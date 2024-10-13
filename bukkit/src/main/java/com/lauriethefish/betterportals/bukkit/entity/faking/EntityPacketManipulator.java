package com.lauriethefish.betterportals.bukkit.entity.faking;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.comphenix.protocol.wrappers.*;
import com.google.inject.Singleton;
import com.lauriethefish.betterportals.bukkit.math.MathUtil;
import com.lauriethefish.betterportals.bukkit.nms.AnimationType;
import com.lauriethefish.betterportals.bukkit.nms.EntityUtil;
import com.lauriethefish.betterportals.bukkit.nms.RotationUtil;
import com.lauriethefish.betterportals.bukkit.util.VersionUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;


/**
 * Deals with sending all of the packets for entity processing
 * Warning: a fair bit of this class is me complaining about mojang
 */
@Singleton
public class EntityPacketManipulator implements IEntityPacketManipulator {
    private static final boolean useHideEntityArray = !VersionUtil.isMcVersionAtLeast("1.17.0") && !VersionUtil.isMcVersionAtLeast("1.17.1");
    private static final boolean useHideEntityList = VersionUtil.isMcVersionAtLeast("1.17.1");
    private static final int entityDataFieldIndex = VersionUtil.isMcVersionAtLeast("1.19.0") ? 4 : 6;
    private static final boolean useNewEntityRotationFields = VersionUtil.isMcVersionAtLeast("1.19.0");

    @Override
    public void showEntity(EntityInfo tracker, Collection<Player> players) {
        // Generate the packet that NMS would normally use to spawn the entity
        PacketContainer spawnPacket = EntityUtil.getRawEntitySpawnPacket(tracker.getEntity());
        if(spawnPacket == null) {return;}

        if(spawnPacket.getUUIDs().size() > 0) {
            spawnPacket.getUUIDs().write(0, tracker.getEntityUniqueId());
        }

        // Use the rendered entity ID
        spawnPacket.getIntegers().write(0, tracker.getEntityId());

        // Translate to the correct rendered position
        Vector actualPos = getPositionFromSpawnPacket(spawnPacket);
        if(tracker.getEntity() instanceof Hanging) {
            actualPos = MathUtil.moveToCenterOfBlock(actualPos);
        }

        Vector renderedPos = tracker.getTranslation().transform(actualPos);
        writePositionToSpawnPacket(spawnPacket, renderedPos);
        setSpawnRotation(spawnPacket, tracker);

        sendPacket(spawnPacket, players);

        // Living Entities also require us to handle entity equipment
        if(tracker.getEntity() instanceof LivingEntity) {
            EntityEquipmentWatcher equipmentWatcher = new EntityEquipmentWatcher((LivingEntity) tracker.getEntity());
            Map<EnumWrappers.ItemSlot, ItemStack> changes = equipmentWatcher.checkForChanges();
            if(changes.size() > 0) {
                sendEntityEquipment(tracker, changes, players);
            }
        }

        sendMetadata(tracker, players);
    }

    private Vector getPositionFromSpawnPacket(PacketContainer packet) {
        // TODO: SPAWN_ENTITY_PAINTING packet is removed by 1.19
        if(packet.getType() == PacketType.Play.Server.SPAWN_ENTITY_PAINTING) {
            BlockPosition blockPos = packet.getBlockPositionModifier().read(0);
            return blockPos.toVector();
        }   else    {
            StructureModifier<Double> doubles = packet.getDoubles();
            return new Vector(
                doubles.read(0),
                doubles.read(1),
                doubles.read(2)
            );
        }
    }

    private void writePositionToSpawnPacket(PacketContainer packet, Vector position) {
        // TODO: SPAWN_ENTITY_PAINTING packet is removed by 1.19
        if(packet.getType() == PacketType.Play.Server.SPAWN_ENTITY_PAINTING) {
            packet.getBlockPositionModifier().write(0, new BlockPosition(position));
        }   else    {
            StructureModifier<Double> doubles = packet.getDoubles();
            doubles.write(0, position.getX());
            doubles.write(1, position.getY());
            doubles.write(2, position.getZ());
        }
    }

