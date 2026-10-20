package io.github.xyz1001.pdsync.android

import pdsync.i18n.AndroidHostMessages.*

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import pdsync.SyncHistory
import pdsync.presentation.HistoryPresentation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import java.util.function.Consumer

/** Platform attachment only. Dialog fields, buttons, draft state and token checks are shared. */
object AndroidHomeDialogComposition {
    @JvmStatic
    fun confirmationView(
        activity: ComponentActivity,
        state: SharedConfirmationState,
        history: SyncHistory?,
        historyPage: Int,
        game: SharedGameIdentity?,
        status: String,
        statusKind: SharedHomeStatusKind,
        selectedId: String?,
        settingsBackdrop: SharedSettingsState?,
        confirm: Runnable,
        merge: Runnable?,
        cancel: Runnable,
    ): View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            AndroidAppViewport {
                SharedConnectionTheme {
                    Box(Modifier.fillMaxSize()) {
                        // These are the host's already-observed facts, never a new read or capability.
                        if (settingsBackdrop != null) {
                            SharedSettingsScreen(settingsBackdrop.copy(pageActive = false), object : SharedSettingsActions {
                                override fun perform(key: SharedSettingsKey) = Unit
                                override fun back() = Unit
                            })
                        } else {
                            SharedHomeScreen(SharedHomeState(game = game, status = status, statusKind = statusKind,
                                pageActive = false, selectedSnapshotId = selectedId,
                                history = history?.let { HistoryPresentation.from(it, historyPage) }),
                                object : SharedHomeActions {
                                    override fun settings() = Unit
                                    override fun upload() = Unit
                                    override fun restore() = Unit
                                    override fun launch() = Unit
                                    override fun selectSnapshot(snapshotId: String) = Unit
                                    override fun rename(snapshotId: String?) = Unit
                                    override fun changeHistoryPage(page: Int) = Unit
                                })
                        }
                        SharedConfirmationDialog(state, { confirm.run() }, { merge?.run() }, { cancel.run() })
                    }
                }
            }
        }
    }

    /** Add a modal over the current shared home without replacing or reimplementing the page. */
    @JvmStatic
    fun showNaming(activity: ComponentActivity, token: Long, initialName: String, notice: String,
                   save: Consumer<String>, maintenance: Runnable?) {
        val view = ComposeView(activity)
        var closed = false
        fun close(): Boolean {
            if (closed) return false
            closed = true
            view.disposeComposition()
            (view.parent as? ViewGroup)?.removeView(view)
            return true
        }
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            var name by remember(token) { mutableStateOf(initialName) }
            SharedNamingDialog(SharedNamingState(token, name, notice = notice),
                onNameChange = { name = it },
                onSave = { if (close()) save.accept(it) },
                onDismiss = { close() }) {
                maintenance?.let { action ->
                    SharedActionButton(CONFIRM_RUN_IDENTITY.text(), { if (close()) action.run() }, style = SharedButtonStyle.TEXT)
                }
            }
        }
        activity.addContentView(view, ViewGroup.LayoutParams(0, 0))
    }
}
