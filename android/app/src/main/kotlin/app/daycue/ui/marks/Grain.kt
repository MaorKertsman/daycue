package app.daycue.ui.marks

import android.graphics.Bitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ImageShader
import kotlin.random.Random

/**
 * Paper grain (VISUAL.md 4.3): one 96x96 px tile from a seeded Random(0xDA7C), generated once and cached.
 * Light: ink pixels with alpha 0..10/255, Multiply. Dark: white with alpha 0..8/255, Screen.
 */
internal object Grain {
    private const val TILE = 96
    private val lightBrush: ShaderBrush by lazy { brush(dark = false) }
    private val darkBrush: ShaderBrush by lazy { brush(dark = true) }

    private fun brush(dark: Boolean): ShaderBrush {
        val random = Random(0xDA7C)
        val maxAlpha = if (dark) 8 else 10
        val rgb = if (dark) 0xFFFFFF else 0x1F1D1A
        val pixels = IntArray(TILE * TILE) {
            val alpha = random.nextInt(maxAlpha + 1)
            (alpha shl 24) or rgb
        }
        val bitmap: ImageBitmap = Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
        return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }

    /** Draws grain clipped to [path]. Never call for text backgrounds. */
    fun DrawScope.drawGrain(path: Path, dark: Boolean) {
        clipPath(path) {
            drawRect(
                brush = if (dark) darkBrush else lightBrush,
                blendMode = if (dark) BlendMode.Screen else BlendMode.Multiply,
            )
        }
    }
}
