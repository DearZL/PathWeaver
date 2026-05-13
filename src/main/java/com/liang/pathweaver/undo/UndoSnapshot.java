package com.liang.pathweaver.undo;

import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;

public record UndoSnapshot(List<UndoEntry> blocks, Map<Item, Integer> materials) {}