    // Every packet handles spawn rotation differently for whatever reason
    // This method will set the correct field(s) for whatever packet type
    private void setSpawnRotation(PacketContainer packet, EntityInfo entityInfo) {
        // Calculate the correct byte yaw, pitch, and head rotation for the entity
        Location renderedPos = entityInfo.findRenderedLocation();

        int yaw = RotationUtil.getPacketRotationInt(renderedPos.getYaw());
        int pitch = RotationUtil.getPacketRotationInt(renderedPos.getPitch());

        PacketType packetType = packet.getType();
        // TODO: SPAWN_ENTITY_PAINTING packet is removed by 1.19
        if(packetType == PacketType.Play.Server.SPAWN_ENTITY_PAINTING) {
            StructureModifier<EnumWrappers.Direction> directions = packet.getDirections();
            EnumWrappers.Direction currentDirection = directions.read(0);
            EnumWrappers.Direction rotated = RotationUtil.rotateBy(currentDirection, entityInfo.getRotation());
            // Make sure to catch this - it should never happen unless something's gone very wrong
            if (rotated == null) {
                throw new IllegalStateException("Portal attempted to rotate painting to an invalid block direction");
            }

            directions.write(0, rotated);
        }   else if(packetType == PacketType.Play.Server.SPAWN_ENTITY)  {
            // Hanging entities use block faces for their rotation
            if(entityInfo.getEntity() instanceof Hanging) {
                // Minecraft deals with this as the ID of the direction as an int for item frames
                // RotationUtil has some convenient methods for dealing with this
                EnumWrappers.Direction currentDirection = RotationUtil.getDirection(packet.getIntegers().read(entityDataFieldIndex));
                if(currentDirection != null) {
                    EnumWrappers.Direction rotated = RotationUtil.rotateBy(currentDirection, entityInfo.getRotation());
                    // Make sure to catch this - it should never happen unless something's gone very wrong
                    if (rotated == null) {
                        throw new IllegalStateException("Portal attempted to rotate a hanging entity to an invalid block direction");
                    }
                    packet.getIntegers().write(entityDataFieldIndex, RotationUtil.getId(rotated));
                }
            }

            // Set the modified pitch and yaw
            if (useNewEntityRotationFields) {
                packet.getBytes().write(0, (byte) pitch);
                packet.getBytes().write(1, (byte) yaw);
            } else {
                packet.getIntegers().write(4, pitch);
                packet.getIntegers().write(5, yaw);
            }
        // TODO: SPAWN_ENTITY_LIVING packet is removed by 1.19
        }   else if(packetType == PacketType.Play.Server.SPAWN_ENTITY_LIVING) {
            packet.getBytes().write(0, (byte) pitch);
            packet.getBytes().write(1, (byte) yaw);
            packet.getBytes().write(2, (byte) yaw);
        }   else if(packetType == PacketType.Play.Server.NAMED_ENTITY_SPAWN) {
            packet.getBytes().write(0, (byte) yaw);
            packet.getBytes().write(1, (byte) pitch);
        }
    }

    @Override
    public void hideEntity(EntityInfo tracker, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);

