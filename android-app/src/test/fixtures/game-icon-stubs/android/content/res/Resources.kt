package android.content.res

import android.graphics.drawable.Drawable

class Configuration(var identity: String = "default") {
    override fun toString(): String = identity
}

open class Resources {
    val configuration = Configuration()
    open fun getDrawable(id: Int, theme: Any?): Drawable = error("No resource")
}
