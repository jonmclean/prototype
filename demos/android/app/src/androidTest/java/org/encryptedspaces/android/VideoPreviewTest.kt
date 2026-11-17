package org.encryptedspaces.android

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class VideoPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap {
            descendants(view.getChildAt(it))
        } else emptyList()

    private fun startActivity() = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as MainActivity

    private fun dialog(activity: MainActivity) =
        MainActivity::class.java.getDeclaredField("previewDialog")
            .apply { isAccessible = true }.get(activity) as AlertDialog

    private fun preview(activity: MainActivity, name: String, mime: String, bytes: ByteArray) {
        MainActivity::class.java.getDeclaredMethod(
            "preview", JSONObject::class.java, ByteArray::class.java
        ).apply { isAccessible = true }.invoke(activity,
            JSONObject().put("name", name).put("mime_type", mime), bytes)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for video playback")
    }

    // Generate and delete a synthetic fixture before checking playback for local file writes.
    private fun mp4(): ByteArray {
        val file = File.createTempFile("video-preview-", ".mp4", context.cacheDir)
        val encoder = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 64, 48).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            var frame = 0
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline) { "MP4 fixture encoding timed out" }
                if (frame <= 30) {
                    val input = encoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val eos = frame == 30
                        val size = if (eos) 0 else 64 * 48 * 3 / 2
                        encoder.getInputBuffer(input)!!.apply {
                            clear()
                            put(ByteArray(size))
                        }
                        encoder.queueInputBuffer(input, 0, size, frame * 100_000L,
                            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frame++
                    }
                }
                val output = encoder.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                } else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(track >= 0)
                        muxer.writeSampleData(track, encoder.getOutputBuffer(output)!!, info)
                    }
                    encoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            encoder.stop()
            muxer.stop()
            return file.readBytes()
        } finally {
            encoder.release()
            muxer.release()
            check(file.delete())
        }
    }

    @Test fun memorySourceSupportsRandomAccessEofAndClearsBytesOnClose() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = MemoryMediaDataSource(bytes)
        val output = ByteArray(6)
        assertEquals(4L, source.size)
        assertEquals(2, source.readAt(2, output, 1, 4))
        assertArrayEquals(byteArrayOf(0, 3, 4, 0, 0, 0), output)
        assertEquals(2, source.readAt(0, output, 0, 2))
        assertEquals(-1, source.readAt(4, output, 0, 1))
        assertEquals(-1, source.readAt(Long.MAX_VALUE, output, 0, 1))
        assertEquals(0, source.readAt(0, output, output.size, 0))
        source.close()
        source.close()
        assertTrue(bytes.all { it == 0.toByte() })
        assertThrows(IOException::class.java) { source.readAt(0, output, 0, 1) }
    }

    @Test fun mp4PlaysPausesSeeksAndClosesWithoutWritingPlaintextFiles() {
        val fixture = mp4()
        val activity = startActivity()
        val before = context.dataDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(context.dataDir).path }.toSet()
        try {
            for ((name, mime) in listOf(
                "clip.MP4" to "application/octet-stream",
                "video" to "VIDEO/MP4; codecs=avc1",
                "clip.mp4" to "",
                "video.md" to "video/mp4"
            )) {
                val bytes = fixture.copyOf()
                lateinit var video: VideoPreview
                lateinit var player: MediaPlayer
                lateinit var play: Button
                lateinit var seek: SeekBar
                var rendered = false
                instrumentation.runOnMainSync {
                    preview(activity, name, mime, bytes)
                    val dialog = dialog(activity)
                    assertTrue(dialog.window!!.attributes.flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertEquals(WindowManager.LayoutParams.MATCH_PARENT,
                        dialog.window!!.attributes.height)
                    val views = descendants(dialog.window!!.decorView)
                    video = views.filterIsInstance<VideoPreview>().single()
                    player = VideoPreview::class.java.getDeclaredField("player")
                        .apply { isAccessible = true }.get(video) as MediaPlayer
                    player.setOnInfoListener { _, what, _ ->
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered = true
                        false
                    }
                    play = views.filterIsInstance<Button>().single {
                        it.text == activity.getString(R.string.video_play)
                    }
                    seek = views.filterIsInstance<SeekBar>().single()
                    assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
                }
                await { play.isEnabled }
                instrumentation.runOnMainSync {
                    assertTrue(video.height > 100)
                    assertTrue(seek.max >= 2900)
                    play.performClick()
                    assertEquals(activity.getString(R.string.video_pause), play.text.toString())
                }
                await { rendered && player.currentPosition > 100 }
                instrumentation.runOnMainSync {
                    play.performClick()
                    assertFalse(player.isPlaying)
                    seek.keyProgressIncrement = 1000
                    seek.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                }
                await { player.currentPosition >= 900 }
                instrumentation.runOnMainSync {
                    play.performClick()
                    assertTrue(player.isPlaying)
                    MainActivity::class.java.getDeclaredMethod("onStop")
                        .apply { isAccessible = true }.invoke(activity)
                    assertFalse(player.isPlaying)
                    dialog(activity).dismiss()
                }
                instrumentation.waitForIdleSync()
                assertTrue(bytes.all { it == 0.toByte() })
            }
            val after = context.dataDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(context.dataDir).path }.toSet()
            assertEquals(before, after)
        } finally {
            fixture.fill(0)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun invalidMp4ReportsErrorAndReplacingVideoReleasesItsBuffer() {
        val fixture = mp4()
        val activity = startActivity()
        val invalid = byteArrayOf(1, 2, 3, 4)
        try {
            instrumentation.runOnMainSync { preview(activity, "bad.mp4", "", invalid) }
            await {
                descendants(dialog(activity).window!!.decorView).filterIsInstance<TextView>().any {
                    it.text == activity.getString(R.string.invalid_video)
                }
            }
            assertTrue(invalid.all { it == 0.toByte() })
            instrumentation.runOnMainSync {
                val status = MainActivity::class.java.getDeclaredField("status")
                    .apply { isAccessible = true }.get(activity) as TextView
                assertTrue(status.text.startsWith("Error:"))
                preview(activity, "clip.mp4", "", fixture)
            }
            await {
                descendants(dialog(activity).window!!.decorView).filterIsInstance<Button>().any {
                    it.text == activity.getString(R.string.video_play) && it.isEnabled
                }
            }
            instrumentation.runOnMainSync {
                preview(activity, "notes.txt", "text/plain", "Hello".toByteArray())
            }
            instrumentation.waitForIdleSync()
            assertTrue(fixture.all { it == 0.toByte() })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
