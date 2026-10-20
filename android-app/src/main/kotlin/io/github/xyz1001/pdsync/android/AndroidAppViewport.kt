package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/** Content owns all system-bar safe areas and the IME after the old toolbar is removed. */
@Composable
internal fun AndroidAppViewport(content: @Composable () -> Unit) {
    val diagnosticAction = (LocalActivity.current as? MainActivity)?.wizardDiagnosticAction()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize()
        .safeDrawingPadding().imePadding()
        .onSizeChanged { viewport = it }) {
        // The size is measured after inset padding, including IME animation and rotation.
        // Shared code sees only a bounded viewport, never Android insets or an Activity.
        CompositionLocalProvider(LocalSharedInputViewport provides viewport,
            LocalSharedDiagnosticAction provides diagnosticAction, content = content)
    }
}
