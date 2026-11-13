package com.aircursor.cloudpc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aircursor.core.RemoteInput
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The PC phone's side of "another phone as keyboard and touchpad": listens on the local network
 * (Wi-Fi or hotspot), shows up for the other phone by name (Android network service discovery),
 * and accepts one phone at a time that knows the 6-digit code shown on this screen. Five wrong
 * codes make a new code. Commands are handed to [onCommand] on the main thread.
 */
class RemoteInputHost(
    private val context: Context,
    private val onCommand: (RemoteInput.Cmd) -> Unit,
    private val onState: (State) -> Unit,
) {
    data class State(val running: Boolean, val code: String, val connected: String? = null)

    private val prefs = context.getSharedPreferences("remote_input", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val nsd = context.getSystemService(NsdManager::class.java)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var wrong = 0

    /** The code stays the same between starts, so a paired phone reconnects by itself. */
    var code: String = prefs.getString("code", null) ?: newCode()
        private set

    val running get() = server != null

    /** "192.168.1.5:41234" – for "Connect by address" when the phones cannot find each other. */
    fun address(): String? {
        val port = server?.localPort ?: return null
        val ip = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (_: Exception) { null } ?: return null
        return "$ip:$port"
    }

    private fun newCode(): String = RemoteInput.newCode().also {
        prefs.edit().putString("code", it).apply()
        code = it
    }

    fun renewCode() {
        newCode()
        closeClient()
        post()
    }

    private fun post(connected: String? = null) = main.post { onState(State(running, code, connected)) }

    fun start() {
        if (server != null) return
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "listen failed", e)
            return
        }
        server = s
        register(s.localPort)
        Thread({ acceptLoop(s) }, "remote-input").start()
        post()
    }

    fun stop() {
        server?.let { try { it.close() } catch (_: Exception) {} }
        server = null
        closeClient()
        registration?.let { try { nsd?.unregisterService(it) } catch (_: Exception) {} }
        registration = null
        post()
    }

    private fun closeClient() {
        client?.let { try { it.close() } catch (_: Exception) {} }
        client = null
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "AirCursor PC – " + (Build.MODEL ?: "phone")
            serviceType = RemoteInput.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(i: NsdServiceInfo, error: Int) { Log.w(TAG, "nsd register failed $error") }
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, error: Int) = Unit
        }
        registration = l
        try {
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "nsd", e)
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (server === s) {
            val sock = try { s.accept() } catch (_: Exception) { break }
            closeClient()   // one phone at a time: the newest wins
            client = sock
            Thread({ serve(sock) }, "remote-input-client").start()
        }
    }

    private fun serve(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 8000
            val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8), 8192)
            val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            val first = input.readLine() ?: return
            val given = RemoteInput.parseHello(first)
            if (given == null || given != code) {
                out.write("NO\n"); out.flush()
                if (++wrong >= 5) {
                    wrong = 0
                    newCode()
                    post()
                }
                return
            }
            wrong = 0
            out.write("OK " + (Build.MODEL ?: "AirCursor PC").replace('\n', ' ') + "\n"); out.flush()
            val name = sock.inetAddress?.hostAddress ?: "phone"
            post(name)
            sock.soTimeout = 30_000   // the other phone pings every few seconds
            while (client === sock) {
                val line = try { input.readLine() } catch (_: SocketTimeoutException) { break } ?: break
                val cmd = RemoteInput.parse(line) ?: continue
                if (cmd != RemoteInput.Cmd.Ping) main.post { onCommand(cmd) }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
            if (client === sock) client = null
            post()
        }
    }

    companion object {
        private const val TAG = "RemoteInputHost"
    }
}
