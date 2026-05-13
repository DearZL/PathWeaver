package com.liang.pathweaver.logic;

import com.liang.pathweaver.PathWeaver;
import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.item.PathWeaverTool;
import com.liang.pathweaver.registry.ModItems;
import com.liang.pathweaver.undo.UndoManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber
public class PathDataManager {
    private static final Map<UUID, PlayerPathData> DATA = new HashMap<>();

    public static PlayerPathData get(UUID uuid) {
        return DATA.computeIfAbsent(uuid, k -> new PlayerPathData());
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer().getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            // Check client mod version from handshake data.
            // If versions mismatch the network channel won't work — reject early.
            // Reject if client doesn't have PathWeaver at all.
            // Version mismatch is caught by the network channel built-in check
            // (packets silently dropped) + displayTest="MATCH_VERSION" red X.
            var connData = NetworkHooks.getConnectionData(sp.connection.connection);
            if (connData != null) {
                List<String> clientMods = connData.getModList();
                if (clientMods != null && !clientMods.contains(PathWeaver.MOD_ID)) {
                    sp.connection.disconnect(Component.literal(
                            "§c[PathWeaver] 客户端未安装此 Mod，拒绝连接"));
                    return;
                }
            }

            UUID uuid = sp.getUUID();
            DATA.remove(uuid);
            UndoManager.clear(uuid);
            PathWeaverTool.syncState(sp, get(uuid));
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        UndoManager.clear(uuid);
        DATA.remove(uuid);
    }
}
