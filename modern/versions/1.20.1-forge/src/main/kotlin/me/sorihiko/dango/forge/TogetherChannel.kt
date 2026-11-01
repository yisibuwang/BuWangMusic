package me.sorihiko.dango.forge

import me.sorihiko.dango.party.TogetherProtocol
import me.sorihiko.dango.together.TogetherServer
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import java.util.Optional

/**
 * Forge 1.20.1 的网络通道：一个 `dangomusic:together` 通道，包体是一条 JSON 字符串。
 * 和 Fabric 版共用同一套 [TogetherProtocol] 消息，服务端逻辑在 [TogetherServer]。
 *
 * 通道在 mod 构造时就注册（服务器和客户端都要），这样 dedicated server 也能收发。
 */
object TogetherChannel {

    /** 通道版本跟着协议版本走：两边协议不同就直接在握手阶段拒绝，别等到解析消息才发现 */
    private val PROTOCOL = TogetherProtocol.VERSION.toString()
    private val NAME = ResourceLocation("dangomusic", "together")

    val channel: SimpleChannel = NetworkRegistry.newSimpleChannel(
        NAME,
        { PROTOCOL },
        { remote -> remote == PROTOCOL },
        { remote -> remote == PROTOCOL },
    )

    /** 一条消息：服务端和客户端都只传这个 */
    class Payload(val json: String)

    fun register() {
        // 一个消息类同时走两个方向：收到后按 direction 分发，服务端逻辑永远在服务端线程执行
        // （显式声明成 Any?，避免 Kotlin 把 Forge 的内部返回类型推断成对外不可见的类型）
        val registered: Any? = channel.registerMessage(
            0,
            Payload::class.java,
            { msg, buf: FriendlyByteBuf -> buf.writeUtf(msg.json, TogetherProtocol.MAX_BYTES) },
            { buf: FriendlyByteBuf -> Payload(buf.readUtf(TogetherProtocol.MAX_BYTES)) },
            { msg, supplier ->
                val ctx = supplier.get()
                if (ctx.direction == NetworkDirection.PLAY_TO_CLIENT) {
                    ctx.enqueueWork { TogetherClient.receive(msg.json) }
                } else {
                    ctx.enqueueWork {
                        val player = ctx.sender
                        if (player != null) TogetherServer.handle(player, msg.json)
                    }
                }
                ctx.packetHandled = true
            },
            Optional.empty(),
        )
        // 服务端要发消息给某个玩家时走这里
        TogetherServer.install { player, json ->
            channel.send(PacketDistributor.PLAYER.with { player }, Payload(json))
        }
        // 服务器关掉时把会话清空，下次开服是新的
        MinecraftForge.EVENT_BUS.addListener { event: ServerStartedEvent -> TogetherServer.ensureConfigured(event.server) }
        MinecraftForge.EVENT_BUS.addListener { _: ServerStoppingEvent -> TogetherServer.reset() }
        // 玩家断开时把他移出一听
        MinecraftForge.EVENT_BUS.addListener { event: PlayerLoggedOutEvent ->
            (event.entity as? ServerPlayer)?.let { TogetherServer.playerLeft(it) }
        }
    }

    fun sendToServer(json: String) {
        channel.sendToServer(Payload(json))
    }
}
