package com.liang.pathweaver.logic;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public class MaterialChecker {

    public static boolean checkAndDeduct(ServerPlayer player, Map<Item, Integer> required) {
        if (player.isCreative()) return true;
        StringBuilder missing = new StringBuilder();

        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            Item item = entry.getKey();
            int need = entry.getValue();
            int have = countItem(player.getInventory(), item);
            if (have < need) {
                if (!missing.isEmpty()) missing.append(", ");
                missing.append(item.getDescription().getString()).append(" x").append(need - have);
            }
        }

        if (!missing.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[PathWeaver] 材料不足: " + missing));
            return false;
        }

        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            deductItem(player.getInventory(), entry.getKey(), entry.getValue());
        }
        return true;
    }

    private static int countItem(Inventory inv, Item item) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private static void deductItem(Inventory inv, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) {
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
            }
        }
    }
}
