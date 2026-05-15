package com.liang.pathweaver.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stores a captured road template.
 *
 * Local coordinate system:
 *   localZ increases in baseDirection (direction of travel when template was confirmed)
 *   localX increases to the right of baseDirection
 *   localY increases upward
 *
 * Placement formula for each pathDir:
 *   NORTH: world = (ox+lx, oy+ly, oz-lz)
 *   SOUTH: world = (ox-lx, oy+ly, oz+lz)
 *   EAST:  world = (ox+lz, oy+ly, oz+lx)
 *   WEST:  world = (ox-lz, oy+ly, oz-lx)
 */
public class TemplateData {
    // key = local (lx, ly, lz) packed as BlockPos, value = original block snapshot
    public final Map<BlockPos, TemplateBlockData> blocks = new LinkedHashMap<>();
    public final Direction baseDirection;
    public final int length;  // dimension along baseDirection
    public final int width;   // dimension perpendicular (right side)
    public final int height;

    public TemplateData(Direction baseDirection, int length, int width, int height) {
        this.baseDirection = baseDirection;
        this.length = length;
        this.width = width;
        this.height = height;
    }

    /**
     * Converts a local position to world position given a tile origin and path direction.
     */
    public static BlockPos localToWorld(int lx, int ly, int lz, BlockPos origin, Direction pathDir) {
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        return switch (pathDir) {
            case SOUTH -> new BlockPos(ox - lx, oy + ly, oz + lz);
            case EAST  -> new BlockPos(ox + lz, oy + ly, oz + lx);
            case WEST  -> new BlockPos(ox - lz, oy + ly, oz - lx);
            default    -> new BlockPos(ox + lx, oy + ly, oz - lz);
        };
    }

    /**
     * Returns the Rotation needed to transform block states from baseDirection to pathDirection.
     */
    public net.minecraft.world.level.block.Rotation getRotation(Direction pathDir) {
        int[] order = {0, 1, 2, 3}; // N, E, S, W
        int baseIdx = horizontalIndex(baseDirection);
        int pathIdx = horizontalIndex(pathDir);
        int steps = (pathIdx - baseIdx + 4) % 4;
        return net.minecraft.world.level.block.Rotation.values()[steps];
    }

    private static int horizontalIndex(Direction d) {
        return switch (d) {
            case EAST  -> 1;
            case SOUTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }
}
