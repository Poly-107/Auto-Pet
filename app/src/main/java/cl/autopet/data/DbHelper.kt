package cl.autopet.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Registro(
    val id: Long,
    val usuario: String,
    val tipo: String,
    val tiempoMs: Int?,
    val nivelPct: Int?,
    val distanciaCm: Double?,
    val origen: String,
    val timestamp: Long
)

class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "autopet.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE Usuarios(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                pass_hash TEXT NOT NULL,
                salt TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE HistorialTolva(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                usuario TEXT NOT NULL,
                tipo TEXT NOT NULL CHECK(tipo IN ('DOSIS','NIVEL')),
                tiempo_ms INTEGER,
                nivel_pct INTEGER CHECK(nivel_pct BETWEEN 0 AND 100),
                distancia_cm REAL,
                origen TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_hist_ts ON HistorialTolva(timestamp DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        db.execSQL("DROP TABLE IF EXISTS HistorialTolva")
        db.execSQL("DROP TABLE IF EXISTS Usuarios")
        onCreate(db)
    }

    fun registrar(username: String, password: CharArray): Boolean {
        val salt = PasswordHasher.newSalt()
        val cv = ContentValues().apply {
            put("username", username.trim())
            put("pass_hash", PasswordHasher.encode(PasswordHasher.hash(password, salt)))
            put("salt", PasswordHasher.encode(salt))
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("Usuarios", null, cv) != -1L
    }

    fun login(username: String, password: CharArray): Boolean {
        readableDatabase.query(
            "Usuarios", arrayOf("pass_hash", "salt"),
            "username = ?", arrayOf(username.trim()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return false
            return PasswordHasher.verify(password, c.getString(1), c.getString(0))
        }
    }

    fun guardarDosis(usuario: String, ms: Int, origen: String) =
        insertar(usuario, "DOSIS", ms, null, null, origen)

    fun guardarNivel(usuario: String, pct: Int, distCm: Double, origen: String) =
        insertar(usuario, "NIVEL", null, pct, distCm, origen)

    private fun insertar(u: String, tipo: String, t: Int?, p: Int?, d: Double?, origen: String) {
        val cv = ContentValues().apply {
            put("usuario", u)
            put("tipo", tipo)
            put("tiempo_ms", t)
            put("nivel_pct", p)
            put("distancia_cm", d)
            put("origen", origen)
            put("timestamp", System.currentTimeMillis())
        }
        writableDatabase.insert("HistorialTolva", null, cv)
    }

    fun historial(limite: Int = 100): List<Registro> {
        val out = mutableListOf<Registro>()
        readableDatabase.query(
            "HistorialTolva", null, null, null, null, null,
            "timestamp DESC", limite.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += Registro(
                    c.getLong(0),
                    c.getString(1),
                    c.getString(2),
                    if (c.isNull(3)) null else c.getInt(3),
                    if (c.isNull(4)) null else c.getInt(4),
                    if (c.isNull(5)) null else c.getDouble(5),
                    c.getString(6),
                    c.getLong(7)
                )
            }
        }
        return out
    }
}