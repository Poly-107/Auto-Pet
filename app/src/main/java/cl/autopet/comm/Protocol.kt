package cl.autopet.comm

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed class Msg {
    data class Level(val pct: Int, val dist: Double) : Msg()
    object OkFeed : Msg()
    object Err : Msg()
    data class Other(val raw: String) : Msg()
}

object ProtocolParser {
    private val LEVEL = Regex("""^STATUS:LEVEL:P=(\d{1,3});D=(\d{1,3}(?:\.\d+)?)$""")

    fun parse(line: String): Msg {
        LEVEL.matchEntire(line)?.let {
            val p = it.groupValues[1].toInt()
            if (p in 0..100) return Msg.Level(p, it.groupValues[2].toDouble())
        }
        return when (line) {
            "OK FEED" -> Msg.OkFeed
            "ERR" -> Msg.Err
            else -> Msg.Other(line)
        }
    }
}

object Validator {
    const val MIN_MS = 200
    const val MAX_MS = 3000
    private val FEED = Regex("""^FEED:T=(\d{1,4})$""")

    fun feedFrame(ms: Int): String? =
        if (ms in MIN_MS..MAX_MS) "FEED:T=$ms" else null

    fun parseFeed(line: String): Int? =
        FEED.matchEntire(line)?.groupValues?.get(1)?.toInt()?.takeIf { it in MIN_MS..MAX_MS }
}

object FrameSigner {
    private fun hmacTag(payload: String, pin: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun sign(payload: String, pin: String): String = "$payload*${hmacTag(payload, pin)}"

    fun verifyAndExtract(signedLine: String, pin: String): String? {
        val idx = signedLine.lastIndexOf('*')
        if (idx <= 0 || idx == signedLine.length - 1) return null
        val payload = signedLine.substring(0, idx)
        val tag = signedLine.substring(idx + 1)
        val expected = hmacTag(payload, pin)
        return if (MessageDigest.isEqual(tag.toByteArray(), expected.toByteArray())) payload else null
    }
}