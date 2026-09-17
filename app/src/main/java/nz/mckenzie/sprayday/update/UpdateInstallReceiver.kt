package nz.mckenzie.sprayday.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Passes the installer's answer to whoever is waiting for it.
 *
 * Process-wide with a single listener on purpose: one install is in flight at a time (a
 * second would be a second copy of the app), and a process that was replaced mid-install
 * has nobody left to tell - which is the normal way this ends.
 */
internal object InstallStatusRelay {

    @Volatile
    private var listener: ((Intent) -> Unit)? = null

    fun listen(handler: ((Intent) -> Unit)?) {
        listener = handler
    }

    fun deliver(intent: Intent) {
        listener?.invoke(intent)
    }
}

/**
 * Receives the installer's answer to a committed session.
 *
 * Declared in the manifest rather than registered at the call site, because the answer can
 * arrive after this app's process has been replaced - and because a commit made from a
 * screen that has since been closed must still find somewhere to land.
 *
 * Not exported: the only thing that should be able to tell an app its own install finished
 * is the installer holding the pending intent the app handed it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        InstallStatusRelay.deliver(intent)
    }
}
