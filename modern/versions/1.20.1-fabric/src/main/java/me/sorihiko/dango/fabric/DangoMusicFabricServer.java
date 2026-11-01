package me.sorihiko.dango.fabric;

import me.sorihiko.dango.together.TogetherServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Fabric 的服务端入口：单人游戏的集成服务器和 dedicated server 都会跑这里。
 * 只注册一起听歌的通道，不碰任何客户端渲染的东西。
 */
public class DangoMusicFabricServer implements ModInitializer {
    @Override
    public void onInitialize() {
        TogetherChannel.INSTANCE.registerServer();
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> TogetherServer.INSTANCE.ensureConfigured(server));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> TogetherServer.INSTANCE.playerLeft(handler.getPlayer()));
    }
}
