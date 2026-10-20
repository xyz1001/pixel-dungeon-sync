package pdsync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.xyz1001.pdsync.android.ConnectionEntryModel
import io.github.xyz1001.pdsync.android.SharedConnectionConfig
import io.github.xyz1001.pdsync.android.SharedConnectionController
import io.github.xyz1001.pdsync.android.SharedConnectionHost
import io.github.xyz1001.pdsync.android.SharedConnectionPorts
import io.github.xyz1001.pdsync.android.SharedInlineAppreciation
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/** A read-only, page-owned panel. Its work never participates in save comparison or completion. */
@Composable
internal fun DesktopComparisonAppreciation(
    config: AppConfig,
    password: String,
    configFile: ConfigFile,
    cacheDirectory: Path,
) {
    if (config.connectionProvider != "official" || !isDesktopOfficialRoot(config.endpoint)) return
    // A different connection gets a different controller; unrelated comparison updates keep it.
    key(config.endpoint, config.username, password, config.profile, config.connectionProvider) {
        val adapter = remember { DesktopConnectionAdapter(config, password, configFile, cacheDirectory) }
        val io = remember { Executors.newSingleThreadExecutor { task ->
            Thread(task, "pdsync-appreciation").apply { isDaemon = true }
        } }
        val ui = remember { Executor { task ->
            if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeLater(task)
        } }
        val readOnlyPorts = remember { object : SharedConnectionPorts by adapter {
            override fun testConnection(config: SharedConnectionConfig): Boolean = error("Read-only panel")
            override fun saveAndActivate(config: SharedConnectionConfig) = error("Read-only panel")
            override fun saveAuthenticatedOfficial(config: SharedConnectionConfig) = error("Read-only panel")
        } }
        val controller = remember { SharedConnectionController(ConnectionEntryModel.Origin.SETTINGS,
            adapter.initialConnection, adapter.profile, readOnlyPorts, io, ui) }
        DisposableEffect(controller) {
            controller.attach(object : SharedConnectionHost {
                override fun onBack(destination: ConnectionEntryModel.Back) = Unit
                override fun onCommitted(config: SharedConnectionConfig) = Unit
                override fun onBusyChanged(busy: Boolean) = Unit
            })
            controller.foreground(true)
            onDispose {
                controller.close()
                adapter.close()
                io.shutdownNow()
            }
        }
        SharedInlineAppreciation(controller)
    }
}
