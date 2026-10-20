package io.github.xyz1001.pdsync.android

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Executes the real production adapter, with deferred first frame and replaceable Activity content. */
object AndroidPickerHostChecks {
    private fun compositions(group: ViewGroup): List<ComposeView> = group.children.flatMap {
        when (it) { is ComposeView -> listOf(it); is ViewGroup -> compositions(it); else -> emptyList() }
    }
    @JvmStatic fun main(args: Array<String>) {
        val activity = ComponentActivity()
        val model = InstalledAppPickerModel.State()
        val candidates = listOf(
            InstalledAppPickerModel.Entry("game.one", "Dungeon", "1", true, false),
            InstalledAppPickerModel.Entry("system.app", "System", "2", false, true))
        var active = true
        val selected = mutableListOf<String>()
        var files = 0
        fun open(): ComposeView {
            AndroidInstalledAppPickerComposition.show(activity, candidates, null, model,
                BooleanSupplier { active }, Consumer { selected += it }, Runnable { files++ })
            // Exact failing completion order: attach picker, render page, then first frame.
            activity.render()
            val host = compositions(activity.window.decorView).singleOrNull()
                ?: error("Picker was removed by completion's page render before first frame")
            host.drawFrame()
            check(PickerUi.candidates.map { it.id } == listOf("game.one"))
            activity.render()
            check(!host.disposed && host.parent != null)
            return host
        }
        var host = open()
        PickerUi.choose("missing.package")
        check(selected.isEmpty() && !host.disposed)
        active = false
        PickerUi.choose("game.one"); PickerUi.file?.invoke()
        check(selected.isEmpty() && files == 0 && !host.disposed)
        active = true
        PickerUi.search("System"); PickerUi.all(true)
        host.drawFrame()
        check(PickerUi.candidates.map { it.id } == listOf("system.app"))
        activity.render()
        host.drawFrame()
        check(!host.disposed && PickerUi.candidates.map { it.id } == listOf("system.app"))
        PickerUi.choose("system.app")
        PickerUi.choose("system.app")
        PickerUi.file?.invoke()
        PickerUi.dismiss()
        check(files == 0)
        check(selected == listOf("system.app") && host.disposed && host.parent == null)
        check(activity.lifecycle.observers.isEmpty())
        model.setQuery(""); model.setShowAll(false)
        host = open()
        PickerUi.file?.invoke(); PickerUi.file?.invoke()
        check(files == 1 && host.disposed && host.parent == null)
        host = open()
        PickerUi.dismiss(); PickerUi.dismiss()
        check(host.disposed && activity.lifecycle.observers.isEmpty())
        host = open()
        activity.lifecycle.dispatch(Lifecycle.Event.ON_STOP)
        PickerUi.choose("game.one")
        check(host.disposed && host.parent == null && selected.size == 1)
        check(activity.lifecycle.observers.isEmpty())
        activity.lifecycle.currentState = Lifecycle.State.RESUMED
        host = open()
        PickerUi.dismiss()
        // A background transition may happen after attachment but before Compose's first frame.
        val drawsBeforeStop = PickerUi.draws
        AndroidInstalledAppPickerComposition.show(activity, candidates, null, model,
            BooleanSupplier { true }, Consumer { selected += it }, null)
        host = compositions(activity.window.decorView).single()
        activity.lifecycle.dispatch(Lifecycle.Event.ON_STOP)
        check(host.disposed && host.parent == null && PickerUi.draws == drawsBeforeStop)
        check(activity.lifecycle.observers.isEmpty())
        activity.lifecycle.currentState = Lifecycle.State.RESUMED
        host = open()
        activity.isDestroyed = true
        activity.lifecycle.dispatch(Lifecycle.Event.ON_DESTROY)
        check(host.disposed && host.parent == null)
        AndroidInstalledAppPickerComposition.show(activity, candidates, null, model,
            BooleanSupplier { true }, Consumer { selected += it }, null)
        check(compositions(activity.window.decorView).isEmpty())
        println("Android picker host: render survival, filtering, stale callbacks, single dispatch, dismiss, stop and destruction PASS")
    }
}
