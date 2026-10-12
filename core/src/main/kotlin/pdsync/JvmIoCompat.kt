package pdsync

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded InputStream.readNBytes equivalent using APIs present before Android 8 / API 26. */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
 require(limit >= 0) { "Read limit must not be negative" }
 if (limit == 0) return byteArrayOf()
 val buffer = ByteArray(minOf(limit, 8192))
 val output = ByteArrayOutputStream(buffer.size)
 while (output.size() < limit) {
  val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
  if (count < 0) break
  if (count == 0) { val next = read(); if (next < 0) break; output.write(next) }
  else output.write(buffer, 0, count)
 }
 return output.toByteArray()
}
