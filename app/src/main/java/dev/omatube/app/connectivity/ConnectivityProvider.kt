package dev.omatube.app.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Connection bucket used to pick the playback quality preference. */
enum class ConnectionType {
    WIFI,
    DATA,
}

/** Supplies the connection type at the moment a stream is resolved. */
fun interface ConnectivityProvider {
    fun currentConnectionType(): ConnectionType
}

/**
 * Pure transport classification, kept free of Android types so it can be unit
 * tested on the JVM. [hasWifi] and [hasCellular] describe the active network's
 * transports; [isMetered] is the [ConnectivityManager.isActiveNetworkMetered]
 * fallback for every other transport. No active network is deliberately
 * conservative and maps to [ConnectionType.DATA].
 */
internal fun classifyConnection(
    hasActiveNetwork: Boolean,
    hasWifi: Boolean,
    hasCellular: Boolean,
    isMetered: Boolean,
): ConnectionType = when {
    !hasActiveNetwork -> ConnectionType.DATA
    hasWifi -> ConnectionType.WIFI
    hasCellular -> ConnectionType.DATA
    isMetered -> ConnectionType.DATA
    else -> ConnectionType.WIFI
}

/**
 * Android connectivity provider backed by [ConnectivityManager]. Wi-Fi maps to
 * [ConnectionType.WIFI]; cellular maps to [ConnectionType.DATA]. Any other
 * transport falls back to the active-network metered flag. A missing active
 * network or a [SecurityException] conservatively reports [ConnectionType.DATA]
 * so playback never assumes an unmetered connection.
 */
class AndroidConnectivityProvider(context: Context) : ConnectivityProvider {

    private val appContext: Context = context.applicationContext

    override fun currentConnectionType(): ConnectionType {
        return try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
                ?: return ConnectionType.DATA
            val active = manager.activeNetwork ?: return ConnectionType.DATA
            val capabilities = manager.getNetworkCapabilities(active)
            classifyConnection(
                hasActiveNetwork = capabilities != null,
                hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                hasCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
                isMetered = manager.isActiveNetworkMetered,
            )
        } catch (_: SecurityException) {
            ConnectionType.DATA
        }
    }
}
