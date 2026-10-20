package androidx.compose.ui.platform
import android.view.View
import androidx.activity.ComponentActivity
object ViewCompositionStrategy { object DisposeOnDetachedFromWindow }
class ComposeView(activity: ComponentActivity): View() {
    var body: (() -> Unit)? = null
    var disposed = false
    fun setViewCompositionStrategy(strategy: Any) {}
    fun setContent(content: () -> Unit) { this.body = content }
    fun disposeComposition() { disposed = true; body = null }
    override fun detach() { disposeComposition() }
    fun drawFrame() { check(!disposed); body?.invoke() }
}
