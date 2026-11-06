package com.stsmn.monitor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The picture wrapped around the 3D Earth: a 2048 x 2048 Web-Mercator world image made from 64 real Esri
 * World Imagery tiles (zoom 3). Downloaded once (about 1.5 MB), then served from a file in the app's private
 * storage, so later starts are instant and work without internet.
 */
object EarthTexture {
    const val SIZE = 2048
    private const val ZOOM = 3
    private const val GRID = 8
    const val TILE_COUNT = GRID * GRID
    private const val TAG = "StsmnEarth"
    private const val CACHE_NAME = "earth_z3_v1.jpg"
    private const val SEA = 0xFF0B3D6E.toInt()

    /** Returns SIZE*SIZE ARGB pixels, or null when nothing usable could be loaded. `onProgress` gets 0..TILE_COUNT. */
    suspend fun load(ctx: Context, onProgress: (Int) -> Unit): IntArray? = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, CACHE_NAME)
        if (cache.isFile) {
            decodeToPixels(BitmapFactory.decodeFile(cache.absolutePath))?.let { return@withContext it }
            cache.delete()                                         // unreadable cache: download again
        }
        val world = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(world)
            canvas.drawColor(SEA)
            var done = 0
            var failed = 0
            val gate = Semaphore(6)
            val lock = Any()
            coroutineScope {
                (0 until TILE_COUNT).map { n ->
                    async {
                        val x = n % GRID
                        val y = n / GRID
                        val tile = gate.withPermit { fetchTile(x, y) ?: fetchTile(x, y) }   // one retry
                        synchronized(lock) {
                            if (tile != null) { canvas.drawBitmap(tile, (x * 256).toFloat(), (y * 256).toFloat(), null); tile.recycle() } else failed++
                            done++
                        }
                        onProgress(done)
                    }
                }.awaitAll()
            }
            if (failed > 6) { Log.w(TAG, "too many tiles failed: $failed"); return@withContext null }
            if (failed == 0) {                                     // cache only a complete picture
                val tmp = File(ctx.filesDir, "$CACHE_NAME.tmp")
                tmp.outputStream().use { world.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (!tmp.renameTo(cache)) tmp.delete()
            }
            val px = IntArray(SIZE * SIZE)
            world.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            px
        } catch (e: Exception) {
            Log.w(TAG, "texture load failed: ${e.message}")
            null
        } finally {
            world.recycle()
        }
    }

    private fun decodeToPixels(b: Bitmap?): IntArray? {
        if (b == null || b.width != SIZE || b.height != SIZE) { b?.recycle(); return null }
        val px = IntArray(SIZE * SIZE)
        b.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        b.recycle()
        return px
    }

    private fun fetchTile(x: Int, y: Int): Bitmap? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$ZOOM/$y/$x")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "STSMN/1.0 (Android)")
            }
            if (c.responseCode != 200) null else c.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }
}
