package com.shiyi.app.importing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

object ImageUtil {
    /** 按 EXIF 方向解码，长边不超过 [maxDim]。 */
    fun decodeOriented(file: File, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > maxDim * 2) s *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scale = minOf(1f, maxDim.toFloat() / maxOf(raw.width, raw.height))
        if (rotation == 0f && scale >= 1f) return raw
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out != raw) raw.recycle()
        return out
    }

    /** 把照片压缩成 JPEG（长边 [maxDim]），用于保存零食包装图，节省空间。失败时返回 false。 */
    fun compressTo(src: File, dst: File, maxDim: Int = 1600, quality: Int = 85): Boolean {
        val bmp = decodeOriented(src, maxDim) ?: return false
        return try {
            dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 截图专用：只限制宽度（长截图保留完整高度，文字不会被压糊），
     * 同时限制总像素，避免超长截图占用过多内存。
     */
    fun decodeScreenshot(file: File, maxWidth: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / s > maxWidth * 2 || (o.outWidth / s).toLong() * (o.outHeight / s) > 30_000_000L) s *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        if (raw.width <= maxWidth) return raw
        val h = (raw.height.toLong() * maxWidth / raw.width).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(raw, maxWidth, h, true)
        if (out != raw) raw.recycle()
        return out
    }

    /** 压缩成 JPEG 字节（用于写入备份）。[screenshot] 为 true 时按宽度缩放。 */
    fun compressToBytes(src: File, maxDim: Int, screenshot: Boolean, quality: Int = 75): ByteArray? = runCatching {
        val bmp = (if (screenshot) decodeScreenshot(src, maxDim) else decodeOriented(src, maxDim)) ?: return null
        try {
            java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally { bmp.recycle() }
    }.getOrNull()

    /** 保存订单截图：宽度不超过 1280，JPEG 85。失败返回 false。 */
    fun compressScreenshotTo(src: File, dst: File, maxWidth: Int = 1280, quality: Int = 85): Boolean = runCatching {
        val bmp = decodeScreenshot(src, maxWidth) ?: return false
        try { dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) } } finally { bmp.recycle() }
    }.getOrDefault(false)
}
