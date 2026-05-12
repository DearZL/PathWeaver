package com.liang.pathweaver.undo;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public record UndoEntry(BlockPos pos, BlockState oldState, ResourceKey<Level> dimension) {}
