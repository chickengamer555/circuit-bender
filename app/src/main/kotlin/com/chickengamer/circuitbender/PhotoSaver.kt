package com.chickengamer.circuitbender

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.chickengamer.circuitbender.core.Photo
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Puts photos (lossless PNG) in Pictures/CircuitBender and videos in
 * Movies/CircuitBender. Runs on a background thread; returns null on any
 * failure and never leaves a half-written entry in the gallery.
 */
object PhotoSaver {
    private const val TAG = "PhotoSaver"

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun save(context: Context, photo: Photo): Uri? {
        val bmp = Bitmap.createBitmap(photo.pixels, photo.width, photo.height, Bitmap.Config.ARGB_8888)
        try {
            return publish(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "CB_${stamp()}.png", "image/png", Environment.DIRECTORY_PICTURES) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Moves a finished recording into the gallery and deletes the temp file. */
    fun saveVideo(context: Context, file: File): Uri? = try {
        publish(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "CB_${stamp()}.mp4", "video/mp4", Environment.DIRECTORY_MOVIES) { out ->
            file.inputStream().use { it.copyTo(out) }
            true
        }
    } finally {
        file.delete()
    }

    private fun publish(context: Context, collection: Uri, name: String, mime: String, dir: String, write: (OutputStream) -> Boolean): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/CircuitBender")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "cannot create $name", e)
            null
        } ?: return null
        var written = false
        try {
            written = resolver.openOutputStream(uri)?.use(write) ?: false
        } catch (e: IOException) {
            Log.e(TAG, "writing $name failed", e)
        } finally {
            if (written) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
        }
        return if (written) uri else null
    }
}
