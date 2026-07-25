package magefree.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and Hilt DI root. Annotating with [HiltAndroidApp] generates the
 * application-level Dagger component that every `@AndroidEntryPoint` hangs off. No bindings are
 * declared yet — this only proves the DI graph builds (story 0007).
 */
@HiltAndroidApp
class MageApp : Application()
