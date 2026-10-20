package android.view
open class View { var parent: ViewGroup? = null; open fun attach() {}; open fun detach() {} }
open class ViewGroup : View() {
    class LayoutParams(val width: Int, val height: Int)
    val children = mutableListOf<View>()
    fun addView(view: View, params: LayoutParams) { children += view; view.parent = this; view.attach() }
    fun removeView(view: View) { children.remove(view); view.parent = null; view.detach() }
    fun removeAllViews() { children.toList().forEach(::removeView) }
}
class Window { val decorView = ViewGroup() }
