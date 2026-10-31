package me.sorihiko.dango.neoforge;

import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.modern.ModernKeyBindings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge 入口：把 NeoForge 的事件转成 mod 自己的事件。
 * 客户端才装配播放器（按键、界面、画图），服务端只装配"一起听歌"的同步层，
 * 所以专用服务器上也应该安装这个 mod。
 */
@Mod("buwangmusic")
public class DangoMusicNeoForge {

    public DangoMusicNeoForge(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> TogetherChannel.INSTANCE.register(event));
        if (FMLEnvironment.getDist() == Dist.CLIENT) Client.init(modBus);
    }

    /** 客户端专用部分单独放一个类，专用服务器不会加载它。 */
    private static final class Client {

        static void init(IEventBus modBus) {
            TogetherClient.INSTANCE.install();
            modBus.addListener((RegisterKeyMappingsEvent event) -> ModernKeyBindings.register(event::register));
            // 这个版本的 NeoForge 加载 mod 时游戏本体（Minecraft）还没创建，
            // 读配置文件夹等都要用到它，所以等第一个游戏 tick 再启动
            NeoForge.EVENT_BUS.addListener(Client::onClientTick);
            NeoForge.EVENT_BUS.addListener(Client::onRenderLevel);
        }

        /** 每个游戏 tick 开头 */
        private static void onClientTick(ClientTickEvent.Pre event) {
            ModernDango.start();   // 只有第一次会真正启动
            ModernDango.tick();
        }

        /** 整个世界画完（含天气、云），画手和 HUD 之前 */
        private static void onRenderLevel(RenderLevelStageEvent.AfterLevel event) {
            ModernDango.worldRenderEnd();
        }
    }
}
