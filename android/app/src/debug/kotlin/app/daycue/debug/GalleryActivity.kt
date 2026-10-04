package app.daycue.debug

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.daycue.ui.theme.DayCueTheme

/**
 * Debug-only component gallery (docs/design/VISUAL.md, UX.md section 6). Exported so screenshots can be
 * driven from adb; see src/debug/AndroidManifest.xml for the extras.
 */
class GalleryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val page = GalleryPage.fromKey(intent.getStringExtra("page"))
        val chrome = intent.getStringExtra("chrome") != "false"
        val openSheet = intent.getStringExtra("sheet") == "true"
        val openDialog = intent.getStringExtra("dialog") == "true"
        val reduceMotion = intent.getStringExtra("reduce_motion") == "true"
        val scrollPx = intent.getStringExtra("scroll")?.let { if (it == "end") Int.MAX_VALUE else it.toIntOrNull() } ?: 0
        setContent {
            DayCueTheme(reduceMotionSetting = reduceMotion) {
                androidx.compose.runtime.CompositionLocalProvider(LocalInitialScroll provides scrollPx) {
                    GalleryApp(page, chrome, openSheet, openDialog)
                }
            }
        }
    }
}
