package com.liang.pathweaver.item;

import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.data.TemplateBlockData;
import com.liang.pathweaver.data.TemplateData;
import com.liang.pathweaver.logic.BlockEntityDataHelper;
import com.liang.pathweaver.logic.PathDataManager;
import com.liang.pathweaver.logic.TemplateMaterialHelper;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

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
        tooltip.add(Component.literal("§6▶ 框选模板区域（可叠加多个框取并集）"));
        tooltip.add(Component.literal("  §7右键方块 §f→ 设角点1；再次右键 §f→ 完成一个框"));
        tooltip.add(Component.literal("  §7左键框内方块 §f→ 删除该框；框外 §f→ 正常破坏"));
        tooltip.add(Component.literal("  §7面朝走向 + §cShift§f+右键 §f→ 确认模板"));
        tooltip.add(Component.literal("§6▶ 标记路径点（模板确认后）"));
        tooltip.add(Component.literal("  §7左键方块 §f→ 添加路径点（上限256个）"));
        tooltip.add(Component.literal("  §7右键方块 §f→ 随时重新框选模板（旧模板保留至确认）"));
        tooltip.add(Component.literal("  §7Shift+右键路径点 §f→ 移除该路径点"));
        tooltip.add(Component.literal("  §7Shift+滚轮 §f→ 切换 LINEAR / BEZIER 模式"));
        tooltip.add(Component.literal("§6▶ 生成路径"));
        tooltip.add(Component.literal("  §7Shift+左键方块 §f→ 生成（生存模式自动消耗材料）"));
        tooltip.add(Component.literal("§6▶ /pathweaver undo 撤销操作"));
        tooltip.add(Component.literal("  §7/pathweaver undo corner|region|point|generate §f→ 指定撤销"));
        tooltip.add(Component.literal("  §7  生成撤销最多 §e10§f 次，生存模式返还材料"));
    }

    // Right-click on block
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return InteractionResult.SUCCESS;
        ServerPlayer player = (ServerPlayer) ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        PlayerPathData data = PathDataManager.get(player.getUUID());
        BlockPos clicked = ctx.getClickedPos();

        if (player.isShiftKeyDown()) {
            // Shift+右键路径点 → 移除该路径点
            if (!data.pathPoints.isEmpty()) {
                int idx = data.pathPoints.indexOf(clicked);
                if (idx >= 0) {
                    data.pathPoints.remove(idx);
                    player.sendSystemMessage(Component.literal(
                            "§a[PathWeaver] 已移除路径点 #" + (idx + 1) + " " + fmtPos(clicked)
                            + " | 剩余:" + data.pathPoints.size()));
                    syncState(player, data);
                    return InteractionResult.SUCCESS;
                }
            }
            if (data.hasRegions()) {
                confirmTemplate(player, data, (ServerLevel) ctx.getLevel());
            }
        } else {
            // No shift — always allow region corner selection (including re-selection when template exists)
            if (data.pendingCorner == null) {
                // If path points exist, require client-side confirmation before proceeding
                if (!data.pathPoints.isEmpty()) return InteractionResult.FAIL;
                data.pendingCorner = clicked;
                if (!data.hasTemplate()) data.clearPathPoints();
                String hint = data.hasTemplate()
                        ? " §7(重新框选模板中，旧模板暂留)"
                        : " | 再次右键确定角点2";
                player.sendSystemMessage(Component.literal(
                        "§a[PathWeaver] 框选角点1: " + fmtPos(clicked) + hint));
            } else {
                data.addRegion(data.pendingCorner, clicked);
                BlockPos c1 = data.regions.get(data.regions.size() - 1)[0];
                data.pendingCorner = null;
                if (!data.hasTemplate()) data.clearPathPoints();
                String next = data.hasTemplate()
                        ? " | 面朝走向+Shift+右键确认新模板"
                        : " | 继续右键添加框，或面朝走向+Shift+右键确认模板";
                player.sendSystemMessage(Component.literal(
                        "§a[PathWeaver] 已添加框 #" + data.regions.size()
                        + " (" + fmtPos(c1) + " ~ " + fmtPos(clicked) + ")" + next));
            }
        }
        syncState(player, data);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    private static void confirmTemplate(ServerPlayer player, PlayerPathData data, ServerLevel level) {
        if (!data.hasRegions()) {
            player.sendSystemMessage(Component.literal("§c[PathWeaver] 请先框选至少一个区域！"));
            return;
        }
        Direction facing = player.getDirection();
        TemplateData template = captureTemplate(level, data, facing);
        if (template == null || template.blocks.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[PathWeaver] 所有选区内没有方块！"));
            return;
        }
        TemplateMaterialHelper.Analysis analysis = TemplateMaterialHelper.analyze(template);
        if (!analysis.unsupportedBlocks().isEmpty()) {
            List<String> preview = analysis.unsupportedBlocks();
            int shown = Math.min(3, preview.size());
            String joined = String.join(", ", new ArrayList<>(preview.subList(0, shown)));
            String suffix = preview.size() > shown ? " ..." : "";
            player.sendSystemMessage(Component.literal(
                    "§c[PathWeaver] 模板包含当前不支持复制的方块/流体: " + joined + suffix));
            return;
        }
        if (analysis.materialsPerTile().isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[PathWeaver] 模板没有可独立计费的主体方块。"));
            return;
        }
        data.template = template;
        data.clearSelection();
        data.clearPathPoints(); // 新模板确认时清空路径点
        player.sendSystemMessage(Component.literal(
                "§a[PathWeaver] 模板已保存！方向:" + facing.getName()
                + " 尺寸:" + template.width + "宽x" + template.height + "高x" + template.length + "长"
                + " | 左键标记路径点，Shift+左键生成"));
    }

    private static TemplateData captureTemplate(ServerLevel level, PlayerPathData data, Direction facing) {
        if (data.regions.isEmpty()) return null;

        // Compute overall bounding box across all regions
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos[] r : data.regions) {
            minX = Math.min(minX, Math.min(r[0].getX(), r[1].getX()));
            minY = Math.min(minY, Math.min(r[0].getY(), r[1].getY()));
            minZ = Math.min(minZ, Math.min(r[0].getZ(), r[1].getZ()));
            maxX = Math.max(maxX, Math.max(r[0].getX(), r[1].getX()));
            maxY = Math.max(maxY, Math.max(r[0].getY(), r[1].getY()));
            maxZ = Math.max(maxZ, Math.max(r[0].getZ(), r[1].getZ()));
        }

        int totalWidth = maxX - minX + 1;
        int height     = maxY - minY + 1;
        int totalDepth = maxZ - minZ + 1;

        boolean nsAxis  = (facing == Direction.NORTH || facing == Direction.SOUTH);
        int length      = nsAxis ? totalDepth : totalWidth;
        int crossWidth  = nsAxis ? totalWidth : totalDepth;

        TemplateData template = new TemplateData(facing, length, crossWidth, height);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos wp = new BlockPos(x, y, z);
                    if (!data.isPosInAnyRegion(wp)) continue;
                    BlockState state = level.getBlockState(wp);
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
                    template.blocks.put(
                            new BlockPos(lx, y - minY, lz),
                            new TemplateBlockData(state, BlockEntityDataHelper.capture(level.getBlockEntity(wp))));
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
