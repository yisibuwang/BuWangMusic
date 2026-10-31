<p align="center"><img src="docs/logo.png" alt="BuWang Music" width="200"></p>

<h1 align="center">BuWang Music</h1>

<p align="center"><em>Minecraft 里的音乐播放器，把「一起听歌」改成由服务端同步。</em></p>

当前版本 **v1.0.0**，支持 Minecraft **1.20.1 / 1.21.1 / 1.21.11 / 26.1.2 / 26.2 / 26.3**，每个版本都有 Fabric 与 Forge / NeoForge 两个加载器的产物。

> **这是 [DangoMusic](https://github.com/Sorihiko/DangoMusic) 的非官方修改版（unofficial fork）**，不是官方发布，也未获原作者背书。修改自上游 1.7.1，修改日期 2026-10-31；上游代码版权归原作者所有，本项目以 GPL-3.0 继续分发。改动摘要与第三方组件许可见 [NOTICE](NOTICE)。

本模组基于 [DangoMusic](https://github.com/Sorihiko/DangoMusic) 修改，播放器界面、3D 歌词、歌源登录、歌单和搜索都沿用上游；主要重写了「一起听歌」的同步层，按曾经用过的 [AllMusic](https://github.com/Coloryr/AllMusic) 模组的思路把权威状态放到服务端，不再依赖 Firebase 之类的第三方服务。仅用于和朋友联机时自用，所以有些看起来没必要的功能实际上是以自己朋友联机时的使用习惯修改的

## 功能

- **播放器**：默认按 `M` 打开，推荐、排行榜、我的歌单、搜索，列表 / 封面墙两种视图
- **三个音源**：网易云、QQ 音乐、酷狗，游戏内扫码登录（会员歌曲需要对应平台会员）
- **歌词**：逐字歌词、漂浮在世界里的 3D 歌词
- **一起听歌**
  - 服务端保存房间、当前歌曲、播放进度、共享播放列表和成员权限，客户端只负责播放
  - 房间码 + 服务器内房间列表，可创建、加入、离开
  - 共享播放列表：任何人点歌都追加到同一个列表
  - 房主可分别开关成员的「切歌 / 暂停与拖进度 / 点歌」权限
  - 后加入的玩家从当前进度接着听；本地加载失败不会自动跳歌，每 5 秒自动重试并提示原因
  - 控制栏添加了播放列表按钮，纯个人习惯方便自己查看
  - 为一些提示添加了单独的气泡提示

## 安装

把对应 Minecraft 版本、对应加载器的 jar 放进 `mods/`；一起听歌要求服务端和客户端装同一个 jar。

| Minecraft | Fabric（需 [Fabric API](https://modrinth.com/mod/fabric-api)） | Forge / NeoForge |
| --------- | ---------------------------------------- | ---------------- |
| 1.20.1   | `BuWangMusic-<版本>+1.20.1-fabric.jar` | Forge 47.x：`…+1.20.1-forge.jar` |
| 1.21.1   | `BuWangMusic-<版本>+1.21.1-fabric.jar` | NeoForge 21.1.x：`…+1.21.1-neoforge.jar` |
| 1.21.11  | `BuWangMusic-<版本>+1.21.11-fabric.jar` | NeoForge 21.11.x：`…+1.21.11-neoforge.jar` |
| 26.1.2   | `BuWangMusic-<版本>+26.1.2-fabric.jar` | NeoForge 26.1.2：`…+26.1.2-neoforge.jar` |
| 26.2     | `BuWangMusic-<版本>+26.2-fabric.jar` | NeoForge 26.2：`…+26.2-neoforge.jar` |
| 26.3     | `BuWangMusic-<版本>+26.3-fabric.jar` | NeoForge 26.3：`…+26.3-neoforge.jar` |

一起听歌需要**服务端和客户端装同一个版本**：服务端没有装（或版本不一致）时，客户端只是没有「一起听歌」入口，单人游戏和普通播放不受影响。

## 用法

1. 进入世界，按 `M` 打开播放器，先在设置或登录页登录想用的音源
2. 侧栏进入「一起听歌」，`Create room` 创建房间（房主）
3. 房主把 5 位房间码告诉朋友，朋友在同一个服务器里输入房间码加入，或在房间列表里直接 `Join`
4. 房主播放的歌曲会自动进入共享播放列表，所有人同步到同一首、同一进度

## 构建

`modern/`（1.20.1 / 1.21.1 / 1.21.11）需要 JDK 21，`modern26/`（26.1.2 / 26.2 / 26.3）需要 JDK 25 与 Gradle 9.7+：

```bash
cd modern
./gradlew :1.20.1-fabric:build :1.20.1-forge:build \
          :1.21.1-fabric:build :1.21.1-neoforge:build \
          :1.21.11-fabric:build :1.21.11-neoforge:build

cd ../modern26
gradle :26.1.2-fabric:build :26.1.2-neoforge:build \
       :26.2-fabric:build   :26.2-neoforge:build \
       :26.3-fabric:build   :26.3-neoforge:build
```

产物在各 `versions/<目标>/build/libs/` 下；仓库根目录的 `build-all.bat` 是上游留下的多版本构建脚本，可作参考。

## 目录

```
common/core     与 Minecraft 无关的核心：播放器、音源、同步协议、界面无关逻辑
common/ui       界面绘制（Glass UI、3D 歌词、播放器页面）
modern/         Minecraft 1.20.1 / 1.21.1 / 1.21.11 的实现，按 versions/<目标> 分包
  src/main     所有现代版本共用（含一起听歌服务端 TogetherServer）
  src/mc<版本>  各版本 Minecraft 相关代码
modern26/       Minecraft 26.x 的实现，结构同上
legacy/         上游的 1.8.9 版本，本仓库不再维护
docs/           设计文档与改动记录
```

「一起听歌」的实现概览（服务端权威状态、网络包、注册入口、实现方式）见 [docs/TOGETHER-SYNC.md](docs/TOGETHER-SYNC.md)。

## 许可证

以 [GPL-3.0](LICENSE) 分发。上游 DangoMusic 同样使用 GPL-3.0，本仓库的修改也遵循同一许可证。

## 致谢

- [DangoMusic](https://github.com/Sorihiko/DangoMusic)：播放器、界面、歌词与音源接入的上游项目
- [AllMusic](https://github.com/Coloryr/AllMusic)：一起听歌「服务端权威 + 客户端同步」思路的参考

音乐版权归各音乐平台所有，本模组只做播放与同步，不提供任何音源。
