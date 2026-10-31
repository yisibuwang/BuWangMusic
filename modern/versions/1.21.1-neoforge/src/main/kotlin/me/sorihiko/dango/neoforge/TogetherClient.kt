package me.sorihiko.dango.neoforge

import me.sorihiko.dango.party.ListenParty
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.NeoForge

/** 客户端侧接线：收到服务端的同步包交给 ListenParty，本地操作再发回服务端。 */
object TogetherClient {
    fun install() {
        NeoForge.EVENT_BUS.addListener(TogetherClient::onLoggingIn)
        NeoForge.EVENT_BUS.addListener(TogetherClient::onLoggingOut)
    }

    fun receive(json: String) {
        ListenParty.onMessage(json)
    }

    private fun onLoggingIn(event: ClientPlayerNetworkEvent.LoggingIn) {
        ListenParty.attach(event.player.uuid.toString(), TogetherClient::sendToServer)
    }

    private fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        ListenParty.detach()
    }

    fun sendToServer(json: String) {
        val connection = Minecraft.getInstance().connection ?: return
        if (!connection.hasChannel(TogetherChannel.TYPE)) return
        connection.send(TogetherChannel.Payload(json))
    }
}
