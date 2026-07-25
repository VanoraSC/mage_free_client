package magefree.designsystem.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure window width size-class logic: the [windowWidthClassFor] thresholds and the
 * [WindowWidthClass] chrome helpers. The Compose/runtime path ([windowWidthClass]) is exercised via
 * the shell's instrumented tests.
 */
class WindowSizeTest {
    @Test
    fun `widths below the medium breakpoint are compact`() {
        assertEquals(WindowWidthClass.Compact, windowWidthClassFor(0.dp))
        assertEquals(WindowWidthClass.Compact, windowWidthClassFor(360.dp))
        assertEquals(WindowWidthClass.Compact, windowWidthClassFor(599.dp))
    }

    @Test
    fun `the medium breakpoint is inclusive of medium`() {
        assertEquals(WindowWidthClass.Medium, windowWidthClassFor(WindowWidthBreakpoints.Medium))
        assertEquals(WindowWidthClass.Medium, windowWidthClassFor(600.dp))
        assertEquals(WindowWidthClass.Medium, windowWidthClassFor(839.dp))
    }

    @Test
    fun `the expanded breakpoint is inclusive of expanded`() {
        assertEquals(WindowWidthClass.Expanded, windowWidthClassFor(WindowWidthBreakpoints.Expanded))
        assertEquals(WindowWidthClass.Expanded, windowWidthClassFor(840.dp))
        assertEquals(WindowWidthClass.Expanded, windowWidthClassFor(1280.dp))
    }

    @Test
    fun `compact uses the bottom bar, not the rail`() {
        assertTrue(WindowWidthClass.Compact.usesBottomBar)
        assertFalse(WindowWidthClass.Compact.usesNavigationRail)
    }

    @Test
    fun `medium and expanded use the rail, not the bottom bar`() {
        assertTrue(WindowWidthClass.Medium.usesNavigationRail)
        assertFalse(WindowWidthClass.Medium.usesBottomBar)
        assertTrue(WindowWidthClass.Expanded.usesNavigationRail)
        assertFalse(WindowWidthClass.Expanded.usesBottomBar)
    }
}
