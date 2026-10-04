package app.daycue.delivery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled cue sounds are exactly what [CueSoundSynth] produces (original, reproducible audio).
 * Regenerate: set `DAYCUE_REGEN_SOUNDS=1` and run `:app:testDebugUnitTest --tests *CueSoundAssetsTest*`.
 */
class CueSoundAssetsTest {
    private val rawDir = File("src/main/res/raw")

    @Test
    fun bundledSoundsMatchSynth() {
        val regen = System.getenv("DAYCUE_REGEN_SOUNDS") == "1"
        CueSoundSynth.files().forEach { (name, bytes) ->
            val f = File(rawDir, "$name.wav")
            if (regen) f.writeBytes(bytes)
            assertTrue("missing ${f.path}", f.isFile)
            assertArrayEquals("${f.name} differs from CueSoundSynth (regenerate with DAYCUE_REGEN_SOUNDS=1)", bytes, f.readBytes())
        }
    }

    @Test
    fun soundsAreShortAndDistinct() {
        val files = CueSoundSynth.files()
        files.forEach { (name, bytes) ->
            val seconds = (bytes.size - 44) / 2.0 / CueSoundSynth.RATE
            assertTrue("$name length $seconds s", seconds in 0.2..2.5)
        }
        assertTrue("all distinct", files.values.map { it.contentHashCode() }.toSet().size == files.size)
    }
}
