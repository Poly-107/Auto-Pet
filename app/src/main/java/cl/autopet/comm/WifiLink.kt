package cl.autopet.comm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class WifiLink(private val scope: CoroutineScope, private val listener: Listener) {

    interface Listener {
        fun onLine(line: String, from: String)
        fun onState(msg: String)
    }

    companion object {
        const val PORT_ESP = 8080
        const val PORT_REMOTE = 9090
        private const val MAX_LINE = 120

        fun bindWifi(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val wifiNet = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return false
            cm.bindProcessToNetwork(wifiNet)
            return true
        }

        fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }

    private var up: Socket? = null
    private var upOut: PrintWriter? = null
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<PrintWriter>()
    private var sessionPin: String = ""
    private var isRemoteMode: Boolean = false

    private var authFails = 0
    private var blockRemoteUntil = 0L

    fun connectUp(host: String, port: Int, authPin: String?) = scope.launch(Dispatchers.IO) {
        try {
            val addr = InetAddress.getByName(host)
            require(addr.isSiteLocalAddress) { "Solo se permiten IP de red local" }
            isRemoteMode = authPin != null
            if (authPin != null) sessionPin = authPin

            val s = Socket()
            s.connect(InetSocketAddress(addr, port), 4000)
            up = s
            upOut = PrintWriter(s.getOutputStream(), true)
            authPin?.let { upOut?.println("AUTH:$it") }
            listener.onState("Conectado a $host:$port")

            s.getInputStream().bufferedReader().forEachLine { raw ->
                if (raw.length <= MAX_LINE) {
                    val clean = raw.trim()
                    if (isRemoteMode) {
                        if (clean == "OK AUTH" || clean == "ERR AUTH") {
                            listener.onState(if (clean == "OK AUTH") "Autenticado con Gateway" else "PIN rechazado")
                        } else {
                            FrameSigner.verifyAndExtract(clean, sessionPin)?.let { verified ->
                                listener.onLine(verified, "UP")
                            }
                        }
                    } else {
                        listener.onLine(clean, "UP")
                    }
                }
            }
            listener.onState("Desconectado")
        } catch (e: Exception) {
            listener.onState("Error: ${e.message}")
        } finally {
            runCatching { up?.close() }
            upOut = null
        }
    }

    fun sendUp(line: String) {
        scope.launch(Dispatchers.IO) {
            val payload = if (isRemoteMode && sessionPin.isNotEmpty()) {
                FrameSigner.sign(line, sessionPin)
            } else {
                line
            }
            upOut?.println(payload)
        }
    }

    fun startServer(port: Int, pin: String) {
        sessionPin = pin
        scope.launch(Dispatchers.IO) {
            try {
                val ipLocal = localIp() ?: throw IllegalStateException("Sin Wi-Fi activo")
                val ss = ServerSocket(port, 1, InetAddress.getByName(ipLocal))
                server = ss
                listener.onState("Gateway activo en $ipLocal:$port")
                while (!ss.isClosed) {
                    val c = ss.accept()
                    launch { handleClient(c, pin) }
                }
            } catch (e: Exception) {
                listener.onState("Servidor: ${e.message}")
            }
        }
    }

    private fun handleClient(c: Socket, pin: String) {
        if (System.currentTimeMillis() < blockRemoteUntil) {
            runCatching { c.close() }
            return
        }
        val out = PrintWriter(c.getOutputStream(), true)
        try {
            c.soTimeout = 5000
            val rd = c.getInputStream().bufferedReader()
            val first = rd.readLine() ?: return
            val ok = MessageDigest.isEqual(first.toByteArray(), "AUTH:$pin".toByteArray())
            if (!ok) {
                if (++authFails >= 3) {
                    blockRemoteUntil = System.currentTimeMillis() + 60_000
                    authFails = 0
                }
                out.println("ERR AUTH")
                return
            }
            authFails = 0
            out.println("OK AUTH")
            c.soTimeout = 0
            clients += out
            listener.onState("Remoto vinculado: ${c.inetAddress.hostAddress}")

            rd.forEachLine { raw ->
                if (raw.length <= MAX_LINE) {
                    FrameSigner.verifyAndExtract(raw.trim(), pin)?.let { verified ->
                        listener.onLine(verified, "REMOTE")
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            clients -= out
            runCatching { c.close() }
        }
    }

    fun broadcast(line: String) {
        if (sessionPin.isEmpty()) return
        val signed = FrameSigner.sign(line, sessionPin)
        clients.forEach { it.println(signed) }
    }

    fun close() {
        runCatching { up?.close() }
        runCatching { server?.close() }
        clients.clear()
    }
}