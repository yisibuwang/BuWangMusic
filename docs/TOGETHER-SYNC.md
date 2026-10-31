# BuWang Music 一起听歌：服务端同步实现说明

「一起听歌」不再依赖 Firebase 或任何第三方服务：服务端持有权威播放状态，客户端通过 Minecraft 自带网络通道收发消息，同一服务器内的玩家即可同步同一首歌、同一进度。

## 一、新增了什么

- **协议**：`common/core/src/main/kotlin/me/sorihiko/dango/party/TogetherProtocol.kt`（纯 gson，Minecraft 无关）：消息类型、权限位、歌曲/队列/成员的 JSON 互转，以及按字节数把队列或点歌请求切成多个包（`queueMessages` / `addMessages`），避免几百首歌一次塞爆单包上限。
- **服务端**：`modern/src/main/kotlin/me/sorihiko/dango/together/TogetherServer.kt`（所有现代版本共用）：房间表、成员表、共享播放列表、当前歌曲、进度、权限位、广播与清理。
- **加载器接线**：1.20.1 见 `modern/versions/1.20.1-fabric/src/main/kotlin/me/sorihiko/dango/fabric/Together{Channel,Client}.kt` + `DangoMusicFabricServer.java`、`modern/versions/1.20.1-forge/src/main/kotlin/me/sorihiko/dango/forge/Together{Channel,Client}.kt`；1.21.1 / 1.21.11 / 26.x 的 Fabric 与 NeoForge 各有同名的 `Together{Channel,Client}.kt`（见第三节）。
- **客户端**：`common/core/src/main/kotlin/me/sorihiko/dango/party/ListenParty.kt`（重写）：房间状态、房间列表、共享队列、进度推算与校准、失败重试。
- **界面与提示**：`common/ui/src/main/kotlin/me/sorihiko/dango/musicui/PartyPage.kt`（重写）、`QueueSheet.kt`（播放栏 ☰ 弹出的播放列表）、`Toasts.kt` + `common/core/.../notice/Notices.kt`（全局提示浮层）。

原 Firebase 同步层（`Firebase.kt`、`FirebaseConfig.kt`、`PartyHttp.kt`、`PartyTransport.kt`、`PartyProxy.kt`、`FirebaseEndpoints.kt`、`RoomMirror.kt`、`PartyRequest.kt`）与根目录 `firebase.properties.example`、`database.rules.json` 已删除；播放器、歌词、音源登录、歌单、搜索未改动。

## 二、网络通道与消息

只有一个自定义通道，消息体是一条 UTF-8 JSON 字符串：

- Fabric（1.20.1 用 Identifier 版 API；1.21.1+ 用 CustomPayload 版）：通道 `dangomusic:together`，`ServerPlayNetworking` / `ClientPlayNetworking`
- Forge 1.20.1：`NetworkRegistry.newSimpleChannel`，单个 `Payload` 按 `NetworkDirection` 分流
- NeoForge 1.21.1+：`RegisterPayloadHandlersEvent` 的 `playToServer` / `playToClient`
- 协议版本 `v=4`；单包上限 `MAX_BYTES = 30 KB`（Fabric 硬上限 32767 字节），组包目标 `WIRE_BYTES = 26 KB`

| 消息 | 方向 | 用途 |
| --- | --- | --- |
| `list` | C→S | 请求房间列表 |
| `create` / `join{code}` / `leave` | C→S | 创建、凭房间码加入、离开房间 |
| `add{tracks[], more}` | C→S | 把歌曲加进共享播放列表（可分批） |
| `play_now{track}` | C→S | 点到列表里已有的歌：提到队首并立刻播放 |
| `jump{index}` / `next` / `prev` / `remove{index}` | C→S | 切歌、上下一首、房主删歌 |
| `intent{op, pos}` | C→S | 成员的暂停/恢复/跳转请求 |
| `set_perms{mask}` | C→S | 房主改权限（1 切歌 / 2 暂停和进度 / 4 点歌） |
| `host_state{track, pos, playing}` / `ended` | C→S | 房主上报本机播放状态、曲终 |
| `resync` | C→S | 请求补发全量状态 |
| `rooms[]` | S→C | 房间列表（房间码、房主、人数、当前歌） |
| `room` | S→C | 进房快照（房间码、房主、权限、成员、当前歌） |
| `queue{queue[], current, from, total}` | S→C | 共享播放列表，按需分片 |
| `state{track, pos, playing, current, restart?}` | S→C | 播放状态 / 切歌 |
| `members{...}` / `command{op, pos}` / `closed{msg}` / `error{msg}` | S→C | 成员变化、转给房主的操作、房间结束、错误提示 |

