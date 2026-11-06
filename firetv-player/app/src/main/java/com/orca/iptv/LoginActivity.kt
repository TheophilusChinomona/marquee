package com.orca.iptv

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var status: TextView
    private lateinit var connect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        server = findViewById(R.id.server)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.password)
        status = findViewById(R.id.status)
        connect = findViewById(R.id.connect)

        Prefs.load(this)?.let {
            server.setText(it.server)
            user.setText(it.user)
            pass.setText(it.pass)
        }
        intent.getStringExtra("server")?.let { server.setText(it) }
        intent.getStringExtra("user")?.let { user.setText(it) }
        intent.getStringExtra("pass")?.let { pass.setText(it) }

        connect.setOnClickListener { doConnect() }
        if (Prefs.load(this) != null || intent.hasExtra("server")) doConnect()
    }

    private fun doConnect() {
        val s = server.text.toString().trim().trimEnd('/')
        val u = user.text.toString().trim()
        val p = pass.text.toString()
        if (s.isEmpty() || u.isEmpty()) {
            status.text = getString(R.string.login_failed)
            return
        }

        connect.isEnabled = false
        status.text = getString(R.string.connecting)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { XtreamClient(s, u, p).accountInfo() }.isSuccess
            }
            if (ok) {
                Prefs.save(this@LoginActivity, Creds(s, u, p))
                startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                finish()
            } else {
                status.text = getString(R.string.login_failed)
                connect.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
