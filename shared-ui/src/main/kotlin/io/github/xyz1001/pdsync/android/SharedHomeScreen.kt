package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Optional production-layout observer for offscreen measurement and pointer verification. */
internal interface SharedHomeLayoutObserver {
    fun element(name: String, boundsInRoot: Rect) {}
}
internal val LocalSharedHomeLayoutObserver = staticCompositionLocalOf<SharedHomeLayoutObserver?> { null }

/** One physical home page for both platforms. This presentation performs no filesystem/network I/O. */
@Composable
fun SharedHomeScreen(state: SharedHomeState, actions: SharedHomeActions, naming: SharedHistoryNaming? = null) {
    SharedConnectionTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val loading = state.pageActive && state.archiveLoading
            val narrow = maxWidth <= 600.dp
            Box(if (loading) Modifier.clearAndSetSemantics {} else Modifier) {
                SharedHomeContent(state, actions, narrow = narrow, naming = naming)
            }
            if (loading) SharedHomeLoadingOverlay()
        }
    }
}

/** In-page modal: no extra window, delayed timer, dismiss button or unrelated busy state. */
@Composable
private fun SharedHomeLoadingOverlay() {
    val observer = LocalSharedHomeLayoutObserver.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .24f))
        .onGloballyPositioned { observer?.element("archive-loading-scrim", it.boundsInRoot()) }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }, contentAlignment = Alignment.Center) {
        Surface(Modifier.padding(24.dp)
            .onGloballyPositioned { observer?.element("archive-loading-panel", it.boundsInRoot()) },
            color = MaterialTheme.colors.surface, shape = RoundedCornerShape(24.dp), elevation = 8.dp) {
            Column(Modifier.padding(horizontal = 32.dp, vertical = 28.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colors.primary, strokeWidth = 3.dp)
                Text(SharedMessages.LOADING.text(), fontSize = 15.sp, lineHeight = 22.5.sp,
                    fontWeight = FontWeight.Medium, color = SharedUiColors.text)
            }
        }
    }
}

