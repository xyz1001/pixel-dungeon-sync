package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The existing desktop palette and corner sizes, shared without a platform-specific override. */
internal fun sharedConnectionColors() = lightColors(
    primary = Color(0xFF326650),
    secondary = Color(0xFF8A6B42),
)

internal val sharedConnectionShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
)

@Composable
fun SharedConnectionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = sharedConnectionColors(),
        shapes = sharedConnectionShapes,
        typography = Typography(),
        content = content,
    )
}
