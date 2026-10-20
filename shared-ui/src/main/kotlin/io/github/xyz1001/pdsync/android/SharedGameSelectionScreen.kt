package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.LocalContentColor
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Measures the real production layout without a second test-only rendering implementation. */
internal interface SharedGameSelectionLayoutObserver {
    fun element(name: String, bounds: Rect) {}
    fun text(name: String, lineCount: Int, overflow: Boolean) {}
}
internal val LocalSharedGameSelectionLayoutObserver = staticCompositionLocalOf<SharedGameSelectionLayoutObserver?> { null }

/** One Step 1 layout. Hosts supply real identities, platform actions and prerequisite checks. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SharedGameSelectionScreen(state: SharedGameSelectionState, actions: SharedGameSelectionActions) {
    SharedConnectionTheme {
        val currentState by rememberUpdatedState(state)
        val currentActions by rememberUpdatedState(actions)
        val interactions = remember { SharedGameSelectionInteractions({ currentState }, { currentActions }) }
        DisposableEffect(interactions) { onDispose { interactions.dispose() } }
        var pickerOpen by remember { mutableStateOf(false) }
        val pickerControls = setOf(SharedGameSelectionControl.CHOOSE_FILE, SharedGameSelectionControl.CHOOSE_INSTALLED)
        val recoveryControls = setOf(SharedGameSelectionControl.OPEN_SYSTEM_CONFIRMATION,
            SharedGameSelectionControl.CANCEL_INSTALLATION, SharedGameSelectionControl.CHECK_EXPIRED_REQUEST,
            SharedGameSelectionControl.CHECK_EXTERNAL_INSTALL)
        val recoveryRows = state.rows.filter { it.control in recoveryControls }
        val working = state.busy && state.access?.status != SharedGameAccessStatus.FAILED
        val observer = LocalSharedGameSelectionLayoutObserver.current
        fun measured(name: String) = Modifier.onGloballyPositioned { observer?.element(name, it.boundsInRoot()) }
        SharedAppShell(title = SharedMessages.CHOOSE_GAME.text(), eyebrow = SharedMessages.STEP_ONE.text(), onboardingStep = 1, footer = {
            if (recoveryRows.isEmpty()) {
                Spacer(Modifier.weight(1f))
                SharedActionButton(SharedMessages.NEXT_CONNECT.text(), interactions::next, measured("next"), enabled = state.nextEnabled)
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Pending requests can disable the ordinary controls. Keep their escape routes
                // outside the scrolling body, including when a notice or game name is very long.
                FlowRow(Modifier.fillMaxWidth().then(measured("installation-recovery")),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    recoveryRows.forEach { row ->
                        SharedActionButton(row.label, { interactions.perform(row.control) },
                            measured("action:${row.control.name}"), enabled = state.actionEnabled(row.control),
                            style = if (row.control == SharedGameSelectionControl.CANCEL_INSTALLATION)
                                SharedButtonStyle.SECONDARY else SharedButtonStyle.PRIMARY)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SharedActionButton(SharedMessages.NEXT_CONNECT.text(), interactions::next, measured("next"), enabled = state.nextEnabled)
                }
            }
        }) {
            Surface(Modifier.fillMaxWidth().then(measured("identity")), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, SharedUiColors.line), elevation = 1.dp) {
                BoxWithConstraints(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    val compact = maxWidth <= 400.dp
                    val identity: @Composable () -> Unit = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(Modifier.size(52.dp), color = Color(0xFFDFF6FB), shape = RoundedCornerShape(12.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (state.game?.icon != null) {
                                        Image(state.game.icon, contentDescription = null,
                                            modifier = Modifier.size(44.dp).then(measured("game-icon")),
                                            contentScale = ContentScale.Fit)
                                    } else CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.primary) {
                                        SharedReferenceIcon("game", Modifier.size(20.dp).then(measured("game-icon-fallback")))
                                    }
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(state.game?.name ?: SharedMessages.NO_GAME_SELECTED.text(), fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp, lineHeight = 24.sp,
                                    modifier = measured("game-name"), onTextLayout = {
                                        observer?.text("game-name", it.lineCount, it.hasVisualOverflow)
                                    })
                                state.game?.version?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = SharedUiColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                    val pickers: @Composable () -> Unit = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.rows.filter { it.control in pickerControls }.forEach { row ->
                                SharedActionButton(
                                    if (row.control == SharedGameSelectionControl.CHOOSE_FILE && state.candidates.isNotEmpty()) SharedMessages.CHANGE_GAME_ELLIPSIS.text()
                                    else if (state.access != null && row.control == SharedGameSelectionControl.CHOOSE_FILE) SharedMessages.CHOOSE_APK.text() else row.label,
                                    { if (state.candidates.isNotEmpty()) pickerOpen = true else interactions.perform(row.control) },
                                    measured("action:${row.control.name}"), enabled = state.actionEnabled(row.control),
                                    style = if (state.access != null && row.control == SharedGameSelectionControl.CHOOSE_FILE)
                                        SharedButtonStyle.TEXT else SharedButtonStyle.SECONDARY)
                            }
                        }
                    }
                    if (compact) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        identity()
                        pickers()
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) { identity() }
                        pickers()
                    }
                }
            }
            if (pickerOpen) SharedGamePickerDialog(
                candidates = state.candidates, selectedId = state.selectionId, enabled = state.canInteract,
                onChoose = { interactions.chooseCandidate(it); pickerOpen = false },
                onDismiss = { pickerOpen = false },
                fileLabel = state.rows.singleOrNull { it.control == SharedGameSelectionControl.CHOOSE_FILE }?.label,
                onChooseFile = if (state.actionEnabled(SharedGameSelectionControl.CHOOSE_FILE)) {
                    { pickerOpen = false; interactions.perform(SharedGameSelectionControl.CHOOSE_FILE) }
                } else null,
            )
            val accessControls = if (state.access != null) setOf(
                SharedGameSelectionControl.CHOOSE_ROOT, SharedGameSelectionControl.SWITCH_TO_EMBEDDED,
                // Older host observations must not resurrect the removed duplicate install action.
                SharedGameSelectionControl.CONTINUE_INSTALLATION,
                SharedGameSelectionControl.CANCEL_ACCESS_SELECTION,
            ) else emptySet()
            state.access?.let { access ->
                Spacer(Modifier.height(20.dp))
                Text(SharedMessages.SAVE_ACCESS.text(), fontSize = 15.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(SharedMessages.ANDROID_SANDBOX.text(),
                    color = SharedUiColors.label, fontSize = 13.sp, lineHeight = 19.5.sp)
                Spacer(Modifier.height(14.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val root: @Composable (Modifier) -> Unit = { modifier ->
                        AccessModeCard(SharedMessages.ROOT_MODE.text(), SharedMessages.ROOT_DESCRIPTION.text(),
                            SharedGameAccessMode.ROOT, access, state.actionEnabled(SharedGameSelectionControl.CHOOSE_ROOT),
                            { interactions.perform(SharedGameSelectionControl.CHOOSE_ROOT) },
                            modifier.then(measured("action:CHOOSE_ROOT")))
                    }
                    val apk: @Composable (Modifier) -> Unit = { modifier ->
                        AccessModeCard(SharedMessages.MODIFIED_APK_MODE.text(), SharedMessages.APK_DESCRIPTION.text(),
                            SharedGameAccessMode.APK, access, state.actionEnabled(SharedGameSelectionControl.SWITCH_TO_EMBEDDED),
                            { interactions.perform(SharedGameSelectionControl.SWITCH_TO_EMBEDDED) },
                            modifier.then(measured("action:SWITCH_TO_EMBEDDED")))
                    }
                    if (maxWidth <= 540.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        root(Modifier.fillMaxWidth()); apk(Modifier.fillMaxWidth())
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        root(Modifier.weight(1f)); apk(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            // Access-mode selection intentionally has no bottom notices, including stale host observations.
            // Desktop discovery has no access-mode cards and keeps its existing diagnostics.
            if (state.access == null && state.warning.isNotBlank()) {
                SelectionNotice(state.warning, error = true, modifier = measured("warning"))
                Spacer(Modifier.height(12.dp))
            }
            if (state.access == null && state.notice.isNotBlank()) {
                SelectionNotice(state.notice, error = state.noticeError, working = working && !state.noticeError,
                    modifier = measured("notice"))
                Spacer(Modifier.height(12.dp))
            }
            state.access?.let {
                state.rows.singleOrNull { it.control == SharedGameSelectionControl.CANCEL_ACCESS_SELECTION }?.let { row ->
                    SharedActionButton(row.label, { interactions.perform(row.control) }, measured("action:${row.control.name}"),
                        enabled = state.actionEnabled(row.control), style = SharedButtonStyle.TEXT)
                }
            }
            state.rows.filterNot { it.control in pickerControls || it.control in accessControls ||
                it.control in recoveryControls || it.control == SharedGameSelectionControl.USE_OTHER_INSTALLER }.forEach { row ->
                Spacer(Modifier.height(8.dp))
                SharedActionButton(row.label, { interactions.perform(row.control) },
                    Modifier.fillMaxWidth().then(measured("action:${row.control.name}")),
                    enabled = state.actionEnabled(row.control), style = SharedButtonStyle.SECONDARY)
            }
            if (working) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

        }
    }
}

@Composable
private fun AccessModeCard(title: String, description: String, mode: SharedGameAccessMode,
    access: SharedGameAccessState, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val selected = access.mode == mode
    val foreground = if (selected) Color(0xFF001F25) else SharedUiColors.text
    Surface(modifier.semantics { this.selected = selected }.clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp), color = if (selected) Color(0xFFDFF6FB) else Color.White,
        contentColor = foreground, border = BorderStroke(2.dp, if (selected) MaterialTheme.colors.primary else SharedUiColors.line)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                if (selected) {
                    val ready = access.status == SharedGameAccessStatus.READY
                    val checking = mode == SharedGameAccessMode.ROOT && access.status == SharedGameAccessStatus.WORKING
                    val label = if (ready) SharedMessages.PASSED.text() else if (checking) SharedMessages.CHECKING.text() else if (mode == SharedGameAccessMode.APK) SharedMessages.AWAITING_COMPONENT.text() else SharedMessages.AWAITING_PERMISSION.text()
                    Surface(shape = RoundedCornerShape(50), color = if (ready) Color(0xFFC4EED5)
                        else if (mode == SharedGameAccessMode.APK) Color(0xFFFFDEA9) else SharedUiColors.secondary,
                        contentColor = if (ready) Color(0xFF002111) else Color(0xFF2B1700)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ready) { SharedReferenceIcon("check", Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
                            Text(label, fontSize = 11.sp, lineHeight = 16.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text(description, fontSize = 12.sp, lineHeight = 18.sp, color = foreground.copy(alpha = .85f))
        }
    }
}

@Composable
private fun SelectionNotice(label: String, modifier: Modifier = Modifier, error: Boolean = false, working: Boolean = false) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = if (error) Color(0xFFFFDAD6) else SharedUiColors.secondary,
        contentColor = if (error) Color(0xFF410002) else SharedUiColors.text,
        border = BorderStroke(1.dp, if (error) Color(0xFFBA1A1A).copy(alpha = .2f) else SharedUiColors.fieldBorder)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SharedReferenceIcon(if (working) "sync" else "warning", Modifier.size(16.dp))
            Text(label, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.5.sp)
        }
    }
}