@Composable
private fun SharedHomeContent(state: SharedHomeState, actions: SharedHomeActions, narrow: Boolean, naming: SharedHistoryNaming?) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val interactions = remember { SharedHomeInteractions({ currentState }, { currentActions }) }
    val observer = LocalSharedHomeLayoutObserver.current
    fun observed(name: String) = Modifier.onGloballyPositioned { observer?.element(name, it.boundsInRoot()) }
    SharedAppShell(title = SharedMessages.PIXEL_DUNGEON.text(), eyebrow = SharedMessages.SAVE_SYNC.text(), game = state.game,
        headerActions = {
            SharedActionButton(SharedMessages.SETTINGS.text(), interactions::settings, enabled = state.canInteract,
                style = SharedButtonStyle.TEXT, icon = "settings", modifier = Modifier.padding(start = 20.dp).then(observed("settings")))
        }) {
        if (state.status.isNotBlank()) {
            // These tokens decorate host-provided status only; capabilities still come from state.
            val syncedStatus = state.statusKind == SharedHomeStatusKind.SYNCED
            val statusTokens = when {
                state.statusKind == SharedHomeStatusKind.EXPIRED -> listOf(0xFFFEF2F2, 0xFF991B1B, 0xFFFECACA)
                state.statusKind in setOf(SharedHomeStatusKind.WARNING, SharedHomeStatusKind.CONFLICT, SharedHomeStatusKind.RUNNING) -> listOf(0xFFFFF8E8, 0xFF855300, 0xFFFFE6B0)
                syncedStatus -> listOf(0xFFE6F6EE, 0xFF085A36, 0xFFBBF0D2)
                else -> listOf(0xFFF1F5F9, 0xFF0F172A, 0xFFE2E8F0)
            }
            Surface(Modifier.fillMaxWidth().then(observed("status")), color = Color(statusTokens[0]),
                contentColor = Color(statusTokens[1]), border = BorderStroke(1.dp, Color(statusTokens[2])),
                shape = RoundedCornerShape(16.dp)) {
                val contentPadding = Modifier.padding(1.dp).padding(horizontal = 18.dp, vertical = 14.dp)
                val icon = if (syncedStatus) "check" else if (state.statusKind in setOf(SharedHomeStatusKind.WARNING, SharedHomeStatusKind.CONFLICT, SharedHomeStatusKind.EXPIRED)) "warning" else if (state.statusKind == SharedHomeStatusKind.RUNNING) "play" else "cloud"
                val label: @Composable (Modifier) -> Unit = { modifier ->
                    Text(state.status, modifier.then(if (state.showAppreciation) observed("expiry-status-text") else Modifier),
                        fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                }
                val appreciation: @Composable (Modifier) -> Unit = { modifier ->
                    SharedActionButton(SharedMessages.SUPPORT.text(), interactions::appreciate,
                        enabled = state.appreciationEnabled, style = SharedButtonStyle.TEXT,
                        modifier = modifier.then(observed("appreciate")))
                }
                // Let the same action wrap below the status at narrow large-text sizes.
                if (state.showAppreciation && narrow && LocalDensity.current.fontScale >= 1.5f) {
                    Column(contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SharedReferenceIcon(icon)
                            label(Modifier.weight(1f))
                        }
                        appreciation(Modifier.align(Alignment.End))
                    }
                } else Row(contentPadding,
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SharedReferenceIcon(icon)
                    label(if (state.showAppreciation) Modifier.weight(1f) else Modifier)
                    if (state.showAppreciation) appreciation(Modifier)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val uploadPrimary = state.uploadEnabled && !state.restoreEnabled
        val upload: @Composable (Modifier) -> Unit = { actionModifier ->
            SharedHomeActionCard(if (state.pending == SharedHomeOperation.UPLOAD) SharedMessages.UPLOADING.text() else SharedMessages.UPLOAD_CLOUD.text(),
                SharedMessages.UPLOAD_DESCRIPTION.text(), interactions::upload, state.uploadEnabled,
                uploadPrimary, actionModifier.then(observed("upload")), icon = "upload",
                pending = state.pending == SharedHomeOperation.UPLOAD, badge = if (uploadPrimary) SharedMessages.RECOMMENDED.text() else null)
        }
        val restore: @Composable (Modifier) -> Unit = { actionModifier ->
            SharedHomeActionCard(if (state.pending == SharedHomeOperation.RESTORE) SharedMessages.RESTORING.text() else SharedMessages.RESTORE_LOCAL.text(),
                SharedMessages.RESTORE_DESCRIPTION.text(), interactions::restore, state.restoreEnabled,
                state.restoreEnabled, actionModifier.then(observed("restore")), icon = "download",
                pending = state.pending == SharedHomeOperation.RESTORE, badge = if (state.restoreEnabled) {
                    if (state.statusKind == SharedHomeStatusKind.CONFLICT) SharedMessages.RESOLVE_CONFLICT.text() else SharedMessages.RECOMMENDED.text()
                } else null)
        }
        if (narrow) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            upload(Modifier.fillMaxWidth()); restore(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            upload(Modifier.weight(1f)); restore(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 0.dp), horizontalArrangement = Arrangement.End) {
            SharedActionButton(if (state.pending == SharedHomeOperation.LAUNCH) SharedMessages.LAUNCHING.text() else SharedMessages.LAUNCH_GAME.text(),
                interactions::launch, enabled = state.launchEnabled, style = SharedButtonStyle.TEXT,
                icon = if (state.pending == SharedHomeOperation.LAUNCH) null else "play",
                modifier = observed("launch"))
        }
        // The graph carries target selection. Keep actionable failures visible without the
        // redundant default/selected-target explanation card or its reserved spacing.
        state.restoreTarget?.takeIf { it.noticeError && it.notice.isNotBlank() }?.let { target ->
            Text(target.notice, Modifier.padding(bottom = 12.dp).then(observed("target-error")),
                color = MaterialTheme.colors.error, fontSize = 12.sp, lineHeight = 19.2.sp)
        }
        val notice = state.visibleNotice
        if (notice.isNotBlank()) Text(notice, Modifier.padding(bottom = 12.dp).then(observed("notice")),
            color = if (state.noticeError) MaterialTheme.colors.error else SharedUiColors.muted,
            fontSize = 14.sp, lineHeight = 22.4.sp)
        SharedHistorySection(state.history, state.selectedSnapshotId, !state.canInteract,
            interactions::selectSnapshot, interactions::rename, interactions::changeHistoryPage,
            modifier = observed("history"), naming = naming, loading = state.archiveLoading,
            onRefresh = interactions::refreshHistory, refreshEnabled = state.refreshHistoryEnabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedHomeActionCard(title: String, description: String, onClick: () -> Unit,
    enabled: Boolean, primary: Boolean, modifier: Modifier, icon: String, pending: Boolean, badge: String?) {
    val background = if (primary) MaterialTheme.colors.primary else SharedUiColors.background
    val foreground = if (primary) Color.White else SharedUiColors.text
    Button(onClick, modifier.heightIn(min = 90.dp), enabled = enabled,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(if (primary) 2.dp else 1.5.dp, if (primary) Color(0xFF005461) else SharedUiColors.fieldBorder),
        elevation = ButtonDefaults.elevation(defaultElevation = if (primary) 4.dp else 1.dp, pressedElevation = 2.dp, disabledElevation = 0.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = background, contentColor = foreground,
            disabledBackgroundColor = Color(0xFFF9FAFC),
            disabledContentColor = SharedUiColors.muted.copy(alpha = .4f))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pending) CircularProgressIndicator(Modifier.size(16.dp), color = foreground, strokeWidth = 2.dp)
                    else SharedReferenceIcon(icon)
                    Text(title, fontSize = 15.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.Bold)
                }
                badge?.let { Text(it, Modifier.background(Color.White, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = MaterialTheme.colors.primary, fontSize = 11.sp, lineHeight = 16.5.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
            }
            Text(description, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, color = if (enabled) foreground.copy(alpha = .85f) else SharedUiColors.muted.copy(alpha = .4f))
        }
    }
}