        if(useHideEntityArray) {
            packet.getIntegerArrays().write(0, new int[]{tracker.getEntityId()});
        }   else  if(useHideEntityList) {
            packet.getIntLists().write(0, Collections.singletonList(tracker.getEntityId()));
        } else  {
            packet.getIntegers().write(0, tracker.getEntityId());
        }
        sendPacket(packet, players);
    }

    @Override
    public void sendEntityMove(EntityInfo tracker, Vector offset, Collection<Player> players) {
        offset = tracker.getRotation().transform(offset);

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE);
        packet.getIntegers().write(0, tracker.getEntityId());

        // We need to convert to the short location, since minecraft is weird and does it like this
        StructureModifier<Short> shorts = packet.getShorts();
        shorts.write(0, (short) (offset.getX() * 4096));
        shorts.write(1, (short) (offset.getY() * 4096));
        shorts.write(2, (short) (offset.getZ() * 4096));
        packet.getBooleans().write(0, tracker.getEntity().isOnGround());

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityMoveLook(EntityInfo tracker, Vector offset, Collection<Player> players) {
        Location entityPos = tracker.findRenderedLocation();
        offset = tracker.getRotation().transform(offset); // Make sure to transform the given offset so that it's correct for the rendered position

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK);
        packet.getIntegers().write(0, tracker.getEntityId());

        // Minecraft is dumb and uses bytes for this (why... just use a float jeb)
        StructureModifier<Byte> bytes = packet.getBytes();
        bytes.write(0, (byte) RotationUtil.getPacketRotationInt(entityPos.getYaw()));
        bytes.write(1, (byte) RotationUtil.getPacketRotationInt(entityPos.getPitch()));

        // We need to convert to the short location, since minecraft is weird and does it like this
        StructureModifier<Short> shorts = packet.getShorts();
        shorts.write(0, (short) (offset.getX() * 4096));
        shorts.write(1, (short) (offset.getY() * 4096));
        shorts.write(2, (short) (offset.getZ() * 4096));
        packet.getBooleans().write(0, tracker.getEntity().isOnGround());

        sendPacket(packet, players);
    }


    @Override
    public void sendEntityLook(EntityInfo tracker, Collection<Player> players) {
        // Transform the entity rotation to the origin of the portal
        Location entityPos = tracker.findRenderedLocation();

        // Minecraft is dumb and uses bytes for this (why... just use a float jeb)
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_LOOK);
        packet.getIntegers().write(0, tracker.getEntityId());

        StructureModifier<Byte> bytes = packet.getBytes();
        bytes.write(0, (byte) RotationUtil.getPacketRotationInt(entityPos.getYaw()));
        bytes.write(1, (byte) RotationUtil.getPacketRotationInt(entityPos.getPitch()));
        packet.getBooleans().write(0, tracker.getEntity().isOnGround());

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityTeleport(EntityInfo tracker, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getIntegers().write(0, tracker.getEntityId());

        Location entityPos = tracker.findRenderedLocation();

        StructureModifier<Double> doubles = packet.getDoubles();
        doubles.write(0, entityPos.getX());
        doubles.write(1, entityPos.getY());
        doubles.write(2, entityPos.getZ());

        // Why minecraft, why must you use a byte!
        StructureModifier<Byte> bytes = packet.getBytes();
        bytes.write(0, (byte) (int) (entityPos.getYaw() * 256.0f / 360.0f));
        bytes.write(1, (byte) (int) (entityPos.getPitch() * 256.0f / 360.0f));

        packet.getBooleans().write(0, tracker.getEntity().isOnGround());
    }

    @Override
    public void sendEntityHeadRotation(EntityInfo tracker, Collection<Player> players) {
        // Entity yaw is actually head rotation in Bukkit
        Location renderedPos = tracker.findRenderedLocation();

        // Why.. why is this a byte
        byte headRotation = (byte) (int) (renderedPos.getYaw() * 256.0f / 360.0f);

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        packet.getIntegers().write(0, tracker.getEntityId());
        packet.getBytes().write(0, headRotation);
        sendPacket(packet, players);
    }

    @Override
    public void sendMount(EntityInfo tracker, Collection<EntityInfo> riding, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.MOUNT);

        int[] ridingIds = new int[riding.size()];
        int i = 0;
        for(EntityInfo ridingTracker : riding) {
            ridingIds[i] = ridingTracker.getEntityId();
            i++;
        }

        packet.getIntegers().write(0, tracker.getEntityId());
        packet.getIntegerArrays().write(0, ridingIds);

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityEquipment(EntityInfo tracker, Map<EnumWrappers.ItemSlot, ItemStack> changes, Collection<Player> players) {
        // Why minecraft, why not just use a map...
        List<Pair<EnumWrappers.ItemSlot, ItemStack>> wrappedChanges = new ArrayList<>();
        changes.forEach((slot, item) -> wrappedChanges.add(new Pair<>(slot, item == null ? new ItemStack(Material.AIR) : item)));

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT);
        packet.getIntegers().write(0, tracker.getEntityId());
        packet.getSlotStackPairLists().write(0, wrappedChanges);
        sendPacket(packet, players);
    }

    @Override
    public void sendMetadata(EntityInfo tracker, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);

        packet.getIntegers().write(0, tracker.getEntityId());

        WrappedDataWatcher dataWatcher = EntityUtil.getActualDataWatcher(tracker.getEntity()); // Use the Entity's actual data watcher, not ProtocolLib's method which gives us a dummy
        if (MinecraftVersion.getCurrentVersion().isAtLeast(new MinecraftVersion("1.19.3"))) {
            List<WrappedDataValue> wrappedDataValueList = dataWatcher.getWatchableObjects().stream()
                    .filter(Objects::nonNull)
                    .map(entry -> {
                        WrappedDataWatcher.WrappedDataWatcherObject dataWatcherObject = entry.getWatcherObject();
                        return new WrappedDataValue(
                                dataWatcherObject.getIndex(),
                                dataWatcherObject.getSerializer(),
                                entry.getRawValue());
                    })
                    .toList();
            packet.getDataValueCollectionModifier().write(0, wrappedDataValueList);
        } else {
            packet.getWatchableCollectionModifier().write(0, dataWatcher.getWatchableObjects());
        }

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityVelocity(EntityInfo tracker, Vector newVelocity, Collection<Player> players) {
        // Rotate the velocity back to the origin of the portal
        Vector entityVelocity = tracker.getRotation().transform(newVelocity);
        // Avoid integer overflows by limiting these values to 3.9
        entityVelocity = MathUtil.min(entityVelocity, new Vector(3.9, 3.9, 3.9));
        entityVelocity = MathUtil.max(entityVelocity, new Vector(-3.9, -3.9, -3.9));

        // Multiply by 8000 to convert the velocity into the integer representation. (jeb, just use a float)
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_VELOCITY);
        StructureModifier<Integer> integers = packet.getIntegers();
        integers.write(0, tracker.getEntityId());
        integers.write(1, (int) (entityVelocity.getX() * 8000.0D));
        integers.write(2, (int) (entityVelocity.getY() * 8000.0D));
        integers.write(3, (int) (entityVelocity.getZ() * 8000.0D));

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityAnimation(EntityInfo tracker, Collection<Player> players, AnimationType animationType) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ANIMATION);

        StructureModifier<Integer> integers = packet.getIntegers();
        integers.write(0, tracker.getEntityId());
        integers.write(1, animationType.getNmsId());

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityPickupItem(EntityInfo tracker, EntityInfo pickedUp, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.COLLECT);

        StructureModifier<Integer> integers = packet.getIntegers();
        integers.write(0, pickedUp.getEntityId());
        integers.write(1, tracker.getEntityId());
        integers.write(2, ((Item) pickedUp.getEntity()).getItemStack().getAmount());

        sendPacket(packet, players);
    }

    private PlayerInfoData generatePlayerInfoData(EntityInfo tracker) {
        // We make a new profile for our fake user, with our fake UUID and the original name
        WrappedGameProfile profile = new WrappedGameProfile(tracker.getEntityUniqueId(), tracker.getEntity().getName());

        Player trackingPlayer = (Player) tracker.getEntity();
        WrappedGameProfile playerProfile = WrappedGameProfile.fromPlayer(trackingPlayer);

        // We remove the existing textures (if any) and add the skin of the original player
        profile.getProperties().removeAll("textures");
        profile.getProperties().putAll("textures", playerProfile.getProperties().get("textures"));


        return new PlayerInfoData(
                profile,
                trackingPlayer.getPing(),
                EnumWrappers.NativeGameMode.fromBukkit(trackingPlayer.getGameMode()),
                WrappedChatComponent.fromText(trackingPlayer.getName())
        );
    }

    @Override
    public void sendAddPlayerProfile(EntityInfo tracker, Collection<Player> players) {
        if (MinecraftVersion.getCurrentVersion().isAtLeast(new MinecraftVersion("1.19.3"))) {
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
            packet.getPlayerInfoActions().write(0, Set.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER));

            List<PlayerInfoData> playerInfoDataList = new ArrayList<>();
            playerInfoDataList.add(generatePlayerInfoData(tracker));
            packet.getPlayerInfoDataLists().write(1, playerInfoDataList);
            sendPacket(packet, players);
        } else {
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
            packet.getPlayerInfoAction().write(0, EnumWrappers.PlayerInfoAction.ADD_PLAYER);

            List<PlayerInfoData> playerInfoDataList = new ArrayList<>();
            playerInfoDataList.add(generatePlayerInfoData(tracker));
            packet.getPlayerInfoDataLists().write(0, playerInfoDataList);
            sendPacket(packet, players);
        }
    }

    @Override
    public void sendRemovePlayerProfile(EntityInfo tracker, Collection<Player> players) {
        if (MinecraftVersion.getCurrentVersion().isAtLeast(new MinecraftVersion("1.19.3"))) {
            // TODO: REMOVE_PLAYER packet is removed by 1.19.3, do we need to do anything here?
        } else {
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
            packet.getPlayerInfoAction().write(0, EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);

            List<PlayerInfoData> playerInfoDataList = new ArrayList<>();
            playerInfoDataList.add(generatePlayerInfoData(tracker));
            packet.getPlayerInfoDataLists().write(0, playerInfoDataList);
            sendPacket(packet, players);
        }
    }

    private void sendPacket(PacketContainer packet, Collection<Player> players) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        try {
            for (Player player : players) {
                protocolManager.sendServerPacket(player, packet);
            }
        }   catch(Exception ex) {
            throw new RuntimeException("Failed to send packet", ex);
        }
    }
}
