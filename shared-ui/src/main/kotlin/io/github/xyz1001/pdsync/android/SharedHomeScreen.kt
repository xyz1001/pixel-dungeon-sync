package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Optional production-layout observer for offscreen measurement and pointer verification. */
internal interface SharedHomeLayoutObserver {
    fun element(name: String, boundsInRoot: Rect) {}
}
internal val LocalSharedHomeLayoutObserver = staticCompositionLocalOf<SharedHomeLayoutObserver?> { null }

/** One physical home page for both platforms. This presentation performs no filesystem/network I/O. */
@Composable
fun SharedHomeScreen(state: SharedHomeState, actions: SharedHomeActions) {
    SharedConnectionTheme {
        BoxWithConstraints { SharedHomeContent(state, actions, narrow = maxWidth <= 560.dp) }
    }
}

@Composable
private fun SharedHomeContent(state: SharedHomeState, actions: SharedHomeActions, narrow: Boolean) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val interactions = remember { SharedHomeInteractions({ currentState }, { currentActions }) }
    val observer = LocalSharedHomeLayoutObserver.current
    fun observed(name: String) = Modifier.onGloballyPositioned { observer?.element(name, it.boundsInRoot()) }
    SharedAppShell(title = "像素地牢", eyebrow = "存档同步", game = state.game,
        headerActions = {
            SharedActionButton("设置", interactions::settings, enabled = state.canInteract,
                style = SharedButtonStyle.TEXT, modifier = Modifier.padding(start = 20.dp).then(observed("settings")))
        }) {
        if (state.status.isNotBlank()) {
            Surface(Modifier.fillMaxWidth().then(observed("status")), color = Color(0xFFEDF4EE),
                shape = RoundedCornerShape(12.dp)) {
                Text(state.status, Modifier.padding(15.dp), color = Color(0xFF41614D),
                    fontSize = 14.sp, lineHeight = 22.4.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 12.dp)) {
                SharedActionButton(if (state.pending == SharedHomeOperation.UPLOAD) "上传中…" else "上传",
                    interactions::upload, enabled = state.uploadEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).then(observed("upload")))
                SharedActionButton(if (state.pending == SharedHomeOperation.RESTORE) "恢复中…" else "恢复",
                    interactions::restore, enabled = state.restoreEnabled, style = SharedButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).then(observed("restore")))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            SharedActionButton(if (state.pending == SharedHomeOperation.LAUNCH) "正在启动…" else "启动游戏",
                interactions::launch, enabled = state.launchEnabled, style = SharedButtonStyle.TEXT,
                modifier = observed("launch"))
        }
        Surface(Modifier.fillMaxWidth().then(observed("target")), shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, SharedUiColors.line), color = MaterialTheme.colors.surface) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.targetTitle, fontSize = 14.sp, lineHeight = 22.4.sp, fontWeight = FontWeight.SemiBold)
                val target = state.restoreTarget
                Text(target?.summary ?: state.emptyTargetMessage, fontSize = 13.sp, lineHeight = 20.8.sp,
                    color = SharedUiColors.muted)
                if (target != null && target.notice.isNotBlank()) Text(target.notice,
                    color = if (target.noticeError) MaterialTheme.colors.error else SharedUiColors.muted,
                    fontSize = 12.sp, lineHeight = 19.2.sp)
            }
        }
        if (state.notice.isNotBlank()) Text(state.notice, Modifier.padding(top = 12.dp).then(observed("notice")),
            color = if (state.noticeError) MaterialTheme.colors.error else SharedUiColors.muted,
            fontSize = 14.sp, lineHeight = 22.4.sp)
        if (state.details.isNotBlank()) {
            var expanded by remember(state.details) { mutableStateOf(false) }
            SharedActionButton(if (expanded) "收起详细信息" else "详细信息", { expanded = !expanded },
                style = SharedButtonStyle.TEXT, modifier = observed("details-toggle"))
            if (expanded) Text(state.details, observed("details"), color = SharedUiColors.muted,
                fontSize = 12.sp, lineHeight = 19.2.sp)
        }
        Spacer(Modifier.height(26.dp))
        SharedHistorySection(state.history, state.selectedSnapshotId, !state.canInteract,
            interactions::selectSnapshot, interactions::rename, interactions::changeHistoryPage,
            modifier = observed("history"))
    }
}
