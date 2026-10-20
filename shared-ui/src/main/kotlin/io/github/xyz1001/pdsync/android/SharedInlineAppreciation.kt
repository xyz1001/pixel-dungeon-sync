package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Built-in-service appreciation inside Step 4. The platform owns a separate retained controller
 * and its lifecycle, so this read-only panel cannot commit or block the comparison decision.
 * Recomposition does not load again; opening an already loaded controller is also idempotent.
 */
@Composable
fun SharedInlineAppreciation(
    controller: SharedConnectionController,
    showAccount: Boolean = false,
    onSaveImage: (() -> Unit)? = null,
    imageSaveBusy: Boolean = false,
    imageSaveMessage: String = "",
) {
    LaunchedEffect(controller) { controller.openAppreciationOnly() }
    val state = controller.state
    SharedInlineAppreciationContent(state.appreciation, state.busy, controller::copyUsername,
        showAccount, onSaveImage, imageSaveBusy, imageSaveMessage, controller::refreshAppreciation)
}

@Composable
internal fun SharedInlineAppreciationContent(
    appreciation: SharedAppreciationState?,
    loading: Boolean,
    copyUsername: () -> Unit,
    showAccount: Boolean = false,
    onSaveImage: (() -> Unit)? = null,
    imageSaveBusy: Boolean = false,
    imageSaveMessage: String = "",
    refresh: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(SharedMessages.SUPPORT_RENEW_YEAR.text(), color = SharedUiColors.muted, style = MaterialTheme.typography.body2)
        if (appreciation != null) {
            SharedAppreciationContent(appreciation, copyUsername, onSaveImage, imageSaveBusy,
                imageSaveMessage, showAccount, imageHeight = 180.dp, refresh = refresh)
        } else if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(SharedMessages.SUPPORT_CODE_UNAVAILABLE.text(), color = SharedUiColors.muted, style = MaterialTheme.typography.body2)
        }
    }
}
