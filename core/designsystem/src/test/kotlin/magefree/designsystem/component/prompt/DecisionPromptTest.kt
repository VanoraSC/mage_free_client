package magefree.designsystem.component.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure logic of the decision prompt: the accessible-label resolution on
 * [DecisionPromptChoice] and the merged prompt-description builder. The composable itself is verified
 * via previews.
 */
class DecisionPromptTest {
    @Test
    fun `accessibleLabel falls back to label when no content description`() {
        val choice = DecisionPromptChoice(label = "Pass")
        assertEquals("Pass", choice.accessibleLabel)
    }

    @Test
    fun `accessibleLabel prefers explicit content description`() {
        val choice = DecisionPromptChoice(label = "Pass", contentDescription = "Pass priority")
        assertEquals("Pass priority", choice.accessibleLabel)
    }

    @Test
    fun `default emphasis is secondary`() {
        assertEquals(DecisionEmphasis.Secondary, DecisionPromptChoice(label = "OK").emphasis)
    }

    @Test
    fun `buildPromptDescription joins title and message`() {
        assertEquals(
            "Choose a target. Tap a creature to target it.",
            buildPromptDescription("Choose a target", "Tap a creature to target it."),
        )
    }

    @Test
    fun `buildPromptDescription is just the title when message is null`() {
        assertEquals("Choose a target", buildPromptDescription("Choose a target", null))
    }

    @Test
    fun `buildPromptDescription ignores a blank message`() {
        assertEquals("Choose a target", buildPromptDescription("Choose a target", "   "))
    }
}
