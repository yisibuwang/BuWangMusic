package me.sorihiko.dango.forge

import me.sorihiko.dango.party.ListenParty
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.common.MinecraftForge

/**
 * 一起听歌的客户端接线（只在客户端加载）：
 *   登录服务器 → 把通道和自己的 UUID 交给 [ListenParty]，之后所有消息都走 Forge 通道；
 *   退出服务器 → 断开，界面回到"没在一起听歌"。
 */
object TogetherClient {

    fun install() {
        MinecraftForge.EVENT_BUS.addListener { event: ClientPlayerNetworkEvent.LoggingIn -> onLogin(event) }
        MinecraftForge.EVENT_BUS.addListener { _: ClientPlayerNetworkEvent.LoggingOut -> ListenParty.detach() }
    }

    private fun onLogin(event: ClientPlayerNetworkEvent.LoggingIn) {
        val player = event.player ?: return
        ListenParty.attach(player.uuid.toString(), TogetherChannel::sendToServer)
    }

    /** 服务端发来的消息（已经在客户端主线程上） */
    fun receive(json: String) = ListenParty.onMessage(json)
}
