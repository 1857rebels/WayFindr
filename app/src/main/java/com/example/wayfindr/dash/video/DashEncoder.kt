package com.example.wayfindr.dash.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * MediaCodec H.264 encoder for the Console Dash stream:
 *   526 × 300, Baseline L4.1, 1-second IDR interval.
 *
 * Cadence/bitrate are DRIVEN BY THE FRAME LOOP, not fixed here — see DashViewModel's motion-adaptive
 * pacing. This mirrors what the dash actually does (confirmed by measuring
 * the dash app, 2026-06): it projects the map at only **2–4 fps / 100–200 kbps**, with
 * two profiles it switches between. A map doesn't need video frame rates, and crucially a few fps
 * NEVER overruns the dash decoder — which is the real cause of the blink/stutter we kept fighting at
 * 24 fps. So we match the dash'sproven-stable envelope: configure at the ACTIVE profile (4 fps hint,
 * ~200 kbps) and drop the bitrate live via [requestBitrate] when the map goes static. ([FPS] here is
 * only the encoder's frame-rate HINT; the real rate is the surface feed rate.)
 *
 * History (kept so we don't re-walk it): we'd climbed 4 → 12 → 24 fps and 0.8 → 1.2 Mbps chasing
 * "smoother", but measurement shows the dash deliberately stays LOW. Reverting to that envelope is the fix.
 *
 * Frames are drawn with Android Canvas via the input Surface's hardware
 * canvas — call [renderFrame] with a draw lambda, then [drain] to pull
 * encoded NAL data out.
 *
 * @param onEncodedData  called with (annexBBytes, isKeyFrame) for each output buffer.
 */
class DashEncoder(private val onEncodedData: (ByteArray, Boolean) -> Unit) {
    companion object {
        const val WIDTH   = 526
        const val HEIGHT  = 300
        // Encoder frame-rate HINT only (actual rate = how fast the loop feeds the surface). the dash'shigh
        // profile is 4 fps; we configure to match. The loop paces 4 fps (active) / 2 fps (idle).
        const val FPS     = 4
        // ~200 kbps = the dash'shigh-profile bitrate (the q2g profile = 204800). The loop drops this to
        // ~100 kbps (CR low profile, q2g = 102400) via requestBitrate() when the map is static.
        // Far under the old 1.2 Mbps that risked overrunning the dash decoder.
        const val BITRATE = 200_000
        const val BITRATE_IDLE = 100_000
        // "Higher video quality" setting: 2× the bitrate, same 4 fps. Less blocky after a zoom or
        // rotation; the risk is overrunning the dash decoder (blink/stutter) — test on the bike.
        const val BITRATE_HQ = 400_000
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FIRST_FRAME_TIMEOUT_US = 40_000L
        private const val TAG = "DashEncoder"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    fun prepare() {
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        // Prefer an explicit HARDWARE AVC encoder — a software fallback runs the
        // CPU hot, which is exactly what this whole project exists to avoid.
        val name = selectHardwareEncoder(format)
        // Constant bitrate: the default (VBR) let a full-picture change after a zoom come out at
        // 60–130 KB in one P-frame (2026-10-16 ride log) against a ~6 KB/frame budget, and the
        // dash stalled. CBR holds each frame near budget (softer for a moment, then sharpens).
        val cbrOk = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }
                ?.getCapabilitiesForType(MIME)?.encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        }.getOrDefault(false)
        if (cbrOk) format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        Log.i(TAG, "Bitrate mode: ${if (cbrOk) "CBR" else "encoder default"}")
        codec = (if (name != null) MediaCodec.createByCodecName(name)
                 else MediaCodec.createEncoderByType(MIME)).also { c ->
            Log.i(TAG, "Encoder: ${c.name}")
            // Low-latency first: at 4 fps every frame the encoder holds back costs 250 ms, which is
            // what made joystick zoom take seconds to reach the dash. Fall back to the plain format
            // if this codec rejects the extra keys.
            val lowLatency = MediaFormat(format).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)                   // realtime
                setInteger("vendor.qti-ext-enc-low-latency.enable", 1)    // Qualcomm (S25 Ultra, Snapdragon 8 Elite)
            }
            try {
                c.configure(lowLatency, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "Configured low-latency")
            } catch (e: Exception) {
                Log.w(TAG, "Low-latency configure rejected (${e.message}) — using default")
                c.reset()
                // Plain format, and without CBR in case that's what the codec refused.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) format.removeKey(MediaFormat.KEY_BITRATE_MODE)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = c.createInputSurface()
            c.start()
        }
    }

    /** Find a hardware AVC encoder that supports this format; null → let the OS pick. */
    private fun selectHardwareEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // First try true hardware-accelerated encoders (API 29+ flag).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, true) }) continue
                if (info.isHardwareAccelerated && !info.isSoftwareOnly) {
                    return info.name
                }
            }
        }
        // Fallback: the OS-preferred encoder for this format.
        return runCatching { list.findEncoderForFormat(format) }.getOrNull()
    }

    private val scalePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val dstRect = android.graphics.Rect(0, 0, WIDTH, HEIGHT)

    /** Draw [bmp] scaled (filtered) to exactly WIDTH×HEIGHT — used for the 2× "sharper map" frame. */
    fun renderBitmap(bmp: android.graphics.Bitmap) = renderFrame { canvas ->
        if (bmp.width == WIDTH && bmp.height == HEIGHT) canvas.drawBitmap(bmp, 0f, 0f, null)
        else canvas.drawBitmap(bmp, null, dstRect, scalePaint)
    }

    /** Draw one frame into the encoder via hardware canvas. */
    fun renderFrame(draw: (Canvas) -> Unit) {
        val surface = inputSurface ?: return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            return
        }
        try {
            draw(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Pull all available encoded buffers; call after every [renderFrame]. */
    fun drain() {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        // Give the frame just submitted a short window to come out THIS tick. With a 10 ms poll it
        // usually missed and rode out on the next tick, adding a whole 250 ms frame of lag.
        var gotFrame = false
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (gotFrame) 0L else FIRST_FRAME_TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit // SPS/PPS come as CODEC_CONFIG buffer
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx) ?: run {
                        codec.releaseOutputBuffer(idx, false); continue
                    }
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    // Pass EVERY buffer through, including CODEC_CONFIG (SPS/PPS) — the
                    // NAL processor needs the parameter sets to bundle them with each IDR,
                    // otherwise the dash can't initialise its decoder and times out.
                    if (info.size > 0) {
                        val data = ByteArray(info.size).also { buf.get(it) }
                        onEncodedData(data, isKey)
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) gotFrame = true
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    /**
     * Change the target bitrate live, without rebuilding the encoder — RE switches between its
     * ~200 kbps (moving) and ~100 kbps (static) profiles this way. MediaCodec applies
     * PARAMETER_KEY_VIDEO_BITRATE to the running session; no IDR or reconfigure needed.
     */
    fun requestBitrate(bps: Int) {
        val c = codec ?: return
        runCatching {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            })
        }.onFailure { Log.w(TAG, "requestBitrate($bps) failed: ${it.message}") }
    }

    /**
     * Ask for an IDR on the next frame. Used on joystick zoom: the whole picture changes, and a
     * self-contained keyframe lands crisp in one frame instead of the rate controller smearing a
     * huge P-frame across several low-quality frames (the "slow sharpen" after a zoom).
     */
    fun requestSyncFrame() {
        val c = codec ?: return
        runCatching {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { Log.w(TAG, "requestSyncFrame failed: ${it.message}") }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release(); inputSurface = null
    }
}
