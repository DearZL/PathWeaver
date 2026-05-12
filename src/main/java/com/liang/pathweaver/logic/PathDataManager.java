package com.liang.pathweaver.logic;

import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.item.PathWeaverTool;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber
public class PathDataManager {
    private static final Map<UUID, PlayerPathData> DATA = new HashMap<>();

    public static PlayerPathData get(UUID uuid) {
        return DATA.computeIfAbsent(uuid, k -> new PlayerPathData());
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            // 进入游戏时推送空状态，确保客户端显示干净
            PathWeaverTool.syncState(sp, get(sp.getUUID()));
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DATA.remove(event.getEntity().getUUID());
    }
}
