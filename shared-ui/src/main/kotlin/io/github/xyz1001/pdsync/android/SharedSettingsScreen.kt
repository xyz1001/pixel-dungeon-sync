package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Optional measurement of the production layout, used by offscreen render/pointer tests. */
internal interface SharedSettingsLayoutObserver {
    fun element(name: String, boundsInRoot: Rect) {}
    fun value(key: SharedSettingsKey, lineCount: Int, hasVisualOverflow: Boolean) {}
}
internal val LocalSharedSettingsLayoutObserver = staticCompositionLocalOf<SharedSettingsLayoutObserver?> { null }

/** The reference's bordered rows and fixed Return footer. This page performs no platform I/O. */
@Composable
fun SharedSettingsScreen(state: SharedSettingsState, actions: SharedSettingsActions) {
    SharedConnectionTheme {
        BoxWithConstraints {
            val narrow = maxWidth <= 600.dp
            val currentState by rememberUpdatedState(state)
            val currentActions by rememberUpdatedState(actions)
            val interactions = remember { SharedSettingsInteractions({ currentState }, { currentActions }) }
            DisposableEffect(interactions) { onDispose { interactions.dispose() } }
            val observer = LocalSharedSettingsLayoutObserver.current
            SharedAppShell(title = SharedMessages.SETTINGS_CENTER.text(), game = state.game, footer = {
                SharedActionButton(SharedMessages.BACK_HOME.text(), interactions::back, enabled = state.backEnabled,
                    style = SharedButtonStyle.TEXT, modifier = observedSettings("back", observer))
            }) {
                val referenceOrder = listOf(SharedSettingsKey.GAME, SharedSettingsKey.CLOUD_CONNECTION,
                    SharedSettingsKey.AUTOMATIC_UPLOAD, SharedSettingsKey.REVIEW_SETUP, SharedSettingsKey.SAVE_PATH)
                state.visibleRows.sortedBy { referenceOrder.indexOf(it.key).takeIf { n -> n >= 0 } ?: 100 }.forEach { supplied ->
                    val row = when (supplied.key) {
                        SharedSettingsKey.GAME -> supplied.copy(title = SharedMessages.GAME_MANAGEMENT.text(), actionLabel = supplied.actionLabel?.let { SharedMessages.CHANGE_GAME.text() })
                        SharedSettingsKey.CLOUD_CONNECTION -> supplied.copy(actionLabel = supplied.actionLabel?.let { SharedMessages.CONNECTION_SETTINGS.text() })
                        SharedSettingsKey.AUTOMATIC_UPLOAD -> automaticUploadSettingsRow(supplied)
                        SharedSettingsKey.REVIEW_SETUP -> supplied.copy(
                            currentValue = if (supplied.setupComplete) SharedMessages.REVIEW_SETUP.text() else supplied.currentValue,
                            actionLabel = supplied.actionLabel?.let { SharedMessages.VIEW_GUIDE.text() })
                        SharedSettingsKey.SAVE_PATH -> supplied.copy(title = if (supplied.hasAction) SharedMessages.SAVE_DIRECTORY.text() else SharedMessages.SAVE_PATH.text(), actionLabel = supplied.actionLabel?.let { SharedMessages.CHANGE_PATH.text() })
                        else -> supplied
                    }
                    key(row.key) {
                        SharedSettingsRowView(row, narrow, state.actionEnabled(row.key),
                            onAction = { interactions.perform(row.key) }, observer = observer)
                        Spacer(Modifier.height(14.dp))
                    }
                }
                if (state.notice.isNotBlank()) {
                    Text(state.notice, Modifier.fillMaxWidth().then(observedSettings("notice", observer)),
                        color = if (state.noticeError) MaterialTheme.colors.error else SharedUiColors.muted,
                        fontSize = 13.sp, lineHeight = 19.5.sp)
                }
            }
        }
    }
}

@Composable
private fun SharedSettingsRowView(row: SharedSettingsRow, narrow: Boolean, enabled: Boolean,
    onAction: () -> Unit, observer: SharedSettingsLayoutObserver?) {
    Surface(Modifier.fillMaxWidth().then(observedSettings("row:${row.key}", observer)),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SharedUiColors.line),
        color = MaterialTheme.colors.surface, elevation = 1.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            // Keep a real left value column even for long host labels at 320dp or enlarged text.
            val actionMaxWidth = maxWidth * .45f
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.title, Modifier.fillMaxWidth().then(observedSettings("title:${row.key}", observer)),
                        fontSize = 15.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.Bold)
                    if (row.currentValue.isNotBlank()) {
                        Text(row.currentValue,
                            Modifier.fillMaxWidth().padding(top = 2.dp)
                                .then(observedSettings("value:${row.key}", observer)),
                            color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 19.5.sp,
                            softWrap = true,
                            onTextLayout = { observer?.value(row.key, it.lineCount, it.hasVisualOverflow) })
                    }
                }
                if (row.hasAction) {
                    SharedActionButton(row.actionLabel!!, onAction, enabled = enabled,
                        style = SharedButtonStyle.SECONDARY,
                        modifier = Modifier.widthIn(max = actionMaxWidth)
                            .then(observedSettings("action:${row.key}", observer)))
                }
            }
        }
    }
}

private fun observedSettings(name: String, observer: SharedSettingsLayoutObserver?) =
    Modifier.onGloballyPositioned { observer?.element(name, it.boundsInRoot()) }

/** Existing automatic-upload row: keep actions/availability, shorten only its pause labels. */
internal fun automaticUploadSettingsRow(supplied: SharedSettingsRow): SharedSettingsRow =
    supplied.copy(title = SharedMessages.AUTO_BACKUP.text(),
        currentValue = when (supplied.toggleEnabled) {
            false -> SharedMessages.AUTO_UPLOAD_PAUSED.text()
            true -> SharedMessages.UPLOAD_AFTER_EXIT.text()
            else -> supplied.currentValue
        }, actionLabel = if (supplied.actionLabel == null) null else when (supplied.toggleEnabled) {
            false -> SharedMessages.ENABLE_AUTO_BACKUP.text()
            true -> SharedMessages.PAUSE.text()
            else -> supplied.actionLabel
        })
