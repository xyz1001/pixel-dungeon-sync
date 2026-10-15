package android.graphics.drawable

import android.graphics.Canvas
import android.graphics.Rect

abstract class Drawable {
    open val intrinsicWidth = -1
    open val intrinsicHeight = -1
    var bounds = Rect(0, 0, 0, 0)
    fun copyBounds(): Rect = bounds.copy()
    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        bounds = Rect(left, top, right, bottom)
    }
    abstract fun draw(canvas: Canvas)
}
