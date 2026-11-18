package com.flipos.launcher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File

/**
 * Every wallpaper the picker lists: the built-in ones ([BuiltInWallpapers],
 * ids are their resource names) with the ones added from the gallery (ids
 * `user/<file>`, saved under filesDir/wallpapers, already cropped to the
 * screen) spread evenly among them. Each is in the daily change unless the user left it out
 * ([LauncherPrefs.getWallpapersOutOfRotation]).
 */
object WallpaperLibrary {

    private const val USER_PREFIX = "user/"

    /**
     * Everything, in picker (and rotation) order: gallery picture k of u goes
     * after built-in number (k+1)*n/(u+1) of n, so the user's own pictures
     * come round every so often instead of all at the end.
     */
    fun all(context: Context): List<String> {
        val builtIn = BuiltInWallpapers.names()
        val user = userFiles(context).map { USER_PREFIX + it.name }
        if (user.isEmpty()) return builtIn
        val result = ArrayList<String>(builtIn.size + user.size)
        var next = 0
        builtIn.forEachIndexed { i, name ->
            result.add(name)
            while (next < user.size && (next + 1) * builtIn.size / (user.size + 1) == i + 1) {
                result.add(user[next++])
            }
        }
        while (next < user.size) result.add(user[next++])
        return result
    }

    /** The ones in the daily change, in order. */
    fun rotation(context: Context): List<String> {
        val out = LauncherPrefs(context).getWallpapersOutOfRotation()
        return all(context).filter { it !in out }
    }

    fun isInRotation(context: Context, id: String): Boolean = id !in LauncherPrefs(context).getWallpapersOutOfRotation()

    /** Puts [id] in or out of the daily change. */
    fun setInRotation(context: Context, id: String, inRotation: Boolean) {
        val prefs = LauncherPrefs(context)
        val out = prefs.getWallpapersOutOfRotation().toMutableSet()
        if (inRotation) out.remove(id) else out.add(id)
        prefs.setWallpapersOutOfRotation(out)
    }

    /** Added from the gallery (so it can be removed again). */
    fun isUser(id: String): Boolean = id.startsWith(USER_PREFIX)

    fun loadThumbnail(context: Context, id: String): Drawable? {
        if (!isUser(id)) return BuiltInWallpapers.loadThumbnail(context, id)
        val bitmap = BitmapFactory.decodeFile(file(context, id).path, BitmapFactory.Options().apply { inSampleSize = 2 })
            ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    /** Sets [id] as the phone's wallpaper. Real work - call off the main thread. Throws if it can't. */
    fun apply(context: Context, id: String) {
        if (!isUser(id)) {
            BuiltInWallpapers.apply(context, id)
            return
        }
        val bitmap = BitmapFactory.decodeFile(file(context, id).path) ?: error("Can't read $id")
        try {
            WallpaperManager.getInstance(context.applicationContext).setBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Saves a cropped gallery picture as a new wallpaper (in the daily change) and returns its id. Off the main thread. */
    fun addFromGallery(context: Context, bitmap: Bitmap): String {
        val file = File(userDir(context), "gallery_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return USER_PREFIX + file.name
    }

    /** Deletes a gallery wallpaper. */
    fun remove(context: Context, id: String) {
        if (!isUser(id)) return
        file(context, id).delete()
        setInRotation(context, id, true) // forget it in the left-out list too
    }

    private fun userDir(context: Context): File = File(context.filesDir, "wallpapers").apply { mkdirs() }

    private fun userFiles(context: Context): List<File> =
        userDir(context).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    private fun file(context: Context, id: String): File = File(userDir(context), id.removePrefix(USER_PREFIX))
}
