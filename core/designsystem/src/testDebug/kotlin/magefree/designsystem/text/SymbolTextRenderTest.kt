package magefree.designsystem.text

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import magefree.designsystem.theme.MageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Symbols drawn into the server's own sentence.
 *
 * The assertion that matters is that **the words survive**. A symbol is drawn in place of its token,
 * but the text a screen reader or a test sees still reads exactly what the server said — which is why
 * the inline placeholder carries the original token as its alternate text. Anything else would mean
 * every existing assertion about a prompt's wording quietly started matching nothing, and the app
 * would have lost the server's words to gain a picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class SymbolTextRenderTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(text: String) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) { SymbolText(text = text) }
            }
        }
    }

    @Test
    fun `a payment prompt still reads as the server wrote it`() {
        show("Pay {2}{R}")

        composeTestRule.onNodeWithText("Pay {2}{R}").assertIsDisplayed()
    }

    @Test
    fun `a rules line keeps its words around the symbols`() {
        show("{T}: Add {G}.")

        composeTestRule.onNodeWithText("{T}: Add {G}.").assertIsDisplayed()
    }

    @Test
    fun `text with no symbols is untouched`() {
        show("Choose target creature")

        composeTestRule.onNodeWithText("Choose target creature").assertIsDisplayed()
    }

    @Test
    fun `a symbol the font does not know still shows what the server sent`() {
        // The fallback that keeps a newer set legible rather than blank.
        show("Pay {WUBRG} somehow")

        composeTestRule.onNodeWithText("Pay {WUBRG} somehow").assertIsDisplayed()
    }
}
