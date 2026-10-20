package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pdsync.NoviceCopy

/** One physical Step 2 screen. Adapters supply actual paths, previews and platform capabilities. */
@Composable
fun SharedSavePathScreen(
    state: SharedSavePathState,
    actions: SharedSavePathActions,
    game: SharedGameIdentity? = null,
    setup: Boolean = false,
) {
    SharedConnectionTheme {
        SharedSavePathContent(state, actions, game, setup)
    }
}

@Composable
private fun SharedSavePathContent(
    state: SharedSavePathState,
    actions: SharedSavePathActions,
    game: SharedGameIdentity?,
    setup: Boolean,
) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val interactions = remember { SharedSavePathInteractions({ currentState }, { currentActions }) }
    var detailsExpanded by remember(state.path) { mutableStateOf(false) }
    val notice = NoviceCopy.feedback(state.notice, SharedMessages.READ_FAILED.text())
    val details = state.details.ifBlank { state.notice.takeIf { it != notice }.orEmpty() }
    SharedAppShell(
        title = SharedMessages.SAVE_LOCATION.text(), game = game, onboardingStep = if (setup) 2 else null,
        footer = {
            SharedActionButton(SharedMessages.BACK_STEP.text(), { interactions.perform(SharedSavePathControl.BACK) },
                enabled = state.enables(SharedSavePathControl.BACK), style = SharedButtonStyle.TEXT)
            Spacer(Modifier.weight(1f))
            SharedActionButton(state.primaryLabel, { interactions.perform(SharedSavePathControl.NEXT) },
                enabled = state.enables(SharedSavePathControl.NEXT))
        },
    ) {
        SharedForm {
            Text(SharedMessages.SAVE_PATH.text(), color = SharedUiColors.label, style = MaterialTheme.typography.body2)
            Spacer(Modifier.height(8.dp))
            Text(state.path, Modifier.fillMaxWidth(), softWrap = true,
                style = MaterialTheme.typography.body2)
            Spacer(Modifier.height(12.dp))
            val summary = savePathSummary(state.summary)
            if (summary != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary.headline)
                    summary.emptyMessage?.let { Text(it, color = SharedUiColors.muted) }
                    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        summary.slots.forEach { slot ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(slot.name, Modifier.weight(1f), color = SharedUiColors.muted,
                                    style = MaterialTheme.typography.body2)
                                Text(slot.time, color = SharedUiColors.muted,
                                    style = MaterialTheme.typography.body2, softWrap = false)
                            }
                        }
                    }
                }
            } else if (state.notice.isBlank()) {
                Text(when (state.readStatus) {
                    SharedSavePathReadStatus.READING -> SharedMessages.CHECKING_SAVES.text()
                    SharedSavePathReadStatus.FAILED -> SharedMessages.READ_FAILED.text()
                    SharedSavePathReadStatus.PREPARE_REQUIRED -> SharedMessages.DIRECTORY_NEXT.text()
                    else -> SharedMessages.CHECK_AFTER_PATH.text()
                }, color = SharedUiColors.muted)
            }
            if (state.notice.isNotBlank()) {
                Text(notice, color = if (state.readStatus == SharedSavePathReadStatus.FAILED)
                    MaterialTheme.colors.error else SharedUiColors.muted)
            }
            if (state.shows(SharedSavePathControl.INITIALIZE_GAME)) {
                Spacer(Modifier.height(12.dp))
                SharedActionButton(SharedMessages.LAUNCH_ONCE.text(), { interactions.perform(SharedSavePathControl.INITIALIZE_GAME) },
                    enabled = state.enables(SharedSavePathControl.INITIALIZE_GAME), style = SharedButtonStyle.SECONDARY)
            }
            if (details.isNotBlank()) {
                SharedActionButton(if (detailsExpanded) SharedMessages.HIDE_DETAILS.text() else SharedMessages.DETAILS.text(),
                    { detailsExpanded = !detailsExpanded }, style = SharedButtonStyle.TEXT)
                if (detailsExpanded) {
                    Text(details, style = MaterialTheme.typography.caption, color = SharedUiColors.muted)
                    if (state.shows(SharedSavePathControl.COPY_DIAGNOSTICS)) {
                        SharedActionButton(SharedMessages.COPY_DIAGNOSTICS.text(), { interactions.perform(SharedSavePathControl.COPY_DIAGNOSTICS) },
                            enabled = state.enables(SharedSavePathControl.COPY_DIAGNOSTICS), style = SharedButtonStyle.TEXT)
                    }
                }
            }
            if (state.busy || state.readStatus == SharedSavePathReadStatus.READING) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}


