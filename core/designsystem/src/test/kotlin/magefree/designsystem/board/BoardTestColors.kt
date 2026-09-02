package magefree.designsystem.board

import androidx.compose.ui.graphics.Color

/** Reference colours with published L\* and contrast values, used to anchor the colour maths. */
internal object BoardTestColors {
    val BLACK = Color(0xFF000000)
    val WHITE = Color(0xFFFFFFFF)

    /** sRGB 128 grey, whose CIE L\* is the standard demonstration that the scale is not linear. */
    val MID_GREY = Color(0xFF808080)
}
