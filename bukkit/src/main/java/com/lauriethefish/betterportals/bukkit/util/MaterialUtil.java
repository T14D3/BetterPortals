package com.lauriethefish.betterportals.bukkit.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.block.TileState;

/**
 * The materials/data of certain blocks that we need change based on version.
 * This small wrapper allows us to avoid the issues.
 */
public class MaterialUtil {
    public static final Material PORTAL_MATERIAL;

    static {
        PORTAL_MATERIAL = Material.NETHER_PORTAL;
    }

    /**
     * Checks if a block with type <code>material</code> would be a tile entity.
     * Prior: {@code
     * This is used for optimisation, because calling {@link Block#getState}
     * and then checking if it's an instance of TileState is expensive and doesn't work on 1.12, so this reduces it to a hashmap lookup.
     * }
     * It now uses "unstable" {@link BlockType} to get the class without creating an actual BlockData.
     * @param material The material to test
     * @return Whether its BlockData is a tile entity
     */
    public static boolean isTileEntity(Material material) {
        return material.isBlock() && TileState.class.isAssignableFrom(material.asBlockType().getBlockDataClass());
    }
}
