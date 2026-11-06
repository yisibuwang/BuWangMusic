// 核心：事件、模块、设置、网易云接口、播放器、一起听歌。不碰任何绘图。
dependencies {
    "implementation"("org.jcraft:jorbis:0.0.17")     // Ogg Vorbis 解码
    "implementation"("javazoom:jlayer:1.0.1")       // MP3 解码
    "implementation"("com.google.zxing:core:3.3.3") // 登录二维码
    "implementation"("de.sciss:jump3r:1.0.5")        // 本地音乐中转的 MP3 重编码
    "implementation"("org.jcodec:jcodec:0.2.5")      // B 站音源的 AAC 解码（内含 JAAD）
}
