package fr.guerard.palettewall

import android.app.WallpaperManager
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlin.math.max

const val TAG = "PaletteWall"

object Prefs {
    private const val FILE = "settings"
    private const val KEY_INTERVAL_MIN = "interval_min"
    private const val KEY_LAST_CHANGE = "last_change"
    private const val KEY_LAST_ID = "last_id"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOCK_TOO = "lock_too"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun intervalMinutes(c: Context) = p(c).getInt(KEY_INTERVAL_MIN, 120)
    fun setIntervalMinutes(c: Context, v: Int) = p(c).edit().putInt(KEY_INTERVAL_MIN, v).apply()

    fun enabled(c: Context) = p(c).getBoolean(KEY_ENABLED, true)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_ENABLED, v).apply()

    fun lockToo(c: Context) = p(c).getBoolean(KEY_LOCK_TOO, false)
    fun setLockToo(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_LOCK_TOO, v).apply()

    fun lastChange(c: Context) = p(c).getLong(KEY_LAST_CHANGE, 0L)
    fun lastId(c: Context) = p(c).getLong(KEY_LAST_ID, -1L)
    fun markChanged(c: Context, id: Long) =
        p(c).edit().putLong(KEY_LAST_CHANGE, System.currentTimeMillis()).putLong(KEY_LAST_ID, id).apply()

    fun isDue(c: Context): Boolean =
        System.currentTimeMillis() - lastChange(c) >= intervalMinutes(c) * 60_000L
}

object Wallpapers {

    /** IDs of every image the Samsung Gallery has marked as favourite. */
    fun favouriteIds(c: Context): List<Long> {
        val ids = mutableListOf<Long>()
        val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        c.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.IS_FAVORITE} = 1",
            null,
            null
        )?.use { cur ->
            val col = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cur.moveToNext()) ids += cur.getLong(col)
        }
        return ids
    }

    /** Picks a random favourite (not the previous one) and sets it. Returns the ID set, or null. */
    fun setRandomFavourite(c: Context): Long? {
        val ids = favouriteIds(c)
        if (ids.isEmpty()) {
            Log.w(TAG, "No favourites found (permission missing, or none marked)")
            return null
        }
        val last = Prefs.lastId(c)
        val pool = if (ids.size > 1) ids.filter { it != last } else ids
        val id = pool.random()
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id
        )
        return if (setFromUri(c, uri)) {
            Prefs.markChanged(c, id)
            id
        } else null
    }

    private fun setFromUri(c: Context, uri: Uri): Boolean = try {
        val metrics = c.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // Decode at roughly screen resolution; ImageDecoder applies EXIF rotation itself.
        val bmp: Bitmap = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(c.contentResolver, uri)
        ) { decoder, info, _ ->
            val sample = max(1, minOf(info.size.width / screenW, info.size.height / screenH))
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

        val crop = centreCrop(bmp.width, bmp.height, screenW, screenH)
        val wm = WallpaperManager.getInstance(c)
        var which = WallpaperManager.FLAG_SYSTEM
        if (Prefs.lockToo(c)) which = which or WallpaperManager.FLAG_LOCK
        wm.setBitmap(bmp, crop, true, which)
        bmp.recycle()
        Log.i(TAG, "Wallpaper set from $uri")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not set wallpaper from $uri", e)
        false
    }

    /** The largest centred rectangle of the image that has the screen's aspect ratio. */
    private fun centreCrop(w: Int, h: Int, sw: Int, sh: Int): Rect {
        val target = sw.toFloat() / sh
        return if (w.toFloat() / h > target) {
            val cw = (h * target).toInt()
            val x = (w - cw) / 2
            Rect(x, 0, x + cw, h)
        } else {
            val ch = (w / target).toInt()
            val y = (h - ch) / 2
            Rect(0, y, w, y + ch)
        }
    }
}
