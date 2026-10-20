package ca.ilianokokoro.umihi.music

import android.app.Application
import ca.ilianokokoro.umihi.music.core.cache.CoilImageLoader
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import ca.ilianokokoro.umihi.music.proxy.ProxyController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Umihi : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        NotificationManager.init(this)

        // Restores the proxy tunnel off the main thread. When the user has never enabled it this
        // is just a DataStore read, so the slow part (loading an 80 MB native core) only happens
        // for people who actually turned the feature on.
        appScope.launch { ProxyController.bootstrap(this@Umihi) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return CoilImageLoader.get(context)
    }
}
