package magefree.app

import android.app.Application
import magefree.app.di.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Application entry point and DI root (story 0081; was Hilt's `@HiltAndroidApp`).
 *
 * **Where the graph is assembled, and where it fails.** Hilt built the component at compile time
 * from annotations scattered across ten modules; Koin builds it here, from an explicit list
 * ([appModules]). That makes the whole graph readable in one place — and it makes this the point at
 * which a missing module means a crash on whichever screen needed it, since Koin resolves at
 * runtime. `KoinGraphTest` is what stops that reaching a device.
 */
class MageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MageApp)
            modules(appModules)
        }
    }
}
