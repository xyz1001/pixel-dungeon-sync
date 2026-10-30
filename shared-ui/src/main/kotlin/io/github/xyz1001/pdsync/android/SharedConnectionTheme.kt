package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalMinimumTouchTargetEnforcement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors and dimensions from the immutable v21-M3 HTML reference, shared by both platform hosts. */
internal object SharedUiColors {
    val background = Color(0xFFF8FAFC)
    val text = Color(0xFF0F172A)
    val muted = Color(0xFF64748B)
    val line = Color(0xFFE2E8F0)
    val label = Color(0xFF334155)
    val fieldBorder = Color(0xFFCBD5E1)
    val secondary = Color(0xFFF1F5F9)
    val errorBackground = Color(0xFFFFF1F2)
}

internal fun sharedConnectionColors() = lightColors(
    primary = Color(0xFF006877),
    secondary = SharedUiColors.secondary,
    background = SharedUiColors.background,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color(0xFF006877),
    onBackground = SharedUiColors.text,
    onSurface = SharedUiColors.text,
    error = Color(0xFFBA1A1A),
)

internal val sharedConnectionShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(24.dp),
)

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun SharedConnectionTheme(content: @Composable () -> Unit) {
    // Match explicit reference control heights; foundation still expands hit testing as needed.
    // Both supported UI languages are LTR. An unsupported RTL system language falls back
    // to English layout as well, keeping Latin paths, counts and action order readable.
    CompositionLocalProvider(LocalMinimumTouchTargetEnforcement provides false,
        LocalLayoutDirection provides LayoutDirection.Ltr) {
    MaterialTheme(
        colors = sharedConnectionColors(),
        shapes = sharedConnectionShapes,
        typography = Typography(
            body1 = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            // Keep fallback-font ascenders inside the declared line box at large font scales.
            body2 = TextStyle(fontSize = 14.sp, lineHeight = 22.4.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None)),
            caption = TextStyle(fontSize = 12.sp, lineHeight = 19.2.sp),
            h5 = TextStyle(fontSize = 24.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            button = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
    }
}
