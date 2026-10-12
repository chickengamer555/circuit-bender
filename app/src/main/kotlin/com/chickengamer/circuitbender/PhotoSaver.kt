package com.chickengamer.circuitbender

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.chickengamer.circuitbender.core.Photo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Writes a captured frame to Pictures/CircuitBender as a lossless PNG.
 * Runs on a background thread; returns null on any failure rather than
 * throwing, and never leaves a half-written entry in the gallery.
 */
object PhotoSaver {
    private const val TAG = "PhotoSaver"

    fun save(context: Context, photo: Photo): Uri? {
        val bmp = Bitmap.createBitmap(photo.pixels, photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val name = "CB_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(context, bmp, name) else saveLegacy(context, bmp, name)
        } catch (e: IOException) {
            Log.e(TAG, "saving $name failed", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "no permission to save $name", e)
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun saveMediaStore(context: Context, bmp: Bitmap, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CircuitBender")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        var written = false
        try {
            written = resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        } finally {
            if (written) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
        }
        return if (written) uri else null
    }

    /** Android 8-9: write the file directly (needs WRITE_EXTERNAL_STORAGE), then index it. */
    private fun saveLegacy(context: Context, bmp: Bitmap, name: String): Uri? {
        @Suppress("DEPRECATION") // the only public API for shared pictures before Q
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CircuitBender")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val file = File(dir, name)
        val ok = FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!ok) {
            file.delete()
            return null
        }
        // Ask the media scanner for a content:// uri so the gallery can open it.
        var uri: Uri? = null
        val done = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png")) { _, scanned ->
            uri = scanned
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        return uri ?: Uri.fromFile(file)
    }
}
