package me.sorihiko.dango.music.bili

import net.sourceforge.jaad.aac.Decoder
import net.sourceforge.jaad.aac.SampleBuffer

/**
 * A DASH audio track cut out of a Bilibili m4s file: the raw AAC frames plus the
 * configuration the decoder needs. The file itself is small (4 to 10 MB), so it is
 * kept in memory and seeks pick a frame index instead of bytes.
 */
internal class BiliTrack(
    val asc: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    private val offsets: IntArray,
    private val sizes: IntArray,
    private val bytes: ByteArray,
) {
    val frameCount: Int get() = offsets.size
    /** Every AAC frame carries 1024 samples. */
    val frameMs: Double get() = 1024.0 * 1000.0 / sampleRate

    fun durationMs(): Long = (frameCount * 1024L * 1000L / sampleRate)

    fun indexAt(startMs: Long): Int {
        val frame = (startMs.coerceAtLeast(0L) * sampleRate / 1000L) / 1024L
        return frame.toInt().coerceIn(0, (frameCount - 1).coerceAtLeast(0))
    }

    fun frame(index: Int): ByteArray {
        val offset = offsets[index]
        val size = sizes[index]
        return bytes.copyOfRange(offset, offset + size)
    }

    fun newDecoder(): Decoder = Decoder(asc)

    fun newBuffer(): SampleBuffer = SampleBuffer().apply { setBigEndian(false) }
}

/** Reads the fMP4 layout Bilibili serves: moov for the codec configuration, then moof/mdat pairs. */
internal object BiliMedia {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun parse(bytes: ByteArray): BiliTrack? {
        var pos = 0
        var asc: ByteArray? = null
        var sampleRate = 0
        var channels = 0
        val offsets = ArrayList<Int>()
        val sizes = ArrayList<Int>()
        while (pos + 8 <= bytes.size) {
            var size = u32(bytes, pos)
            val type = text(bytes, pos + 4)
            var header = 8
            if (size == 1) {
                if (pos + 16 > bytes.size) break
                val large = s64(bytes, pos + 8)
                if (large <= 0L || large > Int.MAX_VALUE) break
                size = large.toInt()
                header = 16
            } else if (size == 0) {
                size = bytes.size - pos
            }
            if (size < header || pos + size > bytes.size) break
            when (type) {
                "moov" -> readMoov(bytes, pos + header, pos + size)?.let {
                    asc = it.asc
                    sampleRate = it.sampleRate
                    channels = it.channels
                }
                "moof" -> readMoof(bytes, pos, pos + size)?.let { fragment ->
                    var cursor = fragment.payload
                    for (index in fragment.sizes.indices) {
                        val frameSize = fragment.sizes[index]
                        if (frameSize <= 0 || cursor + frameSize > bytes.size) break
                        // Each fragment starts with a few filler samples that are not AAC frames.
                        if (frameSize >= 20) {
                            offsets += cursor
                            sizes += frameSize
                        }
                        cursor += frameSize
                    }
                }
            }
            pos += size
        }
        val config = asc ?: return null
        if (offsets.isEmpty()) return null
        val rate = if (sampleRate > 0) sampleRate else sampleRateOf(config) ?: 44100
        val ch = if (channels > 0) channels else channelsOf(config) ?: 2
        return BiliTrack(config, rate, ch, offsets.toIntArray(), sizes.toIntArray(), bytes)
    }

    private class Config(val asc: ByteArray, val sampleRate: Int, val channels: Int)

    private fun readMoov(bytes: ByteArray, from: Int, to: Int): Config? {
        var config: Config? = null
        each(bytes, from, to) { type, start, end, body ->
            if (type == "trak") config = config ?: readTrak(bytes, body, end)
        }
        return config
    }

    private fun readTrak(bytes: ByteArray, from: Int, to: Int): Config? {
        var config: Config? = null
        each(bytes, from, to) { type, _, end, body ->
            if (type == "mdia" && config == null) config = readMdia(bytes, body, end)
        }
        return config
    }

    private fun readMdia(bytes: ByteArray, from: Int, to: Int): Config? {
        var config: Config? = null
        each(bytes, from, to) { type, _, end, body ->
            if (type == "minf" && config == null) config = readMinf(bytes, body, end)
        }
        return config
    }

    private fun readMinf(bytes: ByteArray, from: Int, to: Int): Config? {
        var config: Config? = null
        each(bytes, from, to) { type, _, end, body ->
            if (type == "stbl" && config == null) config = readStbl(bytes, body, end)
        }
        return config
    }

    private fun readStbl(bytes: ByteArray, from: Int, to: Int): Config? {
        var config: Config? = null
        each(bytes, from, to) { type, _, end, body ->
            if (type == "stsd" && config == null) config = readStsd(bytes, body, end)
        }
        return config
    }

    private fun readStsd(bytes: ByteArray, from: Int, to: Int): Config? {
        // Full box header (4) then entry count (4), then the sample entries.
        var config: Config? = null
        each(bytes, from + 8, to) { type, _, end, body ->
            if (type == "mp4a" && config == null) config = readMp4a(bytes, body, end)
        }
        return config
    }

    private fun readMp4a(bytes: ByteArray, from: Int, to: Int): Config? {
        // AudioSampleEntry: 6 reserved + 2 data ref + 8 version/revision/vendor + 2 channels
        // + 2 sample size + 4 compression + 4 packet size + 4 sample rate, then child boxes.
        if (from + 28 > to) return null
        val channels = u16(bytes, from + 16)
        val rate = (u32(bytes, from + 24) ushr 16)
        var config: Config? = null
        each(bytes, from + 28, to) { type, _, end, body ->
            if (type == "esds" && config == null) config = readEsds(bytes, body, end, rate, channels)
        }
        return config
    }

