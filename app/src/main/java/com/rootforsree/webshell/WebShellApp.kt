package com.rootforsree.webshell

import android.app.Application
import com.rootforsree.webshell.server.ServerHost

/**
 * Holds the one [ServerHost] for the process. The host owns the Ktor
 * server lifecycle; the foreground service keeps the process alive while
 * it runs, and the ViewModel only observes the host's flows. Manual
 * construction, like the Drop app: one screen, one server, no DI graph.
 */
class WebShellApp : Application() {
    val serverHost: ServerHost by lazy { ServerHost(this) }
}
