package cl.autopet.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cl.autopet.data.DbHelper
import cl.autopet.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding
    private lateinit var db: DbHelper
    private var fails = 0
    private var lockUntil = 0L
    private val userRx = Regex("^[A-Za-z0-9_]{3,20}$")

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = DbHelper(this)

        b.btnLogin.setOnClickListener {
            if (System.currentTimeMillis() < lockUntil) {
                toast("Bloqueado 30 s por intentos fallidos")
                return@setOnClickListener
            }
            val u = b.etUser.text.toString().trim()
            val p = b.etPass.text.toString().toCharArray()
            if (db.login(u, p)) {
                fails = 0
                startActivity(Intent(this, MainActivity::class.java).putExtra("USER", u))
                finish()
            } else {
                if (++fails >= 5) {
                    lockUntil = System.currentTimeMillis() + 30_000
                    fails = 0
                }
                toast("Credenciales inválidas")
            }
            p.fill('0')
        }

        b.btnRegistrar.setOnClickListener {
            val u = b.etUser.text.toString().trim()
            val p = b.etPass.text.toString().toCharArray()
            when {
                !userRx.matches(u) -> toast("Usuario: 3-20 caracteres (letras, números, _)")
                p.size < 8 || p.none { it.isDigit() } || p.none { it.isLetter() } ->
                    toast("Contraseña: mínimo 8 caracteres con letras y números")
                db.registrar(u, p) -> toast("Usuario creado. Ya puedes ingresar")
                else -> toast("El usuario ya existe")
            }
            p.fill('0')
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}