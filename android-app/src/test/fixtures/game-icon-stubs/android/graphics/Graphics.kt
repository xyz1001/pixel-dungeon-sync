package android.graphics

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

class Bitmap private constructor(val width: Int, val height: Int) {
    enum class Config { ARGB_8888 }
    var recycled = false
    fun recycle() { recycled = true }
    companion object {
        val created = mutableListOf<Bitmap>()
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            check(config == Config.ARGB_8888)
            return Bitmap(width, height).also { created.add(it) }
        }
    }
}

class Canvas(val bitmap: Bitmap)
