package me.sorihiko.dango.music.bili

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

/**
 * Playlists created by pasting a link (a video, a collection or a favourite folder).
 *
 * They are stored next to the login cookie so the sidebar still lists them after a restart;
 * the rows are snapshots taken at import time, so a deleted video stays visible until the
 * playlist is pasted again.
 */
object BiliImports {
    /** One imported playlist: a key for the file, a title and the snapshot rows. */
    class Item(val key: String, val name: String, val entries: List<BiliEntry>)

    @Volatile private var items: List<Item> = emptyList()
    @Volatile private var loaded = false

    private fun file(): java.io.File? = try {
        DangoMusic.instance.clientDir.resolve("bili_imports.json").toFile()
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun all(): List<Item> {
        load()
        return items
    }

    @Synchronized
    fun find(key: String): Item? {
        load()
        return items.firstOrNull { it.key == key }
    }

    /** Adds an import; pasting the same title again replaces the old snapshot instead of stacking up. */
    @Synchronized
    fun save(name: String, entries: List<BiliEntry>): Item? {
        if (entries.isEmpty()) return null
        load()
        val title = name.ifEmpty { "Bilibili import" }
        val existing = items.firstOrNull { it.name == title }
        if (existing != null) {
            items = items.map { if (it.key == existing.key) Item(existing.key, title, entries) else it }
            write()
            return items.first { it.key == existing.key }
        }
        val key = "i" + System.currentTimeMillis().toString(36)
        val item = Item(key, title, entries)
        items = items + item
        write()
        return item
    }

    @Synchronized
    fun remove(key: String) {
        load()
        val next = items.filterNot { it.key == key }
        if (next.size == items.size) return
        items = next
        write()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = file() ?: return
        if (!f.isFile) return
        try {
            val root = JsonParser().parse(f.readText(Charsets.UTF_8)).asJsonObject
            val array = root.getAsJsonArray("items") ?: return
            val list = ArrayList<Item>()
            for (element in array) {
                val o = element.asJsonObject
                val key = o.str("key")
                if (key.isEmpty()) continue
                val rows = o.getAsJsonArray("entries")?.mapNotNull { row(it.asJsonObject) }.orEmpty()
                if (rows.isEmpty()) continue
                list += Item(key, o.str("name"), rows)
            }
            items = list
        } catch (e: Exception) {
            DangoLogger.warn("Bilibili imports read failed: {}", e.toString())
        }
    }

    private fun row(o: JsonObject): BiliEntry? {
        val bvid = o.str("bvid")
        if (bvid.isEmpty()) return null
        return BiliEntry(bvid, o.str("title"), o.str("author"), o.str("cover"), o.num("durationMs"), o.num("cid"))
    }

    private fun write() {
        val f = file() ?: return
        try {
            val root = JsonObject()
            val array = JsonArray()
            for (item in items) {
                val o = JsonObject()
                o.addProperty("key", item.key)
                o.addProperty("name", item.name)
                val rows = JsonArray()
                for (entry in item.entries) {
                    val r = JsonObject()
                    r.addProperty("bvid", entry.bvid)
                    r.addProperty("title", entry.title)
                    r.addProperty("author", entry.author)
                    r.addProperty("cover", entry.cover)
                    r.addProperty("durationMs", entry.durationMs)
                    if (entry.cid != 0L) r.addProperty("cid", entry.cid)
                    rows.add(r)
                }
                o.add("entries", rows)
                array.add(o)
            }
            root.add("items", array)
            f.parentFile?.mkdirs()
            // 先写临时文件再替换：中途崩掉也不会留下半个 JSON，把已导入的歌单全丢掉
            val temp = f.toPath().resolveSibling(f.name + ".tmp")
            java.nio.file.Files.write(temp, root.toString().toByteArray(Charsets.UTF_8))
            try {
                java.nio.file.Files.move(temp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                java.nio.file.Files.move(temp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            DangoLogger.warn("Bilibili imports write failed: {}", e.toString())
        }
    }

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.num(key: String): Long = get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
}
