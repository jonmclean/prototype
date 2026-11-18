package org.encryptedspaces.android

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.IOException

internal class MemoryMediaDataSource(private var bytes: ByteArray) : MediaDataSource() {
    private var closed = false

    @Synchronized
    override fun getSize(): Long {
        checkOpen()
        return bytes.size.toLong()
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        checkOpen()
        require(position >= 0)
        require(offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(size.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    @Synchronized
    override fun close() {
        bytes.fill(0)
        bytes = ByteArray(0)
        closed = true
    }

    private fun checkOpen() {
        if (closed) throw IOException("Video source is closed")
    }
}

internal class VideoPreview(
    context: Context,
    bytes: ByteArray,
    private val onError: (Exception) -> Unit
) : LinearLayout(context), SurfaceHolder.Callback {
    private val source = MemoryMediaDataSource(bytes)
    private val player = MediaPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val surface = SurfaceView(context).apply { setSecure(AppConfig.blockScreenCapture(context)) }
    private val message = TextView(context).apply {
        setText(R.string.video_loading)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }
    private val picture = object : FrameLayout(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            resizeVideo()
        }
    }
    private val play = Button(context).apply {
        setText(R.string.video_play)
        isAllCaps = false
        isEnabled = false
    }
    private val seek = SeekBar(context).apply {
        contentDescription = context.getString(R.string.video_position)
        isEnabled = false
    }
    private var prepared = false
    private var released = false
    private var preparing = false
    private val updatePosition = object : Runnable {
        override fun run() {
            if (!prepared || released) return
            if (!seek.isPressed) seek.progress = player.currentPosition
            if (player.isPlaying) handler.postDelayed(this, 500)
        }
    }

    init {
        orientation = VERTICAL
        picture.setBackgroundColor(Color.BLACK)
        picture.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        picture.addView(message, FrameLayout.LayoutParams(-1, -1))
        addView(picture, LayoutParams(-1, 0, 1f))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(play, LayoutParams(-2, -2))
            addView(seek, LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, -2))
        play.setOnClickListener {
            if (player.isPlaying) pause() else {
                player.start()
                play.setText(R.string.video_pause)
                handler.post(updatePosition)
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && prepared) player.seekTo(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        player.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        player.setOnPreparedListener {
            if (!released) {
                prepared = true
                message.visibility = GONE
                play.isEnabled = true
                seek.isEnabled = true
                seek.max = player.duration.coerceAtLeast(0)
                resizeVideo()
            }
        }
        player.setOnVideoSizeChangedListener { _, _, _ -> resizeVideo() }
        player.setOnCompletionListener {
            play.setText(R.string.video_play)
            seek.progress = seek.max
            handler.removeCallbacks(updatePosition)
        }
        player.setOnErrorListener { _, what, extra ->
            fail(IllegalStateException(context.getString(R.string.video_decode_error, what, extra)))
            true
        }
        surface.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        try {
            player.setDisplay(holder)
            if (!preparing) {
                preparing = true
                player.setDataSource(source)
                player.prepareAsync()
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
        if (!released) player.setDisplay(null)
    }

    fun pause() {
        handler.removeCallbacks(updatePosition)
        if (prepared && !released) {
            player.pause()
            play.setText(R.string.video_play)
        }
    }

    fun release() {
        if (released) return
        released = true
        prepared = false
        handler.removeCallbacks(updatePosition)
        play.isEnabled = false
        seek.isEnabled = false
        try {
            player.release()
        } finally {
            source.close()
        }
    }

    private fun fail(error: Exception) {
        release()
        message.setText(R.string.invalid_video)
        message.visibility = VISIBLE
        onError(error)
    }

    private fun resizeVideo() {
        if (!prepared || released) return
        val width = player.videoWidth
        val height = player.videoHeight
        if (width <= 0 || height <= 0 || picture.width <= 0 || picture.height <= 0) return
        val scale = minOf(picture.width.toDouble() / width, picture.height.toDouble() / height)
        surface.layoutParams = FrameLayout.LayoutParams(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1), Gravity.CENTER)
    }
}
