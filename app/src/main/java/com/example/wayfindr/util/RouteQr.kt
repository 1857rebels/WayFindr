package com.example.wayfindr.util

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.example.wayfindr.data.SavedLocation
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/**
 * A saved destination as a QR code of its [RouteShare] wayfindr:// link. Scanning it with the
 * phone's own camera offers to open Wayfindr (checked with a stock camera app), which
 * loads every stop onto the Route screen. Generating and reading it needs no internet.
 */
object RouteQr {
    /** QR modules for [text] with no quiet zone; null when it's too long for any QR version. */
    fun matrix(text: String): BitMatrix? {
        // Medium error correction scans better off a screen; drop to Low only for very long routes.
        for (level in listOf(ErrorCorrectionLevel.M, ErrorCorrectionLevel.L)) {
            try {
                return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
                    EncodeHintType.ERROR_CORRECTION to level,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.MARGIN to 0,
                ))
            } catch (_: WriterException) { }
        }
        return null
    }

    /** White card: destination name on top, stop count, then the QR. Null if the route won't fit a QR. */
    fun image(loc: SavedLocation): Bitmap? {
        val stops = RouteShare.stopsOf(loc)
        val m = matrix(RouteShare.link(loc.name, stops)) ?: return null
        val width = 1080
        val pad = 72
        // Whole pixels per module keeps edges crisp; the QR spec wants ≥4 white modules around it.
        val module = (width - 32) / (m.width + 8)
        val qrPx = module * m.width
        val quiet = 4 * module

        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 64f; typeface = Typeface.DEFAULT_BOLD
        }
        val titleLayout = StaticLayout.Builder.obtain(loc.name, 0, loc.name.length, title, width - 2 * pad)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END).build()
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x55, 0x5B, 0x60); textSize = 36f; textAlign = Paint.Align.CENTER
        }
        val subText = (if (stops.size == 1) "1 stop" else "${stops.size} stops") + " · scan to open in Wayfindr"

        val top = pad + titleLayout.height + 20 + 44
        val height = top + quiet + qrPx + quiet
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.save(); c.translate(pad.toFloat(), pad.toFloat()); titleLayout.draw(c); c.restore()
        c.drawText(subText, width / 2f, (pad + titleLayout.height + 20 + 36).toFloat(), sub)

        val black = Paint().apply { color = Color.BLACK }
        val left = (width - qrPx) / 2f
        val qrTop = (top + quiet).toFloat()
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) c.drawRect(left + x * module, qrTop + y * module,
                left + (x + 1) * module, qrTop + (y + 1) * module, black)
        }
        return bmp
    }

    /** Hand the QR image to a messaging app via the share sheet. */
    fun shareImage(context: Context, bmp: Bitmap, name: String) {
        val dir = File(context.cacheDir, "route_qr").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }           // only the latest share is kept
        val file = File(dir, fileName(name))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Wayfindr route: $name")
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share $name")
            // Sharing to our own app would just reload the same route.
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context, com.example.wayfindr.MainActivity::class.java)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Save the QR image to Pictures/Wayfindr (no storage permission needed on API 29+). */
    fun saveToGallery(context: Context, bmp: Bitmap, name: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(name))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Wayfindr")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        }
        true
    }.getOrDefault(false)

    /**
     * Read a QR code's text from an image (e.g. a route QR saved to the gallery or a screenshot).
     * Offline; null when no QR is found. Call off the main thread.
     */
    fun read(context: Context, uri: android.net.Uri): String? = runCatching {
        // Downsample big photos: decoding a 50 MP image is slow and a QR needs far fewer pixels.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)!!.use {
            android.graphics.BitmapFactory.decodeStream(it, null,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }!!
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        decode(com.google.zxing.RGBLuminanceSource(bmp.width, bmp.height, px))
    }.getOrNull()

    /** Find a QR in [source]: the local-contrast binarizer first, then the global one. */
    fun decode(source: com.google.zxing.LuminanceSource): String? {
        val hints = mapOf(
            com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            com.google.zxing.DecodeHintType.TRY_HARDER to true,
            com.google.zxing.DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        for (binarizer in listOf(com.google.zxing.common.HybridBinarizer(source),
                com.google.zxing.common.GlobalHistogramBinarizer(source))) {
            runCatching {
                return com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(binarizer), hints).text
            }
        }
        return null
    }

    private fun fileName(name: String) =
        "Wayfindr - " + name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40).ifBlank { "route" } + ".png"
}
