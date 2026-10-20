package androidx.lifecycle
fun interface LifecycleEventObserver { fun onStateChanged(source: Any, event: Lifecycle.Event) }
class Lifecycle {
    enum class State { DESTROYED, CREATED, STARTED, RESUMED; fun isAtLeast(state: State) = ordinal >= state.ordinal }
    enum class Event { ON_STOP, ON_DESTROY }
    var currentState = State.RESUMED
    val observers = mutableListOf<LifecycleEventObserver>()
    fun addObserver(observer: LifecycleEventObserver) { observers += observer }
    fun removeObserver(observer: LifecycleEventObserver) { observers -= observer }
    fun dispatch(event: Event) { currentState = if (event == Event.ON_DESTROY) State.DESTROYED else State.CREATED; observers.toList().forEach { it.onStateChanged(this, event) } }
}
