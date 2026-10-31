package me.sorihiko.dango.fabric

import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.party.TogetherProtocol
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs

/**
 * 客户端这边：注册收包、把 [ListenParty] 接到当前服务器。
 *
 * 进服务器（包括单人）后把本地 UUID 交给 [ListenParty]，
 * 之后它发的每条消息都由 [sendToServer] 出去；
 * 退出时断开，避免把旧会话的状态带到下一个服务器。
 */
object TogetherClient {

    fun install() {
        ClientPlayNetworking.registerGlobalReceiver(TogetherChannel.ID) { client, _, buf, _ ->
            val json = try {
                buf.readUtf(TogetherProtocol.MAX_BYTES)
            } catch (e: Exception) {
                return@registerGlobalReceiver
            }
            client.execute { ListenParty.onMessage(json) }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player ?: return@register
            val uuid = player.uuid.toString()
            if (ListenParty.uid != uuid) ListenParty.attach(uuid, TogetherClient::sendToServer)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ListenParty.detach() }
    }

    /** 客户端 → 服务端；服务端没装这个模组时通道不存在，直接不发 */
    fun sendToServer(json: String) {
        if (!ClientPlayNetworking.canSend(TogetherChannel.ID)) return
        val buf = PacketByteBufs.create()
        buf.writeUtf(json, TogetherProtocol.MAX_BYTES)
        ClientPlayNetworking.send(TogetherChannel.ID, buf)
    }
}
