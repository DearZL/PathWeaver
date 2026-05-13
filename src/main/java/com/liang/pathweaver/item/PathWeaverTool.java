package com.liang.pathweaver.item;

import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.data.TemplateData;
import com.liang.pathweaver.logic.PathDataManager;
import com.liang.pathweaver.network.ModMessages;
import com.liang.pathweaver.network.S2CUpdateStatePacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.PacketDistributor;

public class PathWeaverTool extends Item {

    public PathWeaverTool() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withStyle(ChatFormatting.GOLD);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§6▶ 第一步：选取模板截面"));
        tooltip.add(Component.literal("  §7右键方块 §f→ 标记角点1，再次右键标记角点2"));
        tooltip.add(Component.literal("  §c  标记角点会清空已有路径点！"));
        tooltip.add(Component.literal("  §7面朝路径走向，Shift+右键方块 §f→ 确认模板"));
        tooltip.add(Component.literal("§6▶ 第二步：标记路径点"));
        tooltip.add(Component.literal("  §7左键方块 §f→ 添加路径点（上限256个）"));
        tooltip.add(Component.literal("  §7Shift+滚轮 §f→ 切换模式"));
        tooltip.add(Component.literal("  §7  LINEAR §f直线: §f≥2点，直线等距铺设"));
        tooltip.add(Component.literal("  §7  BEZIER §f贝塞尔曲线: §f每3点一段，奇数点为端点，偶数点为控制点"));
        tooltip.add(Component.literal("§6▶ 第三步：生成路径"));
        tooltip.add(Component.literal("  §7Shift+左键方块 §f→ 生成（生存模式自动消耗材料）"));
        tooltip.add(Component.literal("§6▶ 撤销（Shift+右键，每次退一步）"));
        tooltip.add(Component.literal("  §7右键空气: §f清角点2 → 清角点1 → 移路径点 → 撤销生成"));
        tooltip.add(Component.literal("  §7右键方块: §f确认模板 / 移路径点 / 撤销生成"));
        tooltip.add(Component.literal("  §7  §f最多可连续撤销 §e10§f 次生成"));
    }

    // Right-click on block
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return InteractionResult.SUCCESS;

        ServerPlayer player = (ServerPlayer) ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        PlayerPathData data = PathDataManager.get(player.getUUID());

        if (player.isShiftKeyDown()) {
            if (data.hasBothCorners()) {
                confirmTemplate(player, data, (ServerLevel) ctx.getLevel());
            } else if (!data.pathPoints.isEmpty()) {
                BlockPos removed = data.pathPoints.remove(data.pathPoints.size() - 1);
                player.sendSystemMessage(Component.literal(
                        "§e[PathWeaver] 已移除路径点 #" + (data.pathPoints.size() + 1)
                        + " " + fmtPos(removed)
                        + " (剩余 " + data.pathPoints.size() + " 个)"));
            } else {
                boolean ok = com.liang.pathweaver.undo.UndoManager.undo(
                        player.getUUID(), player.getServer());
                player.sendSystemMessage(Component.literal(ok
                        ? "§a[PathWeaver] 已撤销上一次生成。"
                        : "§c[PathWeaver] 没有可撤销的操作。"));
            }
        } else {
            markCorner(player, data, ctx.getClickedPos());
        }
        syncState(player, data);
        return InteractionResult.SUCCESS;
    }

    // Shift+右键空气 → 逐步撤销
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player.isShiftKeyDown()) {
            ServerPlayer sp = (ServerPlayer) player;
            PlayerPathData data = PathDataManager.get(sp.getUUID());
            if (data.corner2 != null) {
                data.corner2 = null;
                sp.sendSystemMessage(Component.literal("§e[PathWeaver] 已清除角点2，请重新右键标记"));
                syncState(sp, data);
            } else if (data.corner1 != null) {
                data.corner1 = null;
                sp.sendSystemMessage(Component.literal("§e[PathWeaver] 已清除角点1"));
                syncState(sp, data);
            } else if (!data.pathPoints.isEmpty()) {
                BlockPos removed = data.pathPoints.remove(data.pathPoints.size() - 1);
                sp.sendSystemMessage(Component.literal(
                        "§e[PathWeaver] 已移除路径点 #" + (data.pathPoints.size() + 1)
                        + " " + fmtPos(removed)
                        + " (剩余 " + data.pathPoints.size() + " 个)"));
                syncState(sp, data);
            } else {
                boolean ok = com.liang.pathweaver.undo.UndoManager.undo(
                        sp.getUUID(), sp.getServer());
                sp.sendSystemMessage(Component.literal(ok
                        ? "§a[PathWeaver] 已撤销上一次生成。"
                        : "§c[PathWeaver] 没有可撤销的操作。"));
            }
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    private static void markCorner(ServerPlayer player, PlayerPathData data, BlockPos pos) {
        // 任何角点操作都清除路径点
        data.clearPathPoints();
        if (data.corner1 == null) {
            data.corner1 = pos;
            player.sendSystemMessage(Component.literal(
                    "§a[PathWeaver] 角点1: " + fmtPos(pos) + " | 再次右键标记角点2"));
        } else if (data.corner2 == null) {
            data.corner2 = pos;
            player.sendSystemMessage(Component.literal(
                    "§a[PathWeaver] 角点2: " + fmtPos(pos) + " | Shift+右键确认模板"));
        } else {
            data.corner1 = pos;
            data.corner2 = null;
            player.sendSystemMessage(Component.literal(
                    "§e[PathWeaver] 重置选区，角点1: " + fmtPos(pos)));
        }
    }

    private static void confirmTemplate(ServerPlayer player, PlayerPathData data, ServerLevel level) {
        if (!data.hasBothCorners()) {
            player.sendSystemMessage(Component.literal("§c[PathWeaver] 请先用右键标记两个角点！"));
            return;
        }
        Direction facing = player.getDirection();
        TemplateData template = captureTemplate(level, data, facing);
        if (template == null || template.blocks.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[PathWeaver] 选区内没有方块！"));
            return;
        }
        data.template = template;
        data.clearSelection();
        player.sendSystemMessage(Component.literal(
                "§a[PathWeaver] 模板已保存！方向:" + facing.getName()
                + " 尺寸:" + template.width + "宽x" + template.height + "高x" + template.length + "长"
                + " | 左键标记路径点，Shift+左键生成"));
    }

    private static TemplateData captureTemplate(ServerLevel level, PlayerPathData data, Direction facing) {
        BlockPos c1 = data.corner1, c2 = data.corner2;
        int minX = Math.min(c1.getX(), c2.getX()), maxX = Math.max(c1.getX(), c2.getX());
        int minY = Math.min(c1.getY(), c2.getY()), maxY = Math.max(c1.getY(), c2.getY());
        int minZ = Math.min(c1.getZ(), c2.getZ()), maxZ = Math.max(c1.getZ(), c2.getZ());

        int totalWidth = maxX - minX + 1;
        int height = maxY - minY + 1;
        int totalDepth = maxZ - minZ + 1;

        boolean nsAxis = (facing == Direction.NORTH || facing == Direction.SOUTH);
        int length    = nsAxis ? totalDepth : totalWidth;
        int crossWidth = nsAxis ? totalWidth : totalDepth;

        TemplateData template = new TemplateData(facing, length, crossWidth, height);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (state.isAir()) continue;
                    int lz = switch (facing) {
                        case SOUTH -> z - minZ;
                        case EAST  -> x - minX;
                        case WEST  -> maxX - x;
                        default    -> maxZ - z;
                    };
                    int lx = switch (facing) {
                        case SOUTH -> maxX - x;
                        case EAST  -> z - minZ;
                        case WEST  -> maxZ - z;
                        default    -> x - minX;
                    };
                    template.blocks.put(new BlockPos(lx, y - minY, lz), state);
                }
            }
        }
        return template;
    }

    public static void syncState(ServerPlayer player, PlayerPathData data) {
        ModMessages.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                S2CUpdateStatePacket.fromData(data));
    }

    private static String fmtPos(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
