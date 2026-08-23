package magefree.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import magefree.designsystem.theme.MageTheme

/**
 * The single Activity hosting the Compose UI. Edge-to-edge is enabled here (consistent with the
 * later immersive game mode) and the content is the [AppRoot] placeholder wrapped in [MageTheme].
 *
 * Story 0081 removed this Activity's `@AndroidEntryPoint`: nothing is injected into the Activity
 * itself, and Koin needs no per-Activity marker — Composables resolve from the container started in
 * [MageApp].
 */
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
