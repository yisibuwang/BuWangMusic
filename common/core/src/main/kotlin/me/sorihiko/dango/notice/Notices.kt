package me.sorihiko.dango.notice

/**
 * 界面上漂浮的临时提示（添加成功、权限不够、需要会员……）。
 *
 * 产生提示的地方（一起听歌的服务端同步、播放器）在任意线程 post，
 * 播放器界面每帧 poll 走，画成顶部/底部浮层。这样提示不会只躺在
 * 某个页面的状态栏里，用户切到别的页面也能立刻看到。
 */
object Notices {

    /** 普通消息（白底） */
    const val INFO = 0

    /** 需要注意 / 失败（红底） */
    const val WARN = 1

    /** 成功（绿底） */
    const val OK = 2

    class Item(val text: String, val level: Int) {
        /** 产生时间：界面关着时积压的旧提示可以据此丢掉 */
        val at: Long = System.currentTimeMillis()
    }

    private val queue = ArrayDeque<Item>()

    /** 一条提示最多留这么久没人取（界面关着的时候不会无限堆） */
    fun post(text: String, level: Int = INFO) {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(queue) {
            if (queue.size >= 32) queue.removeFirst()
            queue.addLast(Item(t, level))
        }
    }

    /** 界面每帧取一条；没有时返回 null */
    fun poll(): Item? = synchronized(queue) {
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    /** 换个界面 / 重新加入房间时清空积压 */
    fun clear() {
        synchronized(queue) { queue.clear() }
    }
}
