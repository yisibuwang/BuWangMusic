package me.sorihiko.dango.music

import me.sorihiko.dango.platform.ListenerEnvironment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * 环境音效：歌曲跟着玩家所在的地方变化，在解码线程里、写给声卡之前处理。
 *   水下：滤掉高频，像隔着水听
 *   室内（头顶看不到天空）：轻微变闷，带一点房间的混响
 *   洞穴（看不到天空、四周闭塞）：混响更重
 *   下雨 / 打雷：音量稍微压低，让雨声透出来
 *
 * 游戏线程每 tick 调 [update] 算出目标值；解码线程每帧调 [process]，参数朝目标慢慢过渡（约半秒），不会突变出爆音。
 * 全都不起作用时直接跳过，不占性能。
 */
object EnvironmentAudio {
    // 混响的延时长度（毫秒）。要放在最前面：下面的状态数组按它们的个数建
    private val COMB_MS = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)
    private val ALLPASS_MS = floatArrayOf(5.0f, 1.7f)

    /** 设置（由 Music 模块每 tick 同步过来） */
    @Volatile var enabled = false
    @Volatile var water = true
    @Volatile var cave = true
    @Volatile var indoor = true
    @Volatile var rain = true
    @Volatile var strength = 1f

    @Volatile private var targetCutoff = OPEN_CUTOFF
    @Volatile private var targetWet = 0f
    @Volatile private var targetGain = 1f

    /** 游戏线程：根据玩家周围的环境算出要过渡到的效果 */
    fun update(env: ListenerEnvironment?) {
        if (!enabled || env == null) {
            targetCutoff = OPEN_CUTOFF
            targetWet = 0f
            targetGain = 1f
            return
        }
        val s = strength.coerceIn(0f, 1f)
        var cutoff = OPEN_CUTOFF
        var wet = 0f
        var gain = 1f
        if (water && env.underwater) {
            cutoff = minOf(cutoff, WATER_CUTOFF)
            gain *= 0.85f
        }
        if (!env.skyVisible) {
            if (indoor) {
                cutoff = minOf(cutoff, INDOOR_CUTOFF)
                wet = INDOOR_WET
            }
            // 四周越闭塞回声越重：一半方向碰到方块开始加，全部碰到最重
            if (cave) wet = maxOf(wet, ((env.enclosure - 0.5f) / 0.5f).coerceIn(0f, 1f) * CAVE_WET)
        }
        if (rain) gain *= 1f - 0.2f * env.rain
        // 强度：在"原样"和"满效果"之间插（截止频率按对数插，听起来才均匀）
        targetCutoff = exp(ln(OPEN_CUTOFF) + (ln(cutoff) - ln(OPEN_CUTOFF)) * s)
        targetWet = wet * s
        targetGain = 1f - (1f - gain) * s
    }

    // ------------------------------------------------------------ 解码线程

    private var cutoff = OPEN_CUTOFF
    private var wet = 0f
    private var gain = 1f

    private var rate = 0
    private var coefCutoff = -1f
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private val x1 = FloatArray(2); private val x2 = FloatArray(2); private val y1 = FloatArray(2); private val y2 = FloatArray(2)

    private var combs: Array<FloatArray> = emptyArray()
    private val combPos = IntArray(COMB_MS.size)
    private val combStore = FloatArray(COMB_MS.size)
    private var allpasses: Array<FloatArray> = emptyArray()
    private val allpassPos = IntArray(ALLPASS_MS.size)

    /** 新的一首 / 拖了进度：清掉滤波器和混响里残留的声音 */
    fun reset() {
        x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f)
        for (c in combs) c.fill(0f)
        for (a in allpasses) a.fill(0f)
        combStore.fill(0f)
        cutoff = targetCutoff
        wet = targetWet
        gain = targetGain
        coefCutoff = -1f
    }

    /** 处理一帧（交错存放的 16 位样本，[length] 是样本总数），原地修改 */
    fun process(pcm: ShortArray, length: Int, channels: Int, sampleRate: Int, frameMs: Float) {
        val k = 1f - exp(-frameMs / SMOOTH_MS)
        cutoff = exp(ln(cutoff) + (ln(targetCutoff) - ln(cutoff)) * k)
        wet += (targetWet - wet) * k
        gain += (targetGain - gain) * k
        val filtering = cutoff < OPEN_CUTOFF * 0.97f
        val reverb = wet > 0.002f
        if (!filtering && !reverb && gain > 0.999f) return

        val ch = channels.coerceIn(1, 2)
        if (sampleRate != rate) setup(sampleRate)
        if (filtering && cutoff != coefCutoff) lowPass(cutoff)
        var i = 0
        while (i + ch - 1 < length) {
            var mono = 0f
            for (c in 0 until ch) {
                var v = pcm[i + c].toFloat()
                if (filtering) {
                    val y = b0 * v + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                    x2[c] = x1[c]; x1[c] = v
                    y2[c] = y1[c]; y1[c] = y
                    v = y
                }
                pcm[i + c] = v.toInt().coerceIn(-32768, 32767).toShort()
                mono += v
            }
            val rev = if (reverb) reverbSample(mono / ch) * wet else 0f
            for (c in 0 until ch) {
                pcm[i + c] = ((pcm[i + c] + rev) * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
            i += ch
        }
    }

    private fun setup(sampleRate: Int) {
        rate = sampleRate
        combs = Array(COMB_MS.size) { FloatArray((COMB_MS[it] * sampleRate / 1000f).toInt().coerceAtLeast(1)) }
        allpasses = Array(ALLPASS_MS.size) { FloatArray((ALLPASS_MS[it] * sampleRate / 1000f).toInt().coerceAtLeast(1)) }
        combPos.fill(0)
        allpassPos.fill(0)
        reset()
    }

    /** 二阶低通（RBJ 公式，Q = 0.707） */
    private fun lowPass(fc: Float) {
        coefCutoff = fc
        val w0 = 2.0 * PI * fc.coerceAtMost(rate * 0.45f) / rate
        val alpha = sin(w0) / (2.0 * 0.7071)
        val cw = cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cw) / 2.0 / a0).toFloat()
        b1 = ((1.0 - cw) / a0).toFloat()
        b2 = b0
        a1 = (-2.0 * cw / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    /** 简单的 Schroeder 混响：四路带阻尼的梳状滤波并联，再串两级全通 */
    private fun reverbSample(input: Float): Float {
        var out = 0f
        for (j in combs.indices) {
            val buf = combs[j]
            val p = combPos[j]
            val y = buf[p]
            combStore[j] = y * (1f - COMB_DAMP) + combStore[j] * COMB_DAMP
            buf[p] = input * 0.25f + combStore[j] * COMB_FEEDBACK
            combPos[j] = if (p + 1 == buf.size) 0 else p + 1
            out += y
        }
        for (j in allpasses.indices) {
            val buf = allpasses[j]
            val p = allpassPos[j]
            val delayed = buf[p]
            buf[p] = out + delayed * ALLPASS_GAIN
            out = delayed - out * ALLPASS_GAIN
            allpassPos[j] = if (p + 1 == buf.size) 0 else p + 1
        }
        return out
    }

    /** 不加滤波时的截止频率（等于不处理） */
    private const val OPEN_CUTOFF = 20000f
    private const val WATER_CUTOFF = 450f
    private const val INDOOR_CUTOFF = 5000f
    private const val INDOOR_WET = 0.3f
    private const val CAVE_WET = 0.5f
    private const val SMOOTH_MS = 350f

    private const val COMB_FEEDBACK = 0.8f
    private const val COMB_DAMP = 0.3f
    private const val ALLPASS_GAIN = 0.6f
}
