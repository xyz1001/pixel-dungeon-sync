package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pdsync.i18n.SharedMessages

/** Shares the original bitmap; opening the viewer never fetches or modifies payment data. */
@Composable
internal fun SharedAppreciationImage(image: ImageBitmap, modifier: Modifier = Modifier) {
    var expanded by remember(image) { mutableStateOf(false) }
    Image(image, SharedMessages.SUPPORT_CODE.text(),
        modifier.testTag("appreciation-thumbnail").clickable { expanded = true },
        alignment = Alignment.Center, contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None)
    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(
            usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true,
        )) {
            // Full-window click target also covers the Fit image's letterboxing. No extra UI controls.
            Box(Modifier.fillMaxSize().testTag("appreciation-viewer")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    expanded = false
                }.padding(16.dp), contentAlignment = Alignment.Center) {
                Image(image, SharedMessages.SUPPORT_CODE.text(),
                    Modifier.fillMaxSize().testTag("appreciation-enlarged-image"),
                    alignment = Alignment.Center, contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None)
            }
        }
    }
}
