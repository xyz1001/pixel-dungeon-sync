package io.github.xyz1001.pdsync.android

import pdsync.i18n.AndroidHostMessages.*

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Thin attachment adapter. The established model still owns filtering, search and pinned selection. */
object AndroidInstalledAppPickerComposition {
    @JvmStatic
    fun show(activity: ComponentActivity, candidates: List<InstalledAppPickerModel.Entry>, selectedPackage: String?,
        state: InstalledAppPickerModel.State, active: BooleanSupplier, select: Consumer<String>, chooseFile: Runnable?) {
        if (activity.isDestroyed) return
        // The async completion renders the page again immediately after show(). A child of
        // android.R.id.content would be detached by setContentView before its first frame.
        // Own this zero-size composition in the window decor; the shared Dialog still owns
        // its own modal window and all of the existing M3 content.
        val host = activity.window.decorView as ViewGroup
        val view = ComposeView(activity)
        var lifecycleObserver: LifecycleEventObserver? = null
        var closed = false
        fun close(): Boolean {
            if (closed) return false
            closed = true
            lifecycleObserver?.let { activity.lifecycle.removeObserver(it) }
            view.disposeComposition()
            (view.parent as? ViewGroup)?.removeView(view)
            return true
        }
        fun canAct(): Boolean = !closed && !activity.isDestroyed && active.asBoolean &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            var query by remember { mutableStateOf(state.query()) }
            var showAll by remember { mutableStateOf(state.showAll()) }
            val visible = remember(query, showAll) { state.visible(candidates, selectedPackage) }
            val noMatches = remember(query, showAll) { state.visible(candidates, null).isEmpty() }
            SharedGamePickerDialog(
                candidates = visible.map { SharedGameCandidate(it.packageName, SharedGameIdentity(it.label, it.versionName), it.packageName) },
                selectedId = selectedPackage, enabled = true,
                onChoose = { packageName ->
                    if (canAct() && state.visible(candidates, selectedPackage).any { it.packageName == packageName } && close()) select.accept(packageName)
                },
                onDismiss = { close() },
                fileLabel = if (chooseFile != null) CHOOSE_AN_APK_FILE_FROM_DEVICE_STORAGE.text() else null,
                fileDescription = NO_ROOT_NEEDED_CHOOSE_AN_UNINSTALLED_APK.text(),
                onChooseFile = chooseFile?.let { action -> { if (canAct() && close()) action.run(); Unit } },
                searchQuery = query,
                onSearch = { if (canAct()) { state.setQuery(it); query = it } },
                showAll = showAll,
                onShowAll = { if (canAct()) { state.setShowAll(it); showAll = it } },
                noSearchMatches = noMatches,
                candidateIcon = { AndroidGameIcon.load(activity, it, "installed", null) },
            )
        }
        lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) close()
        }
        activity.lifecycle.addObserver(lifecycleObserver!!)
        host.addView(view, ViewGroup.LayoutParams(0, 0))
    }
}
