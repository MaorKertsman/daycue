package app.daycue

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.daycue.ui.home.PlaceholderScreen
import app.daycue.ui.theme.DayCueTheme

// AppCompatActivity (not ComponentActivity) so AppCompatDelegate.setApplicationLocales
// applies the per-app language on API 26-32.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DayCueTheme {
                PlaceholderScreen()
            }
        }
    }
}
