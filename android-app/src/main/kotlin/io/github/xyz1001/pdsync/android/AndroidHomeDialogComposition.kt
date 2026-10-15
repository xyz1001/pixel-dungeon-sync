package io.github.xyz1001.pdsync.android

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
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
    fun confirmationView(activity: ComponentActivity, state: SharedConfirmationState,
                         confirm: Runnable, merge: Runnable?, cancel: Runnable): View =
        ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                SharedConnectionTheme {
                    SharedConfirmationDialog(state, { confirm.run() }, { merge?.run() }, { cancel.run() })
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
                    SharedActionButton("确认单局身份", { if (close()) action.run() }, style = SharedButtonStyle.TEXT)
                }
            }
        }
        activity.addContentView(view, ViewGroup.LayoutParams(0, 0))
    }
}
