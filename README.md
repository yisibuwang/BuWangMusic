<p align="center"><img src="docs/logo.png" alt="BuWang Music" width="200"></p>

<h1 align="center">BuWang Music</h1>

<p align="center"><em>Minecraft 里的音乐播放器，把「一起听歌」改成由服务端同步，新增本地音乐与bilibili的支持</em></p>

当前版本 **v1.2.0**，支持 Minecraft **1.20.1 / 1.21.1 / 1.21.11 / 26.1.2 / 26.2 / 26.3**，每个版本都有 Fabric 与 Forge / NeoForge 两个加载器的产物。

> **这是 [DangoMusic](https://github.com/Sorihiko/DangoMusic) 的非官方修改版**

本模组基于 [DangoMusic](https://github.com/Sorihiko/DangoMusic) 修改，播放器界面、3D 歌词、歌源登录、歌单和搜索都沿用上游；主要重写了「一起听歌」的同步层，按曾经用过的 [AllMusic](https://github.com/Coloryr/AllMusic) 模组的思路把权威状态放到服务端，不再依赖 Firebase 之类的第三方服务。仅用于和朋友联机时自用，所以有些看起来没必要的功能实际上是以自己朋友联机时的使用习惯修改的

## 功能

- **播放器**：默认按 `M` 打开，推荐、排行榜、我的歌单、搜索，列表 / 封面墙两种视图
- **五个音源**：网易云、QQ 音乐、酷狗（游戏内扫码登录，会员歌曲需要对应平台会员）、**Bilibili**、以及**本地音乐**
- **Bilibili**：可直接搜索获取视频；复制视频 / 合集 / 收藏夹链接，在「B 站导入」页点一下导成歌单；扫码登录后会自动将「我的收藏」导入歌单
- **本地音乐**：默认扫描游戏内`config/dangomusic/music`文件夹，添加任意多个文件夹（只收 `.mp3`），自动监听文件夹变化；支持按全部 / 歌手 / 专辑 / 文件夹筛选
- **歌词**：逐字歌词、漂浮在世界里的 3D 歌词
- **一起听歌**
  - 服务端保存房间、当前歌曲、播放进度、共享播放列表和成员权限，客户端只负责播放
  - 房间码 + 服务器内房间列表，可创建、加入、离开
  - 共享播放列表：任何人点歌都追加到同一个列表
  - 房主可分别开关成员的「切歌 / 暂停与拖进度 / 点歌」权限
  - 后加入的玩家从当前进度接着听；本地加载失败不会自动跳歌，每 5 秒自动重试并提示原因
  - 控制栏添加了播放列表按钮，纯个人习惯方便自己查看
  - 为一些提示添加了单独的气泡提示
  - 本地歌也能一起听：房间里放本地歌时，房主把文件按实时速度转给其他成员（服务端只转发不解码，250 ms 一片、单片 ≤ 8 KB）；房主可选 **共享音质**（原始 / 128 / 96 / 64 kbps），界面按当前人数提示大约占用的上传带宽
  - B 站曲目不中转：每个成员用自己的账号各自取流
- **服务端可管控**：`config/dangomusic-server.properties`（服务器启动时自动生成）可限制最高码率、是否允许共享本地音乐、是否允许房间里放 B 站曲目；

## 安装

把对应 Minecraft 版本、对应加载器的 jar 放进 `mods/`；一起听歌要求服务端和客户端装同一个 jar。

| Minecraft | Fabric（需 [Fabric API](https://modrinth.com/mod/fabric-api)） | Forge / NeoForge                              |
| --------- | ------------------------------------------------------------- | --------------------------------------------- |
| 1.20.1    | `BuWangMusic-<版本>+1.20.1-fabric.jar`                      | Forge 47.x：`…+1.20.1-forge.jar`           |
| 1.21.1    | `BuWangMusic-<版本>+1.21.1-fabric.jar`                      | NeoForge 21.1.x：`…+1.21.1-neoforge.jar`   |
| 1.21.11   | `BuWangMusic-<版本>+1.21.11-fabric.jar`                     | NeoForge 21.11.x：`…+1.21.11-neoforge.jar` |
| 26.1.2    | `BuWangMusic-<版本>+26.1.2-fabric.jar`                      | NeoForge 26.1.2：`…+26.1.2-neoforge.jar`   |
| 26.2      | `BuWangMusic-<版本>+26.2-fabric.jar`                        | NeoForge 26.2：`…+26.2-neoforge.jar`       |
| 26.3      | `BuWangMusic-<版本>+26.3-fabric.jar`                        | NeoForge 26.3：`…+26.3-neoforge.jar`       |

一起听歌需要**服务端和客户端装同一个版本**：服务端没有装（或版本不一致）时，客户端只是没有「一起听歌」入口，单人游戏和普通播放不受影响。

服务端可选配置：`config/dangomusic-server.properties`（服务器启动时自动生成，改完重启生效）

```properties
allow-bilibili=true      # 是否允许房间里放 B 站曲目（各客户端自己取流，服务端只同步进度）
allow-local-music=true   # 是否允许房主共享本地音乐
max-bitrate=0            # 允许的最高中转码率（kbps），0 = 不限制
```

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

产物在各 `versions/<目标>/build/libs/` 下。仓库根目录的 `build-all.bat` 会按上面的顺序构建全部 12 个目标，只把当前版本号的 `BuWangMusic-*.jar` 收进 `dist/`，并重算 `dist/SHA256SUMS.txt`（1.8.9 的 `legacy/` 不再构建）。

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

## 许可证

以 [GPL-3.0](LICENSE) 分发。上游 DangoMusic 同样使用 GPL-3.0，本仓库的修改也遵循同一许可证。

## 致谢

- [DangoMusic](https://github.com/Sorihiko/DangoMusic)：播放器、界面、歌词与音源接入的上游项目
- [AllMusic](https://github.com/Coloryr/AllMusic)：一起听歌「服务端权威 + 客户端同步」思路的参考
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)：B 站接口行为的公开技术文档

B 站音源的 WBI 签名、DASH 与 fMP4 解析为本项目自行实现，AAC 解码使用 JCodec 及其内置的 JAAD 端口。
