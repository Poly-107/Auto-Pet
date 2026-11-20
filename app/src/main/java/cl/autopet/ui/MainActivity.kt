package cl.autopet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.autopet.comm.*
import cl.autopet.data.DbHelper
import cl.autopet.data.Registro
import cl.autopet.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), WifiLink.Listener {
    private lateinit var b: ActivityMainBinding
    private lateinit var db: DbHelper
    private lateinit var link: WifiLink
    private lateinit var user: String
    private val adapter = HistorialAdapter()
    private var isGateway = true
    private var simHardware = true
    private var simNivelPct = 85
    @Volatile private var pendingMs = 0

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        user = intent.getStringExtra("USER") ?: run { finish(); return }
        db = DbHelper(this)
        link = WifiLink(lifecycleScope, this)

        b.rvHist.layoutManager = LinearLayoutManager(this)
        b.rvHist.adapter = adapter
        b.tvIpLocal.text = "IP local: ${WifiLink.localIp() ?: "sin Wi-Fi"}"

        b.seekT.max = Validator.MAX_MS - Validator.MIN_MS
        b.seekT.progress = 300
        b.tvT.text = "500 ms"
        b.seekT.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                b.tvT.text = "${p + Validator.MIN_MS} ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        b.btnConectar.setOnClickListener { conectar() }
        b.btnDosis.setOnClickListener { dosificar() }
        b.btnStatus.setOnClickListener { enviarComando("STATUS?") }
        refrescar()
    }

    private fun conectar() {
        isGateway = b.rbGateway.isChecked
        simHardware = b.swSimulador.isChecked
        val ip = b.etIp.text.toString().trim()
        val pin = b.etPin.text.toString()
        if (pin.length < 4) {
            toast("PIN mínimo 4 dígitos")
            return
        }
        if (!WifiLink.bindWifi(this)) {
            toast("Conéctate a una red Wi-Fi primero")
            return
        }
        b.tvIpLocal.text = "IP local: ${WifiLink.localIp() ?: "sin Wi-Fi"}"

        if (isGateway) {
            link.startServer(WifiLink.PORT_REMOTE, pin)
            if (simHardware) {
                onState("Gateway + ESP32 Simulado activo (${WifiLink.localIp()})")
            } else {
                link.connectUp(ip, WifiLink.PORT_ESP, null)
            }
        } else {
            link.connectUp(ip, WifiLink.PORT_REMOTE, pin)
        }
    }

    private fun dosificar() {
        val ms = b.seekT.progress + Validator.MIN_MS
        val frame = Validator.feedFrame(ms) ?: run {
            toast("Tiempo fuera de rango")
            return
        }
        pendingMs = ms
        enviarComando(frame)
    }

    private fun enviarComando(frame: String) {
        if (isGateway && simHardware) {
            procesarEnSimulador(frame)
        } else {
            link.sendUp(frame)
        }
    }

    private fun procesarEnSimulador(frame: String) {
        val ms = Validator.parseFeed(frame)
        when {
            ms != null -> {
                pendingMs = ms
                val consumo = (ms / 400).coerceAtLeast(1)
                simNivelPct = (simNivelPct - consumo).coerceAtLeast(0)
                onLine("OK FEED", "UP")
                emitirNivelSimulado()
            }
            frame == "STATUS?" -> emitirNivelSimulado()
            else -> onLine("ERR", "UP")
        }
    }

    private fun emitirNivelSimulado() {
        val distCm = String.format(Locale.US, "%.1f", 2.0 + (100 - simNivelPct) * 0.22)
        onLine("STATUS:LEVEL:P=$simNivelPct;D=$distCm", "UP")
    }

    override fun onLine(line: String, from: String) {
        if (isGateway && from == "REMOTE") {
            val ms = Validator.parseFeed(line)
            when {
                ms != null -> {
                    pendingMs = ms
                    if (simHardware) procesarEnSimulador(line) else link.sendUp(line)
                }
                line == "STATUS?" || line == "TIME?" -> {
                    if (simHardware) procesarEnSimulador(line) else link.sendUp(line)
                }
                else -> link.broadcast("ERR")
            }
            return
        }
        if (isGateway) link.broadcast(line)
        val origen = when {
            isGateway && simHardware -> "SIM-ESP32"
            isGateway -> "ESP32"
            else -> "GATEWAY"
        }
        when (val m = ProtocolParser.parse(line)) {
            is Msg.Level -> {
                db.guardarNivel(user, m.pct, m.dist, origen)
                runOnUiThread {
                    b.pbNivel.progress = m.pct
                    b.tvNivel.text = "Nivel: ${m.pct}%  (${m.dist} cm)"
                    refrescar()
                }
            }
            Msg.OkFeed -> {
                db.guardarDosis(user, pendingMs, origen)
                runOnUiThread {
                    toast("Dosis OK")
                    refrescar()
                }
            }
            Msg.Err -> runOnUiThread { toast("El dispositivo respondió ERR") }
            is Msg.Other -> {}
        }
    }

    override fun onState(msg: String) {
        runOnUiThread { b.tvEstado.text = "Estado: $msg" }
    }

    private fun refrescar() = adapter.submit(db.historial())
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        link.close()
        super.onDestroy()
    }
}

class HistorialAdapter : RecyclerView.Adapter<HistorialAdapter.VH>() {
    private var items = listOf<Registro>()
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    fun submit(l: List<Registro>) {
        items = l
        notifyDataSetChanged()
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
        LayoutInflater.from(p.context)
            .inflate(android.R.layout.simple_list_item_1, p, false) as TextView
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val r = items[i]
        val d = if (r.tipo == "DOSIS") "Dosis ${r.tiempoMs} ms" else "Nivel ${r.nivelPct}% (${r.distanciaCm} cm)"
        h.tv.text = "${fmt.format(Date(r.timestamp))}  $d  [${r.origen}]"
    }
}