/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O⁠ute⁠rTu⁠ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.heytune.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.util.BitmapLoader
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.heytune.app.R
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import javax.inject.Inject
import kotlin.math.min

class CoilBitmapLoader @Inject constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(coilCoroutine),
    private val data: LocalArtworkPath = LocalArtworkPath(null),
) : Fetcher, BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    // Artwork is disabled app-wide (large local art over Binder dropped the
    // Android Auto connection). The session/notification loader therefore
    // returns only a tiny generic icon — never touches storage, decoders,
    // or the network, and can never exceed Binder limits. Both overloads
    // ignore their input (including ExoPlayer-extracted embedded
    // artworkData, which is why Auto still showed low-res covers after
    // setArtworkUri was removed). The `data` overload is kept only to
    // satisfy the Fetcher/BitmapLoader contracts.
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future {
            drawPlaceholder(context, SMALL_ART, SMALL_ART)
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future {
            drawPlaceholder(context, SMALL_ART, SMALL_ART)
        }

    // Local-file artwork path disabled: always the tiny generic icon.
    // (Previously this ran MediaMetadataRetriever per row/session request.)
    override suspend fun fetch(): FetchResult {
        val icon = drawPlaceholder(context, SMALL_ART, SMALL_ART)
        return ImageFetchResult(
            image = icon.asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }

    companion object {
        // Single icon size used everywhere artwork used to appear (Auto,
        // notification, lists, player). 96px ARGB = ~37 kB: far below the
        // ~1 MB Binder limit no matter how many items reference it.
        const val SMALL_ART = 96

        // TODO: re eval dimens after a few months
        /**
         * Draw a centered square app icon with the maximum possible size while maintaining aspect ratio.
         *
         * @param context
         * @param x Desired final x dimension
         * @param y Desired final y dimension
         * @param size Percentage size of valid draw frame. Must be a value between 0.0 and 1.0. For example, 0.8
         *      means that inner frame should be 80% of the size of the final frame, and centered within that frame.
         */
        fun drawPlaceholder(context: Context, x: Int = SMALL_ART, y: Int = SMALL_ART, size: Float = 0.8f): Bitmap {
            val padding = size.coerceIn(0f, 1f)
            val innerRecWidth = x * padding
            val innerRecHeight = y * padding

            val squareLength = min(innerRecWidth, innerRecHeight).toInt()
            val squareLeft = ((x - squareLength) / 2)
            val squareTop = ((y - squareLength) / 2)

            val drawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.placeholder_icon)
            val bitmap = Bitmap.createBitmap(x, y, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            drawable?.setBounds(squareLeft, squareTop, squareLeft + squareLength, squareTop + squareLength)
            drawable?.draw(canvas)
            return bitmap
        }
    }

    class Factory(
        private val context: Context,
    ) : Fetcher.Factory<LocalArtworkPath> {
        override fun create(data: LocalArtworkPath, options: Options, imageLoader: ImageLoader): Fetcher? {
            return CoilBitmapLoader(context, data = data)
        }
    }
}

class LocalArtworkPathKeyer : Keyer<LocalArtworkPath> {
    override fun key(
        data: LocalArtworkPath,
        options: Options
    ): String? {
        return data.path + ";" + data.x + ";" + data.y
    }

}

data class LocalArtworkPath(val path: String?, val x: Int = -1, val y: Int = -1)
