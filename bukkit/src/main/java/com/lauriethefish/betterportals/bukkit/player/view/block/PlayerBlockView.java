package com.lauriethefish.betterportals.bukkit.player.view.block;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.lauriethefish.betterportals.api.PortalDirection;
import com.lauriethefish.betterportals.bukkit.block.IBlockMap;
import com.lauriethefish.betterportals.bukkit.block.IViewableBlockInfo;
import com.lauriethefish.betterportals.bukkit.block.IMultiBlockChangeManager;
import com.lauriethefish.betterportals.bukkit.config.RenderConfig;
import com.lauriethefish.betterportals.bukkit.math.PlaneIntersectionChecker;
import com.lauriethefish.betterportals.bukkit.portal.IPortal;
import com.lauriethefish.betterportals.bukkit.tasks.BlockUpdateFinisher;
import com.lauriethefish.betterportals.bukkit.util.HeightUtil;
import com.lauriethefish.betterportals.bukkit.util.MaterialUtil;
import com.lauriethefish.betterportals.shared.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class PlayerBlockView implements IPlayerBlockView   {
    private final Player player;
    private final IPortal portal;

    private final IMultiBlockChangeManager.Factory multiBlockChangeManagerFactory;
    private final IPlayerBlockStates blockStates;
    // Avoid resetting block states while they're being updated asynchronously
    private final ReentrantLock statesLock = new ReentrantLock(true);
    private final Logger logger;
    private final BlockUpdateFinisher updateFinisher;
    private final boolean shouldHidePortalBlocks;

    private final int minChunkY;
    private final int maxChunkY;

    // Stored here since we can't access the Bukkit API from another thread
    private volatile Vector playerPosition;

    // Used to avoid a situation where the portal is no longer viewable and the blocks were reset, then an async update comes in and resends them
    private volatile boolean didDeactivate = false;

    @Inject
    public PlayerBlockView(@Assisted Player player, @Assisted IPortal portal,
                           IMultiBlockChangeManager.Factory multiBlockChangeManagerFactory, IPlayerBlockStates.Factory blockStatesFactory,
                           Logger logger, BlockUpdateFinisher updateFinisher, RenderConfig renderConfig) {
        this.player = player;
        this.portal = portal;
        this.multiBlockChangeManagerFactory = multiBlockChangeManagerFactory;
        this.blockStates = blockStatesFactory.create(player);
        this.logger = logger;
        this.updateFinisher = updateFinisher;
        this.shouldHidePortalBlocks = portal.isNetherPortal() && renderConfig.isPortalBlocksHidden();

        World viewWorld = player.getWorld();
        minChunkY = HeightUtil.getMinHeight(viewWorld) >> 4;
        maxChunkY = HeightUtil.getMaxHeight(viewWorld) >> 4;
    }

    // Called whenever the player moves
    @Override
    public void update(boolean refresh) {
        playerPosition = player.getEyeLocation().toVector();
        updateFinisher.scheduleUpdate(this, refresh);

        if(refresh && shouldHidePortalBlocks) {
            setPortalBlocks(WrappedBlockData.createData(Material.AIR));
        }
    }

    @Override
    public void finishReset() {
        statesLock.lock();
        try {
            blockStates.resetAndUpdate(minChunkY, maxChunkY);
        }   finally {
            statesLock.unlock();
        }
    }

    // Called whenever the player is no longer activating the portal
    @Override
    public void onDeactivate(boolean shouldResetStates) {
        didDeactivate = true;
        logger.finer("Player block view deactivating. Should reset states: %b", shouldResetStates);

        if(shouldResetStates) {
            // Reset the portal blocks back to the portal material. Avoid reshowing them if the portal is no longer registered, since then breaking a portal will create ghost portal blocks.
            if(shouldHidePortalBlocks && portal.isRegistered()) {
                setPortalBlocks(getPortalBlockData());
            }

            // If the states lock is available, then brilliant, we can reset the states now
            if(statesLock.tryLock()) {
                logger.finest("Resetting immediately!");
                try {
                    blockStates.resetAndUpdate(minChunkY, maxChunkY);
                } finally {
                    statesLock.unlock();
                }
            }   else    {
                logger.finest("Scheduling reset");
                // Otherwise, we schedule a reset to happen later on
                updateFinisher.scheduleReset(this);
            }
        }
    }

    public void finishUpdate(boolean refresh) {
        if(didDeactivate) {return;} // Avoid resetting block states while they're being updated asynchronously
        if(refresh) {
            logger.finest("Refreshing already sent blocks!");
        }
        statesLock.lock();

        try {
            IMultiBlockChangeManager multiBlockChangeManager = multiBlockChangeManagerFactory.create(player, minChunkY, maxChunkY);
            List<PacketContainer> queuedTileEntityUpdates = new ArrayList<>();

            PlaneIntersectionChecker intersectionChecker = portal.getTransformations().createIntersectionChecker(playerPosition);

            IBlockMap viewableBlockArray = portal.getViewableBlocks();
            List<IViewableBlockInfo> viewableStates = viewableBlockArray.getViewableStates();
            if(viewableStates == null) {
                return;
            }

            for (IViewableBlockInfo blockInfo : viewableStates) {
                Vector position = blockInfo.getOriginPos().getCenterPos();

                boolean visible = intersectionChecker.checkIfIntersects(position);

                // If visible/non-visible, change to the new state
                // However, don't bother resending the packet again if the block has already been changed
                // (unless we're refreshing the sent blocks)
                if (visible) {
                    if (blockStates.setViewable(position, blockInfo) || refresh) {
                        multiBlockChangeManager.addChangeDestination(position, blockInfo);

                        PacketContainer nbtUpdatePacket = viewableBlockArray.getDestinationTileEntityPacket(blockInfo.getOriginPos());
                        if (nbtUpdatePacket != null) {
                            queuedTileEntityUpdates.add(nbtUpdatePacket);
                            logger.fine("Queueing tile state update at destination");
                        }
                    }
                } else {
                    if (blockStates.setNonViewable(position, blockInfo)) {
                        multiBlockChangeManager.addChangeOrigin(position, blockInfo);

                        PacketContainer nbtUpdatePacket = viewableBlockArray.getOriginTileEntityPacket(blockInfo.getOriginPos());
                        if (nbtUpdatePacket != null) {
                            queuedTileEntityUpdates.add(nbtUpdatePacket);
                            logger.fine("Queueing tile state update at origin");
                        }
                    }
                }
            }

            // Show the player the changed states
            multiBlockChangeManager.sendChanges();
            try {
                for (PacketContainer packet : queuedTileEntityUpdates) {
                    ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

            // Removed due to being unreasonably frequent
            //logger.finest("Performed viewable block process. Time taken: %fms", timer.getTimeTakenMillis());
        }   finally     {
            statesLock.unlock();
        }
    }

    // Gets the right rotation of portal block depending on the portal's direction
    private WrappedBlockData getPortalBlockData() {
        PortalDirection portalDirection = portal.getOriginPos().getDirection();
        if(portalDirection == PortalDirection.EAST || portalDirection == PortalDirection.WEST) {
            return WrappedBlockData.createData(MaterialUtil.PORTAL_MATERIAL, 2); // EAST/WEST portal blocks must be rotated
        }   else if(portalDirection == PortalDirection.NORTH || portalDirection == PortalDirection.SOUTH) {
            return WrappedBlockData.createData(MaterialUtil.PORTAL_MATERIAL, 0);
        }   else {
            throw new IllegalStateException("Tried to get portal block data of a horizontal portal");
        }
    }

    // Sets each block inside the portal window to the specified WrappedBlockData
    private void setPortalBlocks(WrappedBlockData data) {
        // Find the position at the bottom-left of the portal by subtracting half of the portal size
        Vector portalPos = portal.getOriginPos().getVector();
        Vector portalSize = portal.getSize();

        PortalDirection portalDirection = portal.getOriginPos().getDirection();
        portalPos.subtract(portalDirection.swapVector(portalSize).multiply(0.5));

        IMultiBlockChangeManager multiBlockChangeManager = multiBlockChangeManagerFactory.create(player, minChunkY, maxChunkY);
        for(int x = 0; x < portalSize.getX(); x++) {
            for(int y = 0; y < portalSize.getY(); y++) {
                // Swap the coordinates if necessary to get the relative position
                Vector relativePos = portalDirection.swapVector(new Vector(x, y, 0.0));
                Vector blockPos = portalPos.clone().add(relativePos);

                multiBlockChangeManager.addChange(blockPos, data);
            }
        }
        multiBlockChangeManager.sendChanges();
    }
}
