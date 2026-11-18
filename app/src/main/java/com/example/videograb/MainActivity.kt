package com.example.videograb

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var txtStatus: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        txtStatus = findViewById(R.id.txtStatus)

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        handleShare(intent)

        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (text.isNullOrBlank()) toast("الحافظة فارغة") else urlInput.setText(text)
        }
        findViewById<Button>(R.id.btnVideo).setOnClickListener { startDownload(0) }
        findViewById<Button>(R.id.btnMp3).setOnClickListener { startDownload(-1) }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.btnOpenMusicPlayer).setOnClickListener {
            startActivity(Intent(this, MusicPlayerActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Hub.listener = { ui.post { txtStatus.text = Hub.text } }
        txtStatus.text = Hub.text
    }

    override fun onPause() {
        super.onPause()
        Hub.listener = null
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { urlInput.setText(it) }
        }
    }

    private fun startDownload(q: Int) {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            toast("الصق الرابط أول")
            return
        }
        val i = Intent(this, DownloadService::class.java)
            .putExtra("url", url)
            .putExtra("q", q)
            .putExtra("pl", false)
            .putExtra("title", "")
        ContextCompat.startForegroundService(this, i)
        toast("بدأ التنزيل")
    }

    private fun showHistory() {
        val items = Store.all(this)
        if (items.isEmpty()) {
            toast("السجل فارغ")
            return
        }
        val names = items.map { it.optString("n") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("السجل")
            .setItems(names) { _, idx ->
                val o = items[idx]
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(o.optString("u")), o.optString("m"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(view) } catch (e: Exception) { toast("ما أقدر أفتح الملف") }
            }
            .setNeutralButton("مسح السجل") { _, _ -> Store.clear(this) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
