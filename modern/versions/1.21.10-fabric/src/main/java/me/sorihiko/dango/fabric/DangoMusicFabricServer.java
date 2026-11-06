package me.sorihiko.dango.fabric;

import me.sorihiko.dango.together.TogetherServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** 服务端入口：只装配一起听歌的同步层，专用服务器上不启动播放器 UI。 */
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
