package com.liang.pathweaver.command;

import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.item.PathWeaverTool;
import com.liang.pathweaver.logic.PathDataManager;
import com.liang.pathweaver.undo.UndoManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class PathWeaverCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("pathweaver").requires(source -> source.hasPermission(0))
                .then(Commands.literal("undo")
                    .then(Commands.literal("corner").executes(PathWeaverCommand::undoCorner))
                    .then(Commands.literal("region").executes(PathWeaverCommand::undoRegion))
                    .then(Commands.literal("point").executes(PathWeaverCommand::undoPoint))
                    .then(Commands.literal("generate").executes(PathWeaverCommand::undoGenerate))
                )
        );
    }

    private static int undoCorner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerPathData data = PathDataManager.get(player.getUUID());
        if (!data.hasPendingCorner()) {
            ctx.getSource().sendFailure(Component.literal("没有待定的角点。"));
            return 0;
        }
        data.pendingCorner = null;
        PathWeaverTool.syncState(player, data);
        ctx.getSource().sendSuccess(() -> Component.literal("已取消角点1。"), false);
        return 1;
    }

    private static int undoRegion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerPathData data = PathDataManager.get(player.getUUID());
        if (!data.hasRegions()) {
            ctx.getSource().sendFailure(Component.literal("没有框选区域可删除。"));
            return 0;
        }
        int n = data.regions.size();
        data.removeLastRegion();
        PathWeaverTool.syncState(player, data);
        ctx.getSource().sendSuccess(() -> Component.literal("已删除框选区域 #" + n + "。"), false);
        return 1;
    }

    private static int undoPoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerPathData data = PathDataManager.get(player.getUUID());
        if (!data.hasTemplate()) {
            ctx.getSource().sendFailure(Component.literal("没有模板，无法移除路径点。"));
            return 0;
        }
        if (data.pathPoints.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("没有路径点可移除。"));
            return 0;
        }
        BlockPos removed = data.pathPoints.remove(data.pathPoints.size() - 1);
        PathWeaverTool.syncState(player, data);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "已移除路径点 (" + removed.getX() + "," + removed.getY() + "," + removed.getZ()
                + ") (剩余 " + data.pathPoints.size() + " 个)。"), false);
        return 1;
    }

    private static int undoGenerate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean ok = UndoManager.undo(player);
        if (ok) {
            ctx.getSource().sendSuccess(() -> Component.literal("已撤销上一次生成。"), false);
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("没有可撤销的生成操作。"));
            return 0;
        }
    }
}