## 三、注册入口

所有现代版本共享 `modern/src/main/kotlin`（含 `TogetherServer`）与各自的 `modern/src/mc<版本>/kotlin`，加载器接线放在各 `versions/<目标>` 模块里。

**1.20.1 Fabric**（`modern/versions/1.20.1-fabric/`，旧的 Identifier 通道 API）

- 客户端：`DangoMusicFabric.onInitializeClient()` → `TogetherClient.install()`
  - `ClientPlayNetworking.registerGlobalReceiver(TogetherChannel.ID, …)` 收包 → `ListenParty.onMessage`
  - `ClientPlayNetworking.send(ID, buf)` 发包（`canSend` 保护）
  - 每 tick 检查玩家 UUID，`ListenParty.attach(uuid, sender)`；断线 `detach()`
- 服务端：`DangoMusicFabricServer.onInitialize()`（`fabric.mod.json` 的 `"main"` 入口）
  - `ServerPlayNetworking.registerGlobalReceiver(ID, …)` → `TogetherServer.handle(player, json)`
  - `ServerPlayNetworking.send(player, ID, buf)` 下发
  - `ServerPlayConnectionEvents.DISCONNECT` → `TogetherServer.playerLeft(player)`

**1.20.1 Forge**（`modern/versions/1.20.1-forge/`）

- `DangoMusicForge` 构造函数 → `TogetherChannel.INSTANCE.register()`
  - `NetworkRegistry.newSimpleChannel(ResourceLocation("dangomusic","together"), …)`
  - `registerMessage(0, Payload::class.java, encoder, decoder, handler, Optional.empty())`，handler 按 `ctx.direction` 分流：`PLAY_TO_CLIENT` → `TogetherClient.receive`，其余 → `TogetherServer.handle(ctx.sender, …)`
  - 下发用 `PacketDistributor.PLAYER`；`ServerStoppingEvent` → `reset()`；`PlayerLoggedOutEvent` → `playerLeft()`
- 客户端：`Client.init()` → `TogetherClient.install()`（`ClientPlayerNetworkEvent.LoggingIn/LoggingOut`）

**1.21.1 / 1.21.11 Fabric**（`modern/versions/<版本>-fabric/`，CustomPayload API）

- `TogetherChannel`：`Payload(json) : CustomPacketPayload`，`TYPE = CustomPacketPayload.Type(ID)`，`CODEC = CustomPacketPayload.codec(StreamMemberEncoder, StreamDecoder)`：
  - `PayloadTypeRegistry.playC2S()/playS2C().register(TYPE, CODEC)`（客户端与专用服务器都会跑到，用 `registered` 标志防重）
  - `ServerPlayNetworking.registerGlobalReceiver(TYPE) { payload, context -> context.server().execute { TogetherServer.handle(context.player(), payload.json) } }`
  - `ServerPlayNetworking.send(player, Payload(json))`，由 `canSend(player, TYPE)` 守卫
- 客户端 `TogetherClient.install()`：`ClientPlayNetworking.registerGlobalReceiver(TYPE) { … }`、`ClientPlayNetworking.send(Payload(json))`
- `DangoMusicFabricServer.onInitialize()` → `registerServer()` + `ServerPlayConnectionEvents.DISCONNECT`

**1.21.1 / 1.21.11 / 26.x NeoForge**（`modern/versions/<版本>-neoforge/`）

- `DangoMusicNeoForge` 构造函数 → `modBus.addListener((RegisterPayloadHandlersEvent event) -> TogetherChannel.INSTANCE.register(event))`
- `registrar.playToServer(TYPE, CODEC) { payload, context -> context.enqueueWork { (context.player() as? ServerPlayer)?.let { TogetherServer.handle(it, payload.json) } } }`
- `registrar.playToClient(TYPE, CODEC) { payload, context -> context.enqueueWork { TogetherClient.receive(payload.json) } }`
- 下发 `player.connection.send(Payload(json))`，由 `player.connection.hasChannel(TYPE)` 守卫
- 客户端 `TogetherClient.install()`：`NeoForge.EVENT_BUS` 上的 `ClientPlayerNetworkEvent.LoggingIn/LoggingOut`，发送用 `Minecraft.getInstance().connection`
- 专用服务器可安装：入口用 `FMLEnvironment.dist == Dist.CLIENT` 守卫客户端部分，`neoforge.mods.toml` 依赖改为 `side="BOTH"`

