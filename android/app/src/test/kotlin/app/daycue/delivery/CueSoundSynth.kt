package app.daycue.delivery

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generates DayCue's original cue sounds (res/raw/cue_*.wav): simple synthesized sine / bell / pluck
 * patterns, no third-party audio. Deterministic (StrictMath, fixed-seed noise), so [CueSoundAssetsTest]
 * can verify the committed files byte-for-byte and regenerate them with `DAYCUE_REGEN_SOUNDS=1`.
 *
 * Format: 16-bit PCM mono WAV, 22 050 Hz.
 */
object CueSoundSynth {
    const val RATE = 22_050

    private fun buf(seconds: Double) = DoubleArray((seconds * RATE).toInt())
    private fun sin(x: Double) = StrictMath.sin(x)
    private fun exp(x: Double) = StrictMath.exp(x)
    private const val TAU = 2 * Math.PI

    /** Adds a bell-like partial stack at [start] seconds. */
    private fun bell(out: DoubleArray, start: Double, freq: Double, amp: Double, decay: Double, partials: List<Pair<Double, Double>>) {
        val s0 = (start * RATE).toInt()
        for (i in s0 until out.size) {
            val t = (i - s0).toDouble() / RATE
            val attack = (t / 0.004).coerceAtMost(1.0)
            var v = 0.0
            for ((ratio, a) in partials) v += a * sin(TAU * freq * ratio * t) * exp(-t * decay * ratio)
            out[i] += amp * attack * v
        }
    }

    /** Karplus-Strong plucked string (fixed-seed noise burst). */
    private fun pluck(out: DoubleArray, start: Double, freq: Double, amp: Double, damping: Double, seed: Long) {
        val n = (RATE / freq).toInt().coerceAtLeast(2)
        val rnd = java.util.Random(seed)
        val ring = DoubleArray(n) { rnd.nextDouble() * 2 - 1 }
        val s0 = (start * RATE).toInt()
        var idx = 0
        for (i in s0 until out.size) {
            val next = (idx + 1) % n
            val v = ring[idx]
            ring[idx] = damping * 0.5 * (ring[idx] + ring[next])
            out[i] += amp * v
            idx = next
        }
    }

    private fun softBell() = buf(1.4).also { bell(it, 0.0, 880.0, 0.5, 3.0, listOf(1.0 to 1.0, 2.76 to 0.35, 5.4 to 0.12)) }
    private fun woodTap() = buf(0.35).also {
        bell(it, 0.0, 1150.0, 0.55, 28.0, listOf(1.0 to 1.0, 1.6 to 0.4))
        bell(it, 0.11, 900.0, 0.35, 32.0, listOf(1.0 to 1.0, 1.6 to 0.3))
    }
    private fun twoNoteRise() = buf(0.9).also {
        pluck(it, 0.0, 659.25, 0.45, 0.996, 11)
        pluck(it, 0.22, 880.0, 0.45, 0.996, 12)
    }
    private fun pop() = buf(0.25).also { out ->
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val f = 420.0 + 900.0 * (1 - exp(-t * 40))
            phase += TAU * f / RATE
            out[i] += 0.6 * sin(phase) * exp(-t * 22) * (t / 0.003).coerceAtMost(1.0)
        }
    }
    private fun brightChime() = buf(1.3).also {
        listOf(1046.5, 1318.5, 1568.0).forEachIndexed { k, f -> bell(it, k * 0.12, f, 0.32, 3.5, listOf(1.0 to 1.0, 3.0 to 0.18)) }
    }
    private fun lowMarimba() = buf(1.0).also {
        bell(it, 0.0, 329.63, 0.5, 6.0, listOf(1.0 to 1.0, 4.0 to 0.25, 9.2 to 0.05))
        bell(it, 0.32, 440.0, 0.5, 6.0, listOf(1.0 to 1.0, 4.0 to 0.25, 9.2 to 0.05))
    }
    private fun droplet() = buf(0.3).also { out ->
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val f = 1500.0 - 750.0 * (1 - exp(-t * 30))
            phase += TAU * f / RATE
            out[i] += 0.4 * sin(phase) * exp(-t * 16) * (t / 0.002).coerceAtMost(1.0)
        }
    }
    /** 2.4 s loop: gentle rising arpeggio (C5 E5 G5 C6) + soft pad, silent tail so looping is seamless. */
    private fun alarmMorning() = buf(2.4).also {
        listOf(523.25, 659.25, 783.99, 1046.5).forEachIndexed { k, f -> bell(it, k * 0.25, f, 0.38, 2.6, listOf(1.0 to 1.0, 2.0 to 0.2, 3.0 to 0.08)) }
        val fadeStart = (2.1 * RATE).toInt()
        for (i in fadeStart until it.size) it[i] *= 1.0 - (i - fadeStart).toDouble() / (it.size - fadeStart)
    }

    private fun DoubleArray.toPcm(): ShortArray {
        val peak = maxOf(1e-9, maxOf { kotlin.math.abs(it) })
        val gain = 0.85 / peak
        val fadeOut = (0.01 * RATE).toInt()
        return ShortArray(size) { i ->
            val tail = if (i >= size - fadeOut) (size - i).toDouble() / fadeOut else 1.0
            (this[i] * gain * tail * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun Map<String, DoubleArray>.pcm() = mapValues { it.value.toPcm() }

    fun wav(samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        out.write(h.array()); out.write(data)
        return out.toByteArray()
    }

    /** All files as WAV bytes. */
    fun files(): Map<String, ByteArray> = linkedMapOf<String, DoubleArray>().apply {
        put("cue_soft_bell", softBell()); put("cue_wood_tap", woodTap()); put("cue_two_note_rise", twoNoteRise()); put("cue_pop", pop())
        put("cue_bright_chime", brightChime()); put("cue_low_marimba", lowMarimba()); put("cue_droplet", droplet()); put("cue_alarm_morning", alarmMorning())
    }.pcm().mapValues { wav(it.value) }
}
