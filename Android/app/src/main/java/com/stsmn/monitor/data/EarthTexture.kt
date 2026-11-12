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

object EarthTexture {
    const val SIZE = 2048
    private const val ZOOM = 3
    private const val GRID = 8
    const val TILE_COUNT = GRID * GRID
    private const val TAG = "StsmnEarth"
    private const val CACHE_NAME = "earth_z3_v1.jpg"
    private const val SEA = 0xFF0B3D6E.toInt()

    suspend fun load(ctx: Context, onProgress: (Int) -> Unit): Bitmap? = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, CACHE_NAME)
        if (cache.isFile) {
            BitmapFactory.decodeFile(cache.absolutePath)?.let { b ->
                if (b.width == SIZE && b.height == SIZE) return@withContext b
                b.recycle()
            }
            cache.delete()
        }
        val world = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        var ok = false
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
                        val tile = gate.withPermit { fetchTile(x, y) ?: fetchTile(x, y) }
                        synchronized(lock) {
                            if (tile != null) { canvas.drawBitmap(tile, (x * 256).toFloat(), (y * 256).toFloat(), null); tile.recycle() } else failed++
                            done++
                        }
                        onProgress(done)
                    }
                }.awaitAll()
            }
            if (failed > 6) { Log.w(TAG, "too many tiles failed: $failed"); return@withContext null }
            if (failed == 0) {
                val tmp = File(ctx.filesDir, "$CACHE_NAME.tmp")
                tmp.outputStream().use { world.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (!tmp.renameTo(cache)) tmp.delete()
            }
            ok = true
            world
        } catch (e: Exception) {
            Log.w(TAG, "texture load failed: ${e.message}")
            null
        } finally {
            if (!ok) world.recycle()
        }
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