/** Settings-only editor. The host keeps validation, chooser cancellation and persistence ownership. */
@Composable
fun SharedSavePathDialog(state: SharedSavePathState, actions: SharedSavePathActions) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val interactions = remember { SharedSavePathInteractions({ currentState }, { currentActions }) }
    SharedConnectionTheme {
        Dialog(onDismissRequest = { interactions.perform(SharedSavePathControl.BACK) },
            properties = DialogProperties(dismissOnBackPress = !state.busy,
                dismissOnClickOutside = !state.busy, usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.padding(horizontal = 19.dp, vertical = 24.dp)) {
                SharedSavePathDialogBody(state, actions,
                    Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight))
            }
        }
    }
}

/** The same editor body is used by real dialogs and offscreen interaction tests. */
@Composable
internal fun SharedSavePathDialogBody(
    state: SharedSavePathState,
    actions: SharedSavePathActions,
    modifier: Modifier = Modifier,
) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val interactions = remember { SharedSavePathInteractions({ currentState }, { currentActions }) }
    var detailsExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.editable && !state.busy) focusRequester.requestFocus() }
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, SharedUiColors.fieldBorder), elevation = 3.dp) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(SharedMessages.CHANGE_DIRECTORY.text(), fontSize = 19.sp, lineHeight = 28.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                SharedTextField(SharedMessages.LOCAL_SAVE_PATH.text(), state.path, interactions::editPath,
                    modifier = Modifier.focusRequester(focusRequester), enabled = state.editable && !state.busy, readOnly = !state.editable)
                if (state.shows(SharedSavePathControl.CHOOSE_DIRECTORY)) {
                    SharedActionButton(SharedMessages.CHOOSE_FOLDER.text(), { interactions.perform(SharedSavePathControl.CHOOSE_DIRECTORY) },
                        enabled = state.enables(SharedSavePathControl.CHOOSE_DIRECTORY), style = SharedButtonStyle.TEXT)
                }
                if (state.notice.isNotBlank()) {
                    Text(NoviceCopy.feedback(state.notice, SharedMessages.READ_FAILED.text()),
                        color = if (state.readStatus == SharedSavePathReadStatus.FAILED) MaterialTheme.colors.error else SharedUiColors.muted,
                        fontSize = 13.sp, lineHeight = 20.sp)
                }
                if (state.details.isNotBlank()) {
                    SharedActionButton(if (detailsExpanded) SharedMessages.HIDE_DETAILS.text() else SharedMessages.DETAILS.text(),
                        { detailsExpanded = !detailsExpanded }, style = SharedButtonStyle.TEXT)
                    if (detailsExpanded) Text(state.details, color = SharedUiColors.muted, fontSize = 12.sp)
                }
                if (state.busy || state.readStatus == SharedSavePathReadStatus.READING) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SharedActionButton(SharedMessages.CANCEL.text(), { interactions.perform(SharedSavePathControl.BACK) },
                    Modifier.weight(1f), enabled = state.enables(SharedSavePathControl.BACK), style = SharedButtonStyle.TEXT)
                SharedActionButton(if (state.continueAfterRead) SharedMessages.VALIDATING.text() else SharedMessages.SAVE_VALIDATE.text(),
                    { interactions.perform(SharedSavePathControl.NEXT) }, Modifier.weight(1.3f),
                    enabled = state.enables(SharedSavePathControl.NEXT))
            }
        }
    }
}
