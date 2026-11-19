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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var txtProgress: TextView
    private val ui = Handler(Looper.getMainLooper())

    // الرابط -> (العنوان، قائمة الجودات)
    private val cache = HashMap<String, Pair<String, List<Int>>>()
    private val standardHeights = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
    private var fetching = false
    private var pendingDialog = false
    private var fetchGen = 0
    private var wasActive = false
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        progress = findViewById(R.id.progressBar)
        txtProgress = findViewById(R.id.txtProgress)

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        // تجهيز وتحديث المحرك بالخلفية من أول ما يفتح التطبيق
        thread { try { Engine.ensureUpdated(application) } catch (e: Exception) { } }

        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { scheduleSearch() }
        })

        handleShare(intent)

        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (text.isNullOrBlank()) toast("الحافظة فارغة") else urlInput.setText(text)
        }
        findViewById<Button>(R.id.btnDownload).setOnClickListener { onDownloadClicked() }
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
        wasActive = Hub.active
        Hub.listener = {
            ui.post {
                refreshBar()
                if (wasActive && !Hub.active && Hub.text.isNotEmpty()) {
                    Toast.makeText(this, Hub.text, Toast.LENGTH_LONG).show()
                }
                wasActive = Hub.active
            }
        }
        refreshBar()
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

    private fun currentUrl(): String =
        Regex("https?://\\S+").find(urlInput.text.toString())?.value ?: ""

    // يبدأ البحث عن الجودات تلقائياً أول ما ينلصق الرابط
    private fun scheduleSearch() {
        searchRunnable?.let { ui.removeCallbacks(it) }
        val url = currentUrl()
        if (url.isEmpty()) {
            fetchGen++
            fetching = false
            pendingDialog = false
            refreshBar()
            return
        }
        if (cache.containsKey(url)) return
        val r = Runnable { fetchFormats(url) }
        searchRunnable = r
        ui.postDelayed(r, 300)
    }

    private fun fetchFormats(url: String) {
        val gen = ++fetchGen
        fetching = true
        refreshBar()
        thread {
            var res: Pair<String, List<Int>>? = null
            try {
                Engine.ensureUpdated(application)
                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                req.addOption("--socket-timeout", "15")
                val info = YoutubeDL.getInstance().getInfo(req)
                val heights = (info.formats ?: emptyList())
                    .mapNotNull { f -> f?.height }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()
                res = Pair(info.title ?: "", heights)
            } catch (e: Exception) { }
            val result = res
            ui.post {
                if (gen != fetchGen) return@post
                fetching = false
                if (result != null) cache[url] = result
                refreshBar()
                if (pendingDialog) {
                    pendingDialog = false
                    showOptions(url)
                }
            }
        }
    }

    private fun onDownloadClicked() {
        val url = currentUrl()
        if (url.isEmpty()) {
            toast("الصق الرابط أول")
            return
        }
        searchRunnable?.let { ui.removeCallbacks(it) }
        when {
            cache.containsKey(url) -> showOptions(url)
            fetching -> {
                // ننتظر 3 ثواني كحد أقصى، وبعدها تظهر القائمة القياسية
                pendingDialog = true
                ui.postDelayed({
                    if (pendingDialog) {
                        pendingDialog = false
                        showOptions(url)
                    }
                }, 3000)
            }
            else -> {
                showOptions(url)
                fetchFormats(url)
            }
        }
    }

    // قائمة وحدة فيها كل الجودات + الصوت
    private fun showOptions(url: String) {
        val data = cache[url]
        val found = data?.second ?: emptyList()
        val heights = if (found.isEmpty()) standardHeights else found
        val title = data?.first ?: ""

        val labels = ArrayList<String>()
        val values = ArrayList<Int>()
        labels.add("🎬 فيديو - أعلى جودة"); values.add(0)
        heights.forEach { labels.add("🎬 فيديو - ${it}p"); values.add(it) }
        labels.add("🎵 صوت MP3"); values.add(-1)
        labels.add("🎵 صوت MP4 (M4A)"); values.add(-2)

        AlertDialog.Builder(this)
            .setTitle("اختر ما تريد تنزيله")
            .setItems(labels.toTypedArray()) { _, i -> startDownload(url, values[i], title) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun startDownload(url: String, q: Int, title: String) {
        val i = Intent(this, DownloadService::class.java)
            .putExtra("url", url)
            .putExtra("q", q)
            .putExtra("pl", false)
            .putExtra("title", title)
        ContextCompat.startForegroundService(this, i)
    }

    private fun refreshBar() {
        if (Hub.active) {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = Hub.indeterminate
            if (!Hub.indeterminate) progress.progress = Hub.progress
            txtProgress.visibility = View.VISIBLE
            txtProgress.text = Hub.text.replace("\n", " • ")
        } else if (fetching) {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            txtProgress.visibility = View.GONE
        } else {
            progress.visibility = View.GONE
            txtProgress.visibility = View.GONE
        }
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