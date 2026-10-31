package me.sorihiko.dango.fabric

import me.sorihiko.dango.party.TogetherProtocol
import me.sorihiko.dango.together.TogetherServer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** 一起听歌的网络通道：一个 JSON 字符串走 `dangomusic:together`。 */
object TogetherChannel {
    val ID: Identifier = Identifier.fromNamespaceAndPath("dangomusic", "together")

    class Payload(val json: String) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Payload> = TYPE
    }

    val CODEC: StreamCodec<RegistryFriendlyByteBuf, Payload> = CustomPacketPayload.codec(
        { payload: Payload, buf: RegistryFriendlyByteBuf -> buf.writeUtf(payload.json, TogetherProtocol.MAX_BYTES) },
        { buf: RegistryFriendlyByteBuf -> Payload(buf.readUtf(TogetherProtocol.MAX_BYTES)) },
    )

    val TYPE: CustomPacketPayload.Type<Payload> = CustomPacketPayload.Type(ID)

    private var registered = false

    /** 双向都注册；客户端同时加载 main 与 client 入口，所以要防止重复注册。 */
    fun registerPayloads() {
        if (registered) return
        registered = true
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC)
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC)
    }

    fun registerServer() {
        registerPayloads()
        ServerPlayNetworking.registerGlobalReceiver(TYPE) { payload, context ->
            val player = context.player()
            context.server().execute { TogetherServer.handle(player, payload.json) }
        }
        TogetherServer.install { player, json -> sendToPlayer(player, json) }
    }

    fun sendToPlayer(player: ServerPlayer, json: String) {
        if (!ServerPlayNetworking.canSend(player, TYPE)) return
        ServerPlayNetworking.send(player, Payload(json))
    }
}