    /**
     * The ES_Descriptor (0x03) and the DecoderConfigDescriptor (0x04) nest other descriptors,
     * so the AudioSpecificConfig is found by scanning for the 0x05 tag and validating its shape.
     */
    private fun readEsds(bytes: ByteArray, from: Int, to: Int, rate: Int, channels: Int): Config? {
        // 0x03/0x04 是嵌套描述子，按长度跳会跳过里面的 0x05，所以按字节扫 0x05 并校验 ASC 形状。
        var pos = from + 4   // version + flags
        while (pos + 2 < to) {
            if (bytes[pos].toInt() and 0xff != 0x05) {
                pos++
                continue
            }
            var cursor = pos + 1
            var length = 0
            var count = 0
            while (cursor < to && count < 4) {
                val value = bytes[cursor].toInt() and 0xff
                cursor++
                length = (length shl 7) or (value and 0x7f)
                if (value and 0x80 == 0) break
                count++
            }
            if (length in 2..8 && cursor + length <= to) {
                val asc = bytes.copyOfRange(cursor, cursor + length)
                val sampleRate = sampleRateOf(asc) ?: rate
                val ch = channelsOf(asc) ?: channels
                return Config(asc, sampleRate, ch)
            }
            pos++
        }
        return null
    }

    private class Fragment(val sizes: IntArray, val payload: Int)

    private fun readMoof(bytes: ByteArray, from: Int, to: Int): Fragment? {
        var sizes: IntArray? = null
        var dataOffset = Int.MIN_VALUE
        each(bytes, from + 8, to) { type, _, end, body ->
            if (type == "traf" && sizes == null) {
                each(bytes, body, end) { inner, _, innerEnd, trafBody ->
                    when (inner) {
                        "trun" -> {
                            val parsed = readTrun(bytes, trafBody, innerEnd) ?: return@each
                            if (sizes == null) sizes = parsed.first
                            if (parsed.second != Int.MIN_VALUE) dataOffset = parsed.second
                        }
                    }
                }
            }
        }
        val list = sizes ?: return null
        // Bilibili points the data offset at the media payload, relative to this moof box.
        val payload = if (dataOffset != Int.MIN_VALUE) from + dataOffset else from
        return Fragment(list, payload)
    }

    private fun readTrun(bytes: ByteArray, from: Int, to: Int): Pair<IntArray, Int>? {
        if (from + 8 > to) return null
        val flags = u24(bytes, from + 1)
        var pos = from + 4
        val count = u32(bytes, pos)
        pos += 4
        var dataOffset = Int.MIN_VALUE
        if (flags and 0x000001 != 0) {
            if (pos + 4 > to) return null
            dataOffset = s32(bytes, pos)
            pos += 4
        }
        if (flags and 0x000004 != 0) pos += 4
        val hasDuration = flags and 0x000100 != 0
        val hasSize = flags and 0x000200 != 0
        val hasFlags = flags and 0x000400 != 0
        val hasCts = flags and 0x000800 != 0
        if (!hasSize) return null
        val stride = (if (hasDuration) 4 else 0) + 4 + (if (hasFlags) 4 else 0) + (if (hasCts) 4 else 0)
        if (count <= 0 || count > 1_000_000) return null
        if (pos + count.toLong() * stride > to) return null
        val sizes = IntArray(count)
        for (index in 0 until count) {
            val entry = pos + index * stride + (if (hasDuration) 4 else 0)
            sizes[index] = u32(bytes, entry)
        }
        return sizes to dataOffset
    }

    // ---------------------------------------------------------------- boxes / bytes

    private inline fun each(bytes: ByteArray, from: Int, to: Int, visit: (type: String, start: Int, end: Int, body: Int) -> Unit) {
        var pos = from
        while (pos + 8 <= to) {
            var size = u32(bytes, pos)
            val type = text(bytes, pos + 4)
            var header = 8
            if (size == 1) {
                if (pos + 16 > to) return
                header = 16
                val large = s64(bytes, pos + 8)
                if (large <= 0L || large > Int.MAX_VALUE) return
                size = large.toInt()
            } else if (size == 0) {
                size = to - pos
            }
            if (size < header || pos + size > to) return
            visit(type, pos, pos + size, pos + header)
            pos += size
        }
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)

    private fun u24(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 16) or ((bytes[at + 1].toInt() and 0xff) shl 8) or (bytes[at + 2].toInt() and 0xff)

    private fun u32(bytes: ByteArray, at: Int): Int = s32(bytes, at) and 0x7fffffff

    private fun s32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)

    private fun s64(bytes: ByteArray, at: Int): Long = (u32(bytes, at).toLong() shl 32) or u32(bytes, at + 4).toLong()

    private fun text(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)

    private fun sampleRateOf(asc: ByteArray): Int? {
        if (asc.size < 2) return null
        val first = asc[0].toInt() and 0xff
        val second = asc[1].toInt() and 0xff
        val index = ((first and 0x07) shl 1) or (second ushr 7)
        return rates.getOrNull(index)
    }

    private fun channelsOf(asc: ByteArray): Int? {
        if (asc.size < 2) return null
        val channels = (asc[1].toInt() and 0x78) ushr 3
        return channels.takeIf { it in 1..8 }
    }
}
