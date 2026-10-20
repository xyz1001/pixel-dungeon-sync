package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The M3 chooser uses real host candidates; optional search is backed by the platform's existing filter. */
@Composable
fun SharedGamePickerDialog(
    candidates: List<SharedGameCandidate>,
    selectedId: String?,
    enabled: Boolean,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    fileLabel: String? = null,
    fileDescription: String = "",
    onChooseFile: (() -> Unit)? = null,
    searchQuery: String? = null,
    onSearch: (String) -> Unit = {},
    showAll: Boolean = false,
    onShowAll: (Boolean) -> Unit = {},
    noSearchMatches: Boolean = false,
    candidateIcon: (String) -> ImageBitmap? = { null },
) {
    SharedConnectionTheme {
        val observer = LocalSharedGameSelectionLayoutObserver.current
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = (maxHeight - 24.dp).coerceAtLeast(160.dp))
                    .onGloballyPositioned { observer?.element("game-picker", it.boundsInRoot()) },
                    shape = RoundedCornerShape(24.dp), elevation = 8.dp, border = BorderStroke(1.dp, SharedUiColors.line)) {
                    Column(Modifier.padding(28.dp)) {
                        Text(SharedMessages.CHOOSE_MANAGED_GAME.text(), fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text(SharedMessages.CHOOSE_VERSION_HINT.text(), fontSize = 13.sp, lineHeight = 19.5.sp,
                            color = SharedUiColors.muted)
                        Spacer(Modifier.height(16.dp))
                        if (searchQuery != null) {
                            SharedTextField(SharedMessages.SEARCH_APPS.text(), searchQuery, onSearch, enabled = enabled)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(showAll, onCheckedChange = onShowAll, enabled = enabled)
                                Text(SharedMessages.SHOW_ALL_APPS.text(), fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        if (noSearchMatches || candidates.isEmpty()) {
                            Text(SharedMessages.NO_MATCHING_APPS.text(), fontSize = 13.sp, color = SharedUiColors.muted)
                            Spacer(Modifier.height(8.dp))
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(candidates, key = { it.id }) { candidate ->
                                val icon = candidate.game.icon ?: remember(candidate.id) { candidateIcon(candidate.id) }
                                Surface(Modifier.fillMaxWidth()
                                    .onGloballyPositioned { observer?.element("candidate:${candidate.id}", it.boundsInRoot()) }
                                    .clickable(enabled = enabled, role = Role.Button) { onChoose(candidate.id) },
                                    shape = RoundedCornerShape(12.dp), border = BorderStroke(
                                        if (selectedId == candidate.id) 2.dp else 1.dp,
                                        if (selectedId == candidate.id) MaterialTheme.colors.primary else SharedUiColors.line)) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        if (icon != null) Image(icon, null, Modifier.size(28.dp), contentScale = ContentScale.Fit)
                                        else CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.primary) {
                                            SharedReferenceIcon("game", Modifier.size(20.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(candidate.game.name, fontSize = 13.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Bold)
                                            candidate.game.version?.takeIf(String::isNotBlank)?.let {
                                                Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = SharedUiColors.muted)
                                            }
                                            if (searchQuery != null && candidate.location.isNotBlank())
                                                Text(candidate.location, fontSize = 11.sp, lineHeight = 16.5.sp, color = SharedUiColors.muted)
                                        }
                                    }
                                }
                            }
                        }
                        if (onChooseFile != null && fileLabel != null) {
                            Spacer(Modifier.height(20.dp))
                            Divider(color = SharedUiColors.line)
                            Spacer(Modifier.height(8.dp))
                            Surface(Modifier.fillMaxWidth()
                                .onGloballyPositioned { observer?.element("picker-file", it.boundsInRoot()) }
                                .clickable(enabled = enabled, role = Role.Button, onClick = onChooseFile),
                                color = SharedUiColors.background, shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, SharedUiColors.line)) {
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.primary) {
                                        SharedReferenceIcon("apk", Modifier.size(20.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(fileLabel, fontSize = 13.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Bold)
                                        if (fileDescription.isNotBlank()) Text(fileDescription, fontSize = 13.sp,
                                            lineHeight = 19.5.sp, color = SharedUiColors.muted)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        SharedActionButton(SharedMessages.CANCEL.text(), onDismiss, Modifier.align(Alignment.CenterHorizontally), style = SharedButtonStyle.TEXT)
                    }
                }
            }
        }
    }
}
