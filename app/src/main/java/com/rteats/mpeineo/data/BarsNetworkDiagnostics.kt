package com.rteats.mpeineo.data

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Unauthenticated connectivity probe, independent of BARS WebView cookies and JavaScript.
 * Never sends or logs BARS credentials, cookies, tokens, response bodies or URL queries.
 */
class BarsNetworkDiagnostics(
    application: Application,
    private val diagnostics: DiagnosticLog,
) {
    private val connectivityManager =
        application.getSystemService(ConnectivityManager::class.java)

    // Diagnostic HTTP calls deliberately do not reuse the authenticated WebView session.
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val host = "bars.mpei.ru"

    fun snapshot(reason: String) {
        runCatching {
            val network = connectivityManager.activeNetwork
            val caps = network?.let(connectivityManager::getNetworkCapabilities)
            val transports = buildList {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            }
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            diagnostics.log(
                "BARS_NET",
                "snapshot reason=$reason active=${network != null} " +
                    "transport=${transports.joinToString(",").ifBlank { "unknown" }} " +
                    "internet=$internet validated=$validated captive=$captive " +
                    "metered=${connectivityManager.isActiveNetworkMetered}",
            )
        }.onFailure { error ->
            diagnostics.log(
                "BARS_NET",
                "snapshot failed type=${error.javaClass.simpleName}",
            )
        }
    }

    suspend fun probe(reason: String) = withContext(Dispatchers.IO) {
        snapshot(reason)
        val start = SystemClock.elapsedRealtime()
        var dnsMillis: Long? = null
        var resolvedAddresses = 0
        var connectionStarted = false
        var tlsCompleted = false

        // This deliberately probes only the BARS host/root (no account-specific route).
        val tracingClient = client.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val started = SystemClock.elapsedRealtime()
                    return try {
                        Dns.SYSTEM.lookup(hostname).also { addresses ->
                            dnsMillis = SystemClock.elapsedRealtime() - started
                            resolvedAddresses = addresses.size
                        }
                    } catch (error: IOException) {
                        dnsMillis = SystemClock.elapsedRealtime() - started
                        throw error
                    }
                }
            })
            .eventListener(object : EventListener() {
                override fun connectStart(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy,
                ) {
                    connectionStarted = true
                }

                override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
                    tlsCompleted = handshake != null
                }
            })
            .build()

        val request = Request.Builder()
            .url("https://$host/bars_web/")
            .header("User-Agent", "MPEI-Neo-Connectivity-Probe")
            .header("Cache-Control", "no-cache")
            .build()

        diagnostics.log("BARS_NET", "probe started reason=$reason host=$host cookies=false")
        try {
            tracingClient.newCall(request).execute().use { response: Response ->
                diagnostics.log(
                    "BARS_NET",
                    "probe completed http=${response.code} elapsedMs=${SystemClock.elapsedRealtime() - start} " +
                        "dnsMs=$dnsMillis dnsAddresses=$resolvedAddresses " +
                        "connectStarted=$connectionStarted tlsCompleted=$tlsCompleted",
                )
            }
        } catch (error: IOException) {
            diagnostics.log(
                "BARS_NET",
                "probe failed type=${error.javaClass.simpleName} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - start} " +
                    "dnsMs=$dnsMillis dnsAddresses=$resolvedAddresses " +
                    "connectStarted=$connectionStarted tlsCompleted=$tlsCompleted",
            )
        }
    }
}
