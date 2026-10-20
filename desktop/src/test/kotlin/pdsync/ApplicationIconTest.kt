package pdsync

import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

class ApplicationIconTest {
    @Test fun approvedArtworkIsAvailableFromApplicationClasspath() {
        val bytes = assertNotNull(javaClass.getResourceAsStream("/icons/pdsync.png")).use { it.readBytes() }
        assertEquals("076cf1ea7cbaba1b9a63cfd62923174f1b8a1f0c1a35e1a2bc225696d3537c12",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val image = assertNotNull(ImageIO.read(bytes.inputStream()))
        assertEquals(512, image.width)
        assertEquals(512, image.height)
        assertTrue(image.colorModel.hasAlpha())
        assertEquals(0, image.getRGB(0, 0) ushr 24)
        assertEquals(255, image.getRGB(256, 256) ushr 24)
    }
}
