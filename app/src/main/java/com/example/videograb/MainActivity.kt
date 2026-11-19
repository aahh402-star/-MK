package com.example.videograb

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

    private class Opt(val label: String, val value: Int)

    private lateinit var urlInput: EditText
    private lateinit var txtInfo: TextView
    private lateinit var chipsBox: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var progressCard: LinearLayout
    private lateinit var txtProgress: TextView
    private lateinit var progressBar: ProgressBar

    private val ui = Handler(Looper.getMainLooper())

    // الرابط -> (العنوان، قائمة الجودات)
    private val cache = HashMap<String, Pair<String, List<Int>>>()
    private val defaultHeights = listOf(1080, 720, 480, 360)
    private var options = listOf<Opt>()
    private var selected = 0
    private var lastUrl = ""
    private var curTitle = ""
    private var fetchGen = 0
    private var wasActive = false
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        txtInfo = findViewById(R.id.txtInfo)
        chipsBox = findViewById(R.id.chipsBox)
        btnStart = findViewById(R.id.btnStart)
        progressCard = findViewById(R.id.progressCard)
        txtProgress = findViewById(R.id.txtProgress)
        progressBar = findViewById(R.id.progressBar)

        styleUi()

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
            override fun afterTextChanged(s: Editable?) { onUrlChanged() }
        })

        handleShare(intent)

        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (text.isNullOrBlank()) toast("الحافظة فارغة") else urlInput.setText(text)
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.btnOpenMusicPlayer).setOnClickListener {
            startActivity(Intent(this, MusicPlayerActivity::class.java))
        }
        btnStart.setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            startService(
                Intent(this, DownloadService::class.java).putExtra("cmd", "cancel")
            )
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

    // ---------------- التصميم ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun styleButton(id: Int, color: String) {
        val b = findViewById<Button>(id)
        b.background = box(Color.parseColor(color), 20)
    }

    private fun styleUi() {
        findViewById<View>(R.id.header).background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#2563EB"), Color.parseColor("#7C3AED"))
        ).apply { cornerRadius = dp(28).toFloat() }

        findViewById<View>(R.id.card).background = box(Color.WHITE, 28)
        progressCard.background = box(Color.WHITE, 28)
        urlInput.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#555555"))

        styleButton(R.id.btnPaste, "#0A84FF")
        styleButton(R.id.btnHistory, "#64748B")
        styleButton(R.id.btnOpenMusicPlayer, "#F59E0B")
        styleButton(R.id.btnStart, "#22C55E")
        styleButton(R.id.btnCancel, "#EF4444")

        val teal = ColorStateList.valueOf(Color.parseColor("#14B8A6"))
        progressBar.progressTintList = teal
        progressBar.indeterminateTintList = teal
        progressBar.progressBackgroundTintList =
            ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
    }

    // ---------------- الرابط والجودات ----------------

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { urlInput.setText(it) }
        }
    }

    private fun currentUrl(): String =
        Regex("https?://\\S+").find(urlInput.text.toString())?.value ?: ""

    private fun onUrlChanged() {
        searchRunnable?.let { ui.removeCallbacks(it) }
        val url = currentUrl()

        if (url.isEmpty()) {
            fetchGen++
            lastUrl = ""
            curTitle = ""
            txtInfo.visibility = View.GONE
            chipsBox.visibility = View.GONE
            btnStart.visibility = View.GONE
            return
        }

        txtInfo.visibility = View.VISIBLE
        chipsBox.visibility = View.VISIBLE
        btnStart.visibility = View.VISIBLE
        if (url == lastUrl) return
        lastUrl = url
        curTitle = ""

        val cached = cache[url]
        if (cached != null) {
            curTitle = cached.first
            txtInfo.text = if (cached.first.isNotEmpty()) cached.first else "جاهز للتنزيل"
            setOptions(if (cached.second.isEmpty()) defaultHeights else cached.second, false)
            return
        }

        txtInfo.text = "جاري جلب معلومات الفيديو..."
        setOptions(defaultHeights, false)
        val r = Runnable { fetchFormats(url) }
        searchRunnable = r
        ui.postDelayed(r, 300)
    }

    private fun fetchFormats(url: String) {
        val gen = ++fetchGen
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
                if (gen != fetchGen || url != lastUrl) return@post
                if (result == null) {
                    txtInfo.text = "تعذر جلب الجودات، تقدر تنزل بالخيارات الأساسية"
                    return@post
                }
                cache[url] = result
                curTitle = result.first
                txtInfo.text = if (result.first.isNotEmpty()) result.first else "جاهز للتنزيل"
                if (result.second.isNotEmpty()) setOptions(result.second, true)
            }
        }
    }

    private fun setOptions(heights: List<Int>, keep: Boolean) {
        val prev = options.getOrNull(selected)?.value
        val list = ArrayList<Opt>()
        list.add(Opt("أفضل جودة", 0))
        heights.forEach { list.add(Opt("${it}p", it)) }
        list.add(Opt("🎵 صوت MP3", -1))
        list.add(Opt("🎵 صوت M4A", -2))
        options = list
        selected = if (keep && prev != null) {
            val idx = list.indexOfFirst { it.value == prev }
            if (idx >= 0) idx else 0
        } else 0
        renderChips()
    }

    private fun renderChips() {
        chipsBox.removeAllViews()
        var row: LinearLayout? = null
        for (idx in options.indices) {
            if (idx % 2 == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                chipsBox.addView(
                    r,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                row = r
            }
            val on = idx == selected
            val tv = TextView(this)
            tv.text = options[idx].label
            tv.gravity = Gravity.CENTER
            tv.textSize = 15f
            tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#64748B"))
            tv.background = box(Color.parseColor(if (on) "#0A84FF" else "#EEF2F7"), 16)
            tv.setOnClickListener {
                selected = idx
                renderChips()
            }
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            row?.addView(tv, lp)
        }
    }

    // ---------------- التنزيل ----------------

    private fun onStartClicked() {
        val url = currentUrl()
        if (url.isEmpty()) {
            toast("الصق الرابط أول")
            return
        }
        val opt = options.getOrNull(selected) ?: return
        val i = Intent(this, DownloadService::class.java)
            .putExtra("url", url)
            .putExtra("q", opt.value)
            .putExtra("pl", false)
            .putExtra("title", curTitle)
        ContextCompat.startForegroundService(this, i)
        toast("انضاف للتنزيل ✅ تكدر تغلق التطبيق")
        urlInput.setText("")
    }

    private fun refreshBar() {
        if (Hub.active) {
            progressCard.visibility = View.VISIBLE
            progressBar.isIndeterminate = Hub.indeterminate
            if (!Hub.indeterminate) progressBar.progress = Hub.progress
            txtProgress.text = Hub.text
        } else {
            progressCard.visibility = View.GONE
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