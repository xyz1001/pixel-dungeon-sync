package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors and dimensions from ui-prototype/style.css, shared by both platform hosts. */
internal object SharedUiColors {
    val background = Color(0xFFEFF3EF)
    val text = Color(0xFF213B30)
    val muted = Color(0xFF65756C)
    val line = Color(0xFFDDE6DF)
    val label = Color(0xFF405748)
    val fieldBorder = Color(0xFFCBD7CD)
    val secondary = Color(0xFFE9EFEA)
    val errorBackground = Color(0xFFFFF0EB)
}

internal fun sharedConnectionColors() = lightColors(
    primary = Color(0xFF326650),
    secondary = SharedUiColors.secondary,
    background = SharedUiColors.background,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color(0xFF326650),
    onBackground = SharedUiColors.text,
    onSurface = SharedUiColors.text,
    error = Color(0xFF924832),
)

internal val sharedConnectionShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun SharedConnectionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = sharedConnectionColors(),
        shapes = sharedConnectionShapes,
        typography = Typography(
            body1 = TextStyle(fontSize = 16.sp, lineHeight = 25.6.sp),
            body2 = TextStyle(fontSize = 14.sp, lineHeight = 22.4.sp),
            caption = TextStyle(fontSize = 12.sp, lineHeight = 19.2.sp),
            h5 = TextStyle(fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight(650)),
            button = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}
