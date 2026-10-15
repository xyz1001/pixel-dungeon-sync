package pdsync

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.xyz1001.pdsync.android.SharedConnectionConfig
import io.github.xyz1001.pdsync.android.SharedConnectionPorts
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Path
import org.jetbrains.skia.Image

/** Platform I/O only. The shared controller owns provider, account and authentication UI. */
internal class DesktopConnectionAdapter(
 initial: AppConfig?,
 currentPassword: String,
 private val configFile: ConfigFile,
 private val privateCache: Path,
 private val setupFactory: (AppConfig) -> ConnectionSetup = { ConnectionSetup(it) }
) : SharedConnectionPorts, AutoCloseable {
 private val lock = Any()
 private val original = initial ?: newConnectionConfig()
 private var pending: ConnectionSetup? = null
 private var verified: SharedConnectionConfig? = null
 private var running = false
 private var closed = false
 private var applied: AppliedConnection? = null

 val initialConnection: SharedConnectionConfig? = initial?.takeIf { it.endpoint.isNotBlank() }?.let { desktopConnectionConfig(it, currentPassword) }
 val profile: String get() = original.profile
 val appliedConnection: AppliedConnection? get() = synchronized(lock) { applied }

 /** This is the existing production DAV connect/provision path, never an invented success. */
 override fun testConnection(config: SharedConnectionConfig): Boolean {
  val candidate = synchronized(lock) {
   check(!closed && !running && applied == null) { "连接页面已关闭或正在处理，请稍候。" }
   pending?.cancel()
   pending = null
   verified = null
   val next = configurationFor(config)
   setupFactory(next).also {
    it.update(next, config.password)
    pending = it
    running = true
   }
  }
  try {
   val result = candidate.connect()
   return synchronized(lock) {
    check(!closed && pending === candidate) { "连接已取消。" }
    when (result) {
     is ConnectionTestResult.Success -> { verified = config; true }
     is ConnectionTestResult.Failure -> throw IOException(result.message)
    }
   }
  } finally {
   synchronized(lock) { if (pending === candidate) running = false }
  }
 }

 /** Only the shared controller's explicit Next/Save action reaches this local-file write. */
 override fun saveAndActivate(config: SharedConnectionConfig) = synchronized(lock) {
  check(!closed && !running && applied == null && sameConnection(verified, config)) { "请先成功测试当前配置，再保存。" }
  val candidate = checkNotNull(pending) { "请先成功测试当前配置，再保存。" }
  applied = candidate.applyFilePassword(configFile)
  verified = null
  pending = null
 }

 override fun cacheDirectory(): Path = SafePaths.checked(privateCache)
 override fun elapsedRealtimeMs(): Long = (System.nanoTime() / 1_000_000).coerceAtLeast(0)
 // A process cannot reliably identify the OS boot session on every desktop platform.
 // Unknown boot identity deliberately prevents cached elapsed time from granting access.
 override fun bootCount(): Int = -1
 override fun copyUsername(username: String) {
  Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(username), null)
 }
 override fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching {
  Image.makeFromEncoded(bytes).toComposeImageBitmap()
 }.getOrNull()

 override fun close() = synchronized(lock) {
  if (!closed) {
   closed = true
   pending?.cancel()
   pending = null
   verified = null
   running = false
  }
 }

 private fun configurationFor(config: SharedConnectionConfig): AppConfig {
  require(config.profile == original.profile) { "同步档案不能在连接设置中改变。" }
  require(!config.official || isDesktopOfficialRoot(config.endpoint)) { "官方服务地址无效。" }
  val endpoint = if (config.official) DESKTOP_OFFICIAL_ROOT else config.endpoint
  val provider = if (config.official) "official" else "custom"
  val identityChanged = endpoint.trimEnd('/') != original.endpoint.trimEnd('/') ||
   config.username != original.username || provider != original.connectionProvider
  return original.copy(
   endpoint = endpoint,
   username = config.username,
   connectionProvider = provider,
   password = null,
   rememberPassword = false,
   onboardingProgress = if (identityChanged) OnboardingProgress.PENDING else original.onboardingProgress
  )
 }
}

internal const val DESKTOP_OFFICIAL_ROOT = "https://webdav.xyz1001.top:8443/"

/** Accept only the exact root emitted by this client, allowing ConfigFile's removed final slash. */
internal fun isDesktopOfficialRoot(endpoint: String): Boolean =
 endpoint == DESKTOP_OFFICIAL_ROOT || endpoint == DESKTOP_OFFICIAL_ROOT.removeSuffix("/")

internal fun desktopConnectionConfig(config: AppConfig, password: String): SharedConnectionConfig {
 val official = config.connectionProvider == "official" && isDesktopOfficialRoot(config.endpoint)
 return SharedConnectionConfig(if (official) DESKTOP_OFFICIAL_ROOT else config.endpoint,
  config.username, password, config.profile, official)
}

private fun sameConnection(a: SharedConnectionConfig?, b: SharedConnectionConfig): Boolean = a != null &&
 a.endpoint == b.endpoint && a.username == b.username && a.password == b.password &&
 a.profile == b.profile && a.official == b.official

/** Event-driven connection-page visibility. A focus change invalidates proof, not the form. */
internal class DesktopConnectionVisibility(private val foreground: (Boolean) -> Unit) : AutoCloseable {
 private var previous: Boolean? = null
 private var closed = false
 fun update(showing: Boolean, focused: Boolean, iconified: Boolean) {
  if (closed) return
  val next = showing && focused && !iconified
  if (previous != next) { previous = next; foreground(next) }
 }
 override fun close() {
  if (closed) return
  closed = true
  if (previous != false) foreground(false)
  previous = false
 }
}

/** Native events avoid polling and leave the application's existing background sync untouched. */
internal fun observeDesktopConnectionWindow(window: java.awt.Window, foreground: (Boolean) -> Unit): AutoCloseable {
 val visibility = DesktopConnectionVisibility(foreground)
 fun update() = visibility.update(window.isShowing, window.isFocused,
  window is java.awt.Frame && (window.extendedState and java.awt.Frame.ICONIFIED) != 0)
 val windowEvents = object : java.awt.event.WindowAdapter() {
  override fun windowGainedFocus(event: java.awt.event.WindowEvent) = update()
  override fun windowLostFocus(event: java.awt.event.WindowEvent) = update()
  override fun windowIconified(event: java.awt.event.WindowEvent) = update()
  override fun windowDeiconified(event: java.awt.event.WindowEvent) = update()
  override fun windowClosed(event: java.awt.event.WindowEvent) = update()
  override fun windowStateChanged(event: java.awt.event.WindowEvent) = update()
 }
 val componentEvents = object : java.awt.event.ComponentAdapter() {
  override fun componentShown(event: java.awt.event.ComponentEvent) = update()
  override fun componentHidden(event: java.awt.event.ComponentEvent) = update()
 }
 window.addWindowListener(windowEvents)
 window.addWindowFocusListener(windowEvents)
 if (window is java.awt.Frame) window.addWindowStateListener(windowEvents)
 window.addComponentListener(componentEvents)
 update()
 return AutoCloseable {
  window.removeWindowListener(windowEvents)
  window.removeWindowFocusListener(windowEvents)
  if (window is java.awt.Frame) window.removeWindowStateListener(windowEvents)
  window.removeComponentListener(componentEvents)
  visibility.close()
 }
}
