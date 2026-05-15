package com.liang.pathweaver.logic;

import com.liang.pathweaver.data.PathMode;
import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.data.TemplateBlockData;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PathGenerator {

    public static List<UndoEntry> generate(ServerLevel level, PlayerPathData data) {
        List<UndoEntry> undoEntries = new ArrayList<>();
        TemplateData template = data.template;
        List<BezierUtil.BezierPoint> tiles = computeTiles(data);

        for (BezierUtil.BezierPoint p : tiles) {
            placeTemplate(level, template, p.pos(), p.direction(), undoEntries);
        }
        return undoEntries;
    }

    public static BlockPos findFirstUnloadedPlacement(ServerLevel level, PlayerPathData data) {
        TemplateData template = data.template;
        for (BezierUtil.BezierPoint tile : computeTiles(data)) {
            BlockPos origin = centerOrigin(tile.pos(), tile.direction(), template.width);
            for (BlockPos local : template.blocks.keySet()) {
                BlockPos world = TemplateData.localToWorld(
                        local.getX(), local.getY(), local.getZ(), origin, tile.direction());
                if (!level.isLoaded(world)) {
                    return world;
                }
            }
        }
        return null;
    }

    private static void placeTemplate(ServerLevel level, TemplateData template,
                                       BlockPos origin, Direction pathDir, List<UndoEntry> undo) {
        origin = centerOrigin(origin, pathDir, template.width);
        Rotation rotation = template.getRotation(pathDir);
        for (Map.Entry<BlockPos, TemplateBlockData> entry : template.blocks.entrySet()) {
            BlockPos local = entry.getKey();
            TemplateBlockData templateBlock = entry.getValue();
            BlockState state = templateBlock.state();
            BlockPos world = TemplateData.localToWorld(
                    local.getX(), local.getY(), local.getZ(), origin, pathDir);

            BlockState rotatedState = state.rotate(level, world, rotation);
            BlockState oldState = level.getBlockState(world);
            undo.add(new UndoEntry(
                    world,
                    oldState,
                    BlockEntityDataHelper.capture(level.getBlockEntity(world)),
                    level.dimension()));
            level.setBlock(world, rotatedState, 3);
            BlockEntityDataHelper.restore(level, world, rotatedState, templateBlock.blockEntityTagCopy());
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
        int tileCount = computeTiles(data).size();
        return TemplateMaterialHelper.multiplyByTiles(template, tileCount);
    }

    public static List<BezierUtil.BezierPoint> computeTiles(PlayerPathData data) {
        List<BlockPos> points = data.pathPoints;
        return (data.pathMode == PathMode.LINEAR)
                ? BezierUtil.computeLinearTiles(points, data.template.length)
                : BezierUtil.computeBezierTiles(points, data.template.length);
    }
}
