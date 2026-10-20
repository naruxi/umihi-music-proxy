package ca.ilianokokoro.umihi.music

import android.app.Application
import ca.ilianokokoro.umihi.music.core.cache.CoilImageLoader
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import ca.ilianokokoro.umihi.music.proxy.CrashGuard
import ca.ilianokokoro.umihi.music.proxy.ProxyController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Umihi : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        NotificationManager.init(this)
        CrashGuard.install(this)

        // If the previous session died while booting, the tunnel is what most likely killed it,
        // so it stays off this time. Otherwise restore it — when the user never enabled the proxy
        // this is just a DataStore read, and the 80 MB core is never loaded.
        val diedEarly = CrashGuard.beginBoot(this)

        appScope.launch {
            if (diedEarly) {
                ProxyController.recoverFromCrash(this@Umihi)
            } else {
                ProxyController.bootstrap(this@Umihi)
            }

            // Surviving the window is what clears the marker; a crash inside it leaves the marker
            // behind for the next launch to find.
            delay(CrashGuard.BOOT_WINDOW_MS)
            CrashGuard.endBoot(this@Umihi)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return CoilImageLoader.get(context)
    }
}
