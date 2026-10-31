package me.sorihiko.dango.fabric

import me.sorihiko.dango.party.TogetherProtocol
import me.sorihiko.dango.together.TogetherServer
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/**
 * Fabric 1.20.1 的网络通道：和 Forge 版共用 `dangomusic:together` 这个通道名，
 * 包里只有一条 JSON 字符串（[TogetherProtocol]），服务端逻辑在 [TogetherServer]。
 *
 * 1.20.1 的 Fabric 还没有原版 CustomPayload，用的是 fabric-api 的 Identifier + PacketByteBuf 通道。
 * `registerServer()` 在服务端入口里调用（单人游戏的集成服务器也会走这里），
 * 客户端那一半在 [TogetherClient] 里注册。
 */
object TogetherChannel {

    val ID: ResourceLocation = ResourceLocation("dangomusic", "together")

    /** 服务端（含集成服务器）：收客户端消息 + 把服务端消息发给玩家 */
    fun registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(ID) { server, player, _, buf, _ ->
            val json = try {
                buf.readUtf(TogetherProtocol.MAX_BYTES)
            } catch (e: Exception) {
                return@registerGlobalReceiver
            }
            // 会话状态只在服务端线程上改
            server.execute { TogetherServer.handle(player, json) }
        }
        TogetherServer.install { player, json -> sendToPlayer(player, json) }
    }

    /** 服务端 → 某个客户端；对方没装（或版本不同）时直接跳过，不让它断线 */
    fun sendToPlayer(player: ServerPlayer, json: String) {
        if (!ServerPlayNetworking.canSend(player, ID)) return
        val buf = PacketByteBufs.create()
        buf.writeUtf(json, TogetherProtocol.MAX_BYTES)
        ServerPlayNetworking.send(player, ID, buf)
    }
}
