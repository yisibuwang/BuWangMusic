package me.sorihiko.dango.neoforge

import me.sorihiko.dango.party.TogetherProtocol
import me.sorihiko.dango.together.TogetherServer
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

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

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToServer(TYPE, CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                TogetherServer.handle(player, payload.json)
            }
        }
        registrar.playToClient(TYPE, CODEC) { payload, context ->
            context.enqueueWork { TogetherClient.receive(payload.json) }
        }
        TogetherServer.install { player, json -> sendToPlayer(player, json) }
    }

    fun sendToPlayer(player: ServerPlayer, json: String) {
        if (!player.connection.hasChannel(TYPE)) return
        player.connection.send(Payload(json))
    }
}