**版本差异备忘**

- `net.minecraft.resources.ResourceLocation` 在 1.21.11 起改名为 `Identifier`（26.x 同样）。
- 26.x 的 `fabric-networking-api-v1 6.3.2` 把 `PayloadTypeRegistry.playC2S() / playS2C()` 改名为 `serverboundPlay() / clientboundPlay()`。
- `CustomPacketPayload.Type` 只接收通道 id，`StreamCodec` 单独传给注册方法。
- 1.21.11 起 `ServerPlayer.server` 为私有，服务端代码统一用 `player.level().getServer()!!`（放在共享的 `TogetherServer` 里，1.20.1 仍然可用）。

## 四、实现方式要点

- **服务端唯一权威**：房间、共享队列、当前歌曲、进度、权限、成员都在 `TogetherServer`；客户端不能单方面改全服状态。
- **事件驱动**：房主在切歌/暂停/继续/跳转时上报一次 `host_state`，服务端盖 `stamp` 后广播；成员用本地收包时刻推算进度，漂移超过阈值（播放中 700ms、暂停 1500ms）才校准，不按 tick 发包。
- **加入即追赶**：新成员进房时拿到当前歌曲 + 实时进度（`pos + (now - stamp)`），从当前位置开始播。
- **成员操作走服务端**：成员请求由服务端按房间权限校验后转发房主执行，房主执行完再上报，客户端之间不直接通信。
- **播放器对接**：`MusicPlayer` 增加房间模式——`playQueue` 变「点歌入队」、`playIndex` 变「跳到第 N 首」、`playWholeQueue` 把整张歌单追加进共享列表、`playRoomTrack`/`mirrorRoomQueue` 对齐服务端队列；房间里加载失败不自动跳歌，改为每 5 秒重试并提示。
- **降级安全**：单人游戏或服务端未装模组时，4 秒内收不到回包即标记「服务器没有开启一起听歌」，本地播放不受影响。

## 五、验证

- 构建（全部 12 个目标均 `BUILD SUCCESSFUL`）：
  - `modern/`（JDK 21）：`:1.20.1-fabric:build :1.20.1-forge:build :1.21.1-fabric:build :1.21.1-neoforge:build :1.21.11-fabric:build :1.21.11-neoforge:build`
  - `modern26/`（JDK 25 + Gradle 9.7.1）：`:26.1.2-fabric:build :26.1.2-neoforge:build :26.2-fabric:build :26.2-neoforge:build :26.3-fabric:build :26.3-neoforge:build`
  - 产物形如 `BuWangMusic-1.0.0+<MC>-<loader>.jar`；Fabric 版本的 `fabric.mod.json` 为 `environment: "*"` 且同时声明 `client` 与 `main` 入口，NeoForge 的 `neoforge.mods.toml` 依赖为 `side="BOTH"`，因此专用服务器可以只装这一个 jar。
- 协议单测：500 首队列拆包后每包 < 26.6 KB，重组顺序与 `current` 一致；`room` 快照不内嵌整队；`play_now` / `restart` 字段往返正确。
- 实机（1.20.1，双客户端）：后加入者进度一致；房主切歌全员同步；暂停/恢复/拖动进度一致；退出重进能追上；无 Firebase 配置可用；单人游戏与服务端未装模组时优雅降级；200~500 首「播放全部」不再断线。
- 其余版本（1.21.1 / 1.21.11 / 26.x）与 1.20.1 共用同一份 `TogetherServer`、`TogetherProtocol` 与 `ListenParty`，差异只在加载器的通道注册与事件接线，编译已全部通过；实机联机验证建议按上面的 1.20.1 清单在对应版本再走一遍。

## 六、已知限制

- 每个玩家用自己的音乐账号解析播放地址，未登录/无会员的歌曲放不了（提示并自动重试），不做账号共享或音频中继。
- 服务端与客户端协议版本必须一致（`v=4`），不一致时服务端提示版本不一致。
- 房间上限 32 人、共享播放列表上限 500 首。
