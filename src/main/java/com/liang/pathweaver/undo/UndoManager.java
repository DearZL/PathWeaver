package com.liang.pathweaver.undo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public class UndoManager {
    private static final Map<UUID, Deque<UndoSnapshot>> STACKS = new HashMap<>();
    private static final int MAX_UNDO = 10;

    public static void push(UUID uuid, List<UndoEntry> entries, Map<Item, Integer> materials) {
        Deque<UndoSnapshot> stack = STACKS.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        stack.push(new UndoSnapshot(new ArrayList<>(entries), materials));
        if (stack.size() > MAX_UNDO) stack.pollLast();
    }

    public static boolean undo(ServerPlayer player) {
        Deque<UndoSnapshot> stack = STACKS.get(player.getUUID());
        if (stack == null || stack.isEmpty()) return false;
        UndoSnapshot snapshot = stack.pop();

        for (int i = snapshot.blocks().size() - 1; i >= 0; i--) {
            UndoEntry e = snapshot.blocks().get(i);
            ServerLevel targetLevel = player.getServer().getLevel(e.dimension());
            if (targetLevel != null && targetLevel.isLoaded(e.pos())) {
                targetLevel.setBlock(e.pos(), e.oldState(), 3);
            }
        }

        if (!player.isCreative() && snapshot.materials() != null) {
            for (Map.Entry<Item, Integer> entry : snapshot.materials().entrySet()) {
                ItemStack refund = new ItemStack(entry.getKey(), entry.getValue());
                if (!player.getInventory().add(refund)) {
                    player.drop(refund, false);
                }
            }
        }

        return true;
    }

    public static void clear(UUID uuid) {
        STACKS.remove(uuid);
    }
}
