package com.liang.pathweaver.data;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class PlayerPathData {
    public BlockPos corner1 = null;
    public BlockPos corner2 = null;
    public TemplateData template = null;
    public final List<BlockPos> pathPoints = new ArrayList<>();
    public PathMode pathMode = PathMode.LINEAR;

    public void clearSelection() {
        corner1 = null;
        corner2 = null;
    }

    public void clearPathPoints() {
        pathPoints.clear();
    }

    public boolean hasTemplate() {
        return template != null;
    }

    public boolean hasBothCorners() {
        return corner1 != null && corner2 != null;
    }
}
