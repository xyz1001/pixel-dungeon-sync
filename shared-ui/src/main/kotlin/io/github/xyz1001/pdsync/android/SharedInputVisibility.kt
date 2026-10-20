package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

/** Opt-in host behavior. Desktop leaves this null and retains its existing layout/scrolling. */
val LocalSharedInputViewport = staticCompositionLocalOf<IntSize?> { null }

@Composable
internal fun Modifier.keepFocusedFieldVisible(focused: Boolean): Modifier {
    val viewport = LocalSharedInputViewport.current ?: return this
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused, viewport) {
        if (focused && viewport.width > 0 && viewport.height > 0) {
            // Focus can arrive before the keyboard finishes resizing the viewport. Request
            // again after each new measured size, against the settled scroll/layout bounds.
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    return bringIntoViewRequester(requester)
}
