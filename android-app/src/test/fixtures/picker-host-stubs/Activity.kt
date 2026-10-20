package androidx.activity
import android.view.*
import androidx.lifecycle.Lifecycle
open class ComponentActivity {
    val window = Window()
    val lifecycle = Lifecycle()
    var isDestroyed = false
    val content = ViewGroup()
    init { window.decorView.addView(content, ViewGroup.LayoutParams(100,100)) }
    fun addContentView(view: View, params: ViewGroup.LayoutParams) { content.addView(view, params) }
    fun render() { content.removeAllViews(); content.addView(ViewGroup(), ViewGroup.LayoutParams(100,100)) }
}
