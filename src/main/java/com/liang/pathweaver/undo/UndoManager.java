package com.liang.pathweaver.undo;

import com.liang.pathweaver.logic.BlockEntityDataHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public class UndoManager {
    private static final Map<UUID, Deque<UndoSnapshot>> STACKS = new HashMap<>();
    private static final int MAX_UNDO = 10;

    public static void push(UUID uuid, List<UndoEntry> entries, Map<Item, Integer> materials, boolean refundMaterials) {
        Deque<UndoSnapshot> stack = STACKS.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        stack.push(new UndoSnapshot(new ArrayList<>(entries), new LinkedHashMap<>(materials), refundMaterials));
        if (stack.size() > MAX_UNDO) stack.pollLast();
    }

    public static boolean undo(ServerPlayer player) {
        Deque<UndoSnapshot> stack = STACKS.get(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[PathWeaver] 没有可撤销的生成操作。"));
            return false;
        }
        UndoSnapshot snapshot = stack.peek();

        for (UndoEntry entry : snapshot.blocks()) {
            ServerLevel targetLevel = player.getServer().getLevel(entry.dimension());
            if (targetLevel == null || !targetLevel.isLoaded(entry.pos())) {
                player.sendSystemMessage(Component.literal("§c[PathWeaver] 撤销失败：目标区块未加载，先加载相关区域后再试。"));
                return false;
            }
        }

        stack.pop();

        for (int i = snapshot.blocks().size() - 1; i >= 0; i--) {
            UndoEntry e = snapshot.blocks().get(i);
            ServerLevel targetLevel = player.getServer().getLevel(e.dimension());
            if (targetLevel == null) continue;
            targetLevel.setBlock(e.pos(), e.oldState(), 3);
            BlockEntityDataHelper.restore(targetLevel, e.pos(), e.oldState(), e.oldBlockEntityTag());
        }

        if (snapshot.refundMaterials() && snapshot.materials() != null) {
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
