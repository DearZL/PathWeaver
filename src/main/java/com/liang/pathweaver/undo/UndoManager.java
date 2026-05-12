package com.liang.pathweaver.undo;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class UndoManager {
    private static final Map<UUID, Deque<List<UndoEntry>>> STACKS = new HashMap<>();
    private static final int MAX_UNDO = 10;

    public static void push(UUID uuid, List<UndoEntry> entries) {
        Deque<List<UndoEntry>> stack = STACKS.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        stack.push(new ArrayList<>(entries));
        if (stack.size() > MAX_UNDO) stack.pollLast();
    }

    public static boolean undo(UUID uuid, MinecraftServer server) {
        Deque<List<UndoEntry>> stack = STACKS.get(uuid);
        if (stack == null || stack.isEmpty()) return false;
        List<UndoEntry> entries = stack.pop();
        for (int i = entries.size() - 1; i >= 0; i--) {
            UndoEntry e = entries.get(i);
            ServerLevel targetLevel = server.getLevel(e.dimension());
            if (targetLevel != null && targetLevel.isLoaded(e.pos())) {
                targetLevel.setBlock(e.pos(), e.oldState(), 3);
            }
        }
        return true;
    }

    public static void clear(UUID uuid) {
        STACKS.remove(uuid);
    }
}
