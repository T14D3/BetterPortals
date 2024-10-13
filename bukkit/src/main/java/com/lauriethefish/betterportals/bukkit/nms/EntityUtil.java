package com.lauriethefish.betterportals.bukkit.nms;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerEntity;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Marker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class EntityUtil {
    /**
     * ProtocolLib unfortunately doesn't provide any methods for getting the <i>actual</i> {@link WrappedDataWatcher} of an entity.
     * {@link WrappedDataWatcher#WrappedDataWatcher(Entity)} doesn't do this - it returns a new empty {@link WrappedDataWatcher} for this entity.
     * This function wraps the entities actual watcher in the ProtocolLib wrapper.
     * @param entity The entity to wrap the data watcher of
     * @return The wrapped data watcher
     */
    @NotNull
    public static WrappedDataWatcher getActualDataWatcher(@NotNull Entity entity) {
        return new WrappedDataWatcher(((CraftEntity)entity).getHandle().getEntityData());
    }

    /**
     * Getting a valid spawn packet that works correctly for a specific {@link Entity} is surprisingly difficult.
     * This method uses some NMS to get the correct spawn packet.
     * @param entity The entity to get the spawn packet of
     * @return A container with the valid packet, or <code>null</code> since some entities can't be spawned with a packet.
     */
    public static @Nullable PacketContainer getRawEntitySpawnPacket(@NotNull Entity entity) {
        if (entity instanceof EnderDragonPart) return null;
        if (entity instanceof Marker) return null;

        net.minecraft.world.entity.Entity nmsEntity = ((CraftEntity) entity).getHandle();
        ServerEntity serverEntity = new ServerEntity(nmsEntity.level().getMinecraftWorld(), nmsEntity, 10, true, a -> {}, Set.of());
        // nmsEntity.recreateFromPacket(new ClientboundAddEntityPacket(nmsEntity, serverEntity));
        return PacketContainer.fromPacket(new ClientboundAddEntityPacket(nmsEntity, serverEntity));
    }
}
