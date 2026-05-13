package com.liang.pathweaver.logic;

import com.liang.pathweaver.data.PathMode;
import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.data.TemplateData;
import com.liang.pathweaver.undo.UndoEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PathGenerator {

    public static List<UndoEntry> generate(ServerLevel level, PlayerPathData data) {
        List<UndoEntry> undoEntries = new ArrayList<>();
        TemplateData template = data.template;
        List<BlockPos> points = data.pathPoints;

        List<BezierUtil.BezierPoint> tiles = (data.pathMode == PathMode.LINEAR)
                ? BezierUtil.computeLinearTiles(points, template.length)
                : BezierUtil.computeBezierTiles(points, template.length);

        for (BezierUtil.BezierPoint p : tiles) {
            placeTemplate(level, template, p.pos(), p.direction(), undoEntries);
        }
        return undoEntries;
    }

    private static void placeTemplate(ServerLevel level, TemplateData template,
                                       BlockPos origin, Direction pathDir, List<UndoEntry> undo) {
        origin = centerOrigin(origin, pathDir, template.width);
        Rotation rotation = template.getRotation(pathDir);
        for (Map.Entry<BlockPos, BlockState> entry : template.blocks.entrySet()) {
            BlockPos local = entry.getKey();
            BlockState state = entry.getValue();
            BlockPos world = TemplateData.localToWorld(
                    local.getX(), local.getY(), local.getZ(), origin, pathDir);

            if (!level.isLoaded(world)) continue;

            BlockState rotatedState = state.rotate(level, world, rotation);
            BlockState oldState = level.getBlockState(world);
            undo.add(new UndoEntry(world, oldState, level.dimension()));
            level.setBlock(world, rotatedState, 3);
        }
    }

    static BlockPos centerOrigin(BlockPos origin, Direction pathDir, int width) {
        int half = width / 2;
        return switch (pathDir) {
            case EAST  -> origin.offset(0, 0, -half);
            case WEST  -> origin.offset(0, 0,  half);
            case NORTH -> origin.offset(-half, 0, 0);
            case SOUTH -> origin.offset( half, 0, 0);
            default    -> origin;
        };
    }

    public static Map<Item, Integer> collectRequiredBlocks(PlayerPathData data) {
        TemplateData template = data.template;
        List<BlockPos> points = data.pathPoints;

        Map<Item, Integer> itemsPerTile = new HashMap<>();
        for (BlockState state : template.blocks.values()) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                itemsPerTile.merge(item, 1, Integer::sum);
            }
        }

        int tileCount = (data.pathMode == PathMode.LINEAR)
                ? BezierUtil.computeLinearTiles(points, template.length).size()
                : BezierUtil.computeBezierTiles(points, template.length).size();

        itemsPerTile.replaceAll((item, count) -> count * tileCount);
        return itemsPerTile;
    }
}
