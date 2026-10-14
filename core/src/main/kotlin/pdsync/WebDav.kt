package pdsync
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebDavException(val statusCode: Int): IOException("WebDAV HTTP $statusCode")

class WebDav(base: String, game: String, profile: String, username: String, password: String, client: OkHttpClient = OkHttpClient()): Store {
 private val client = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(30,TimeUnit.SECONDS).build()
 private val gameId = game
 private val profileId = profile
 private fun validate(m: Manifest) { require(m.schema == 1 && m.game == gameId && m.profile == profileId); require(m.id.matches(Regex("[0-9a-fA-F-]{36}"))); require(m.parent == null || m.parent.matches(Regex("[0-9a-fA-F-]{36}"))) }
 private val baseUrl = base.toHttpUrl().let { url ->
  require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "URL must not contain credentials, query or fragment" }
  require(url.scheme == "https" || (url.scheme == "http" && url.host in setOf("127.0.0.1","localhost","::1"))) { "HTTPS required except exact loopback hosts" }
  url.toString().trimEnd('/') + "/$game/$profile/"
 }
 private val auth = Credentials.basic(username,password)
 init { require(game.matches(Regex("[a-zA-Z0-9_-]+")) && profile.matches(Regex("[a-zA-Z0-9_-]+"))) }
 private fun call(path: String, method: String = "GET", bytes: ByteArray? = null, headers: Map<String,String> = emptyMap()): Pair<ByteArray,String?> {
  val builder = Request.Builder().url(baseUrl+path).header("Authorization",auth).method(method,bytes?.toRequestBody())
  headers.forEach { (k,v) -> builder.header(k,v) }
  val requestStarted = System.nanoTime()
  fun context() = DiagnosticDetails.builder().networkTarget(DiagnosticDetails.NetworkTarget.DAV)
   .requestDurationMs(((System.nanoTime() - requestStarted) / 1_000_000).coerceAtLeast(0)).build()
  try {
   client.newCall(builder.build()).execute().use { r ->
    DiagnosticLog.http(when(method) {
     "GET" -> DiagnosticHttpMethod.GET
     "PUT" -> DiagnosticHttpMethod.PUT
     else -> DiagnosticHttpMethod.OTHER
    }, r.code, context())
    if(r.code == 404 && method == "GET") throw java.nio.file.NoSuchFileException(path)
    if(!r.isSuccessful) throw WebDavException(r.code)
    val limit = if(path.endsWith(".zip")) MAX_SNAPSHOT_BYTES else MAX_MANIFEST_BYTES
    val body = r.body
    require(body == null || body.contentLength() <= limit) { "Remote response too large" }
    val data = body?.byteStream()?.readUpTo(limit + 1) ?: byteArrayOf()
    require(data.size <= limit) { "Remote response too large" }
    return data to r.header("ETag")
   }
  } catch(error: IOException) {
   if(error !is WebDavException && error !is java.nio.file.NoSuchFileException)
    DiagnosticLog.networkFailure(error, context())
   throw error
  }
 }
 private fun immutablePut(path: String, bytes: ByteArray) {
  try { call(path,"PUT",bytes,mapOf("If-None-Match" to "*")) }
  catch(e: WebDavException) {
   if(e.statusCode != 412) throw e
   require(call(path).first.contentEquals(bytes)) { "Existing immutable object differs" }
  }
 }
 override fun head(): RemoteHead? = try { val (bytes,etag) = call("head.json"); RemoteHead(json.decodeFromString<Manifest>(bytes.decodeToString()).also { validate(it) },etag) } catch(e: java.nio.file.NoSuchFileException) { null }
 override fun upload(snapshot: Snapshot) {
  validate(snapshot.manifest); require(snapshot.zip.size <= MAX_SNAPSHOT_BYTES)
  val metadata = json.encodeToString(snapshot.manifest).encodeToByteArray(); require(metadata.size <= MAX_MANIFEST_BYTES)
  immutablePut("${snapshot.manifest.id}.zip",snapshot.zip)
  immutablePut("${snapshot.manifest.id}.json",metadata)
 }
 override fun download(manifest: Manifest): Snapshot { validate(manifest); val stored = json.decodeFromString<Manifest>(call("${manifest.id}.json").first.decodeToString()); require(stored == manifest) { "Uploaded manifest mismatch" }; return Snapshot(manifest,call("${manifest.id}.zip").first) }
 override fun publish(manifest: Manifest, expected: RemoteHead?) {
  validate(manifest)
  val headers = if(expected == null) mapOf("If-None-Match" to "*") else {
   val e = expected.etag
   require(e != null && e.length >= 2 && e.first() == '"' && e.last() == '"' && e.substring(1,e.length-1).all { it.code == 0x21 || it.code in 0x23..0x7e || it.code in 0x80..0xff }) { "Strong ETag required; publishing disabled" }
   mapOf("If-Match" to e)
  }
  call("head.json","PUT",json.encodeToString(manifest).encodeToByteArray(),headers)
 }
}
