package com.notifylog.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Small LRU of app icons, loaded from PackageManager on demand.
 *
 * Deliberately no image library and nothing cached to disk: icons are already
 * on the device, and re-reading a handful of them is cheaper than shipping a
 * loader or storing copies alongside every log row.
 */
object AppIcons {

    private const val CACHE_SIZE = 48

    private val cache = LruCache<String, Drawable>(CACHE_SIZE)

    /** Cache hit only — safe to call while binding a view. */
    fun cached(pkg: String): Drawable? = cache.get(pkg)

    /** Loads (and caches) the icon. Does I/O: call off the main thread. */
    fun load(context: Context, pkg: String): Drawable? {
        cache.get(pkg)?.let { return it }
        val pm = context.applicationContext.packageManager
        val icon = try {
            pm.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            // Uninstalled since the log was written — normal, not an error.
            null
        } catch (e: Exception) {
            null
        }
        if (icon != null) cache.put(pkg, icon)
        return icon
    }

    fun clear() = cache.evictAll()
}
