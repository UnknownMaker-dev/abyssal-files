package com.abyssal.files

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Built-in player: plays a queue (the audio files of the folder it was started from). */
class AudioPlayer(private val scope: CoroutineScope) {
    var queue by mutableStateOf<List<String>>(emptyList()); private set
    var index by mutableIntStateOf(-1); private set
    var playing by mutableStateOf(false); private set
    var position by mutableIntStateOf(0); private set
    var duration by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null)

    private var mp: MediaPlayer? = null
    private var prepared = false
    /** Streams (http links from other apps) take a moment to start. */
    var loading by mutableStateOf(false); private set
    private var ticker: Job? = null

    val current: File? get() = queue.getOrNull(index)?.let(::File)

    fun play(files: List<String>, start: Int) {
        if (start !in files.indices) return
        queue = files
        load(start)
    }

    private fun load(i: Int) {
        release()
        index = i
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(queue[i])
            p.setOnCompletionListener {
                if (index < queue.lastIndex) load(index + 1) else { pause(); seek(0) }
            }
            p.setOnPreparedListener {
                if (mp !== it) return@setOnPreparedListener
                prepared = true; loading = false
                it.start()
                duration = it.duration
                playing = true
                ticker = scope.launch {
                    while (isActive) {
                        mp?.takeIf { prepared }?.let { m -> position = m.currentPosition }
                        delay(250)
                    }
                }
            }
            p.setOnErrorListener { mp, _, _ ->
                if (this.mp === mp) { release(); error = "Can't play ${File(queue[i]).name}" }
                true
            }
            mp = p
            loading = true
            p.prepareAsync()
        } catch (e: Exception) {
            p.release()
            if (mp === p) mp = null
            loading = false
            error = "Can't play ${File(queue[i]).name}"
        }
    }

    fun toggle() {
        val p = mp ?: run { if (index in queue.indices) load(index); return }
        if (!prepared) return
        if (p.isPlaying) pause() else { p.start(); playing = true }
    }

    fun pause() {
        mp?.takeIf { prepared && it.isPlaying }?.pause()
        playing = false
    }

    fun seek(ms: Int) {
        mp?.takeIf { prepared }?.seekTo(ms)
        position = ms
    }

    fun next() { if (index < queue.lastIndex) load(index + 1) }

    fun prev() { if (position > 3000 || index == 0) seek(0) else load(index - 1) }

    fun stop() {
        release()
        queue = emptyList()
        index = -1
    }

    fun release() {
        ticker?.cancel()
        mp?.release()
        mp = null
        prepared = false
        loading = false
        playing = false
        position = 0
        duration = 0
    }
}
