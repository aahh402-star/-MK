package com.example.videograb

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MusicPlayerActivity : AppCompatActivity() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var txtSongTitle: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var songListView: ListView

    private val songList = mutableListOf<SongModel>()
    private var currentSongIndex = 0

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    data class SongModel(val title: String, val uri: Uri)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_player)

        txtSongTitle = findViewById(R.id.txtSongTitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        songListView = findViewById(R.id.songListView)

        // تهيئة المشغل ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build()

        // فحص الصلاحيات وطلبها لجلب الأغاني من الذاكرة
        checkAndRequestPermissions()

        // أزرار التحكم
        btnPlayPause.setOnClickListener {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
                btnPlayPause.text = "▶ تشغيل"
            } else {
                exoPlayer.play()
                btnPlayPause.text = "⏸ إيقاف مؤقت"
            }
        }

        btnNext.setOnClickListener {
            if (songList.isNotEmpty()) {
                currentSongIndex = (currentSongIndex + 1) % songList.size
                playSong(currentSongIndex)
            }
        }

        btnPrev.setOnClickListener {
            if (songList.isNotEmpty()) {
                currentSongIndex = if (currentSongIndex - 1 < 0) songList.size - 1 else currentSongIndex - 1
                playSong(currentSongIndex)
            }
        }

        // النقر على أي أغنية من القائمة لتشغيلها مباشرة
        songListView.setOnItemClickListener { _, _, position, _ ->
            currentSongIndex = position
            playSong(currentSongIndex)
        }
    }

    private fun checkAndRequestPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_CODE)
        } else {
            loadAudioFiles()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadAudioFiles()
            } else {
                Toast.makeText(this, "تم رفض الصلاحية، لا يمكن عرض الأغاني", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadAudioFiles() {
        songList.clear()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA
        )

        val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (it.moveToNext()) {
                val title = it.getString(titleColumn)
                val path = it.getString(dataColumn)
                val audioUri = Uri.parse(path)
                songList.add(SongModel(title, audioUri))
            }
        }

        val titles = songList.map { it.title }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
        songListView.adapter = adapter

        if (songList.isNotEmpty()) {
            Toast.makeText(this, "تم العثور على ${songList.size} مقطع صوتي", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "لم يتم العثور على ملفات صوتية في الذاكرة", Toast.LENGTH_LONG).show()
        }
    }

    private fun playSong(index: Int) {
        if (songList.isEmpty()) return
        val song = songList[index]
        txtSongTitle.text = song.title

        val mediaItem = MediaItem.fromUri(song.uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
        btnPlayPause.text = "⏸ إيقاف مؤقت"
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }
}
