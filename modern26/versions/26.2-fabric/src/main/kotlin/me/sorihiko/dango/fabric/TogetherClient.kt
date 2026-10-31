package me.sorihiko.dango.fabric

import me.sorihiko.dango.party.ListenParty
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/** 客户端侧接线：收到服务端的同步包交给 ListenParty，本地操作再发回服务端。 */
object TogetherClient {
    fun install() {
        TogetherChannel.registerPayloads()
        ClientPlayNetworking.registerGlobalReceiver(TogetherChannel.TYPE) { payload, context ->
            context.client().execute { ListenParty.onMessage(payload.json) }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player ?: return@register
            val uuid = player.uuid.toString()
            if (ListenParty.uid != uuid) ListenParty.attach(uuid, TogetherClient::sendToServer)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ListenParty.detach() }
    }

    fun sendToServer(json: String) {
        if (!ClientPlayNetworking.canSend(TogetherChannel.TYPE)) return
        ClientPlayNetworking.send(TogetherChannel.Payload(json))
    }
}
