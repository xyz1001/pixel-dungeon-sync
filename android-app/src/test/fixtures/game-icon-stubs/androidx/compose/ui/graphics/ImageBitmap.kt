package androidx.compose.ui.graphics

import android.graphics.Bitmap

class ImageBitmap(val bitmap: Bitmap) {
    val width get() = bitmap.width
    val height get() = bitmap.height
}

fun Bitmap.asImageBitmap() = ImageBitmap(this)
