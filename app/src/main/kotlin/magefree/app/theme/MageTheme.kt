package magefree.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Minimal app theme: [MaterialTheme] with the default Material 3 light/dark color schemes, chosen
 * by [isSystemInDarkTheme]. This is a deliberate placeholder — the real design system (tokens,
 * typography, components, dynamic color) arrives in EPIC-03. Keep it thin.
 */
@Composable
fun MageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
