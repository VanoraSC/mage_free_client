package magefree.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import magefree.designsystem.theme.MageTheme

/**
 * The single Activity hosting the Compose UI. Edge-to-edge is enabled here (consistent with the
 * later immersive game mode) and the content is the [AppRoot] placeholder wrapped in [MageTheme].
 *
 * [AndroidEntryPoint] makes this Activity a Hilt injection target; nothing is injected yet, but it
 * exercises the generated DI plumbing.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MageTheme {
                AppRoot()
            }
        }
    }
}
