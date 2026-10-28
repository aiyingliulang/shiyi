package com.shiyi.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 离线中文 OCR（ML Kit 打包模型，首次使用无需联网下载）。
 * 只负责提取文字行；字段解析交给 [OrderParser]。
 *
 * 长截图使用 BitmapRegionDecoder 分块解码，任何时刻内存里只有一块，
 * 避免整张长图解码导致内存溢出。
 */
class OcrEngine(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private data class Piece(val text: String, val box: Rect)

    suspend fun recognize(file: File): List<String> = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        require(w > 0 && h > 0) { "无法解析图片" }

        var sample = 1
        while (w / sample > MAX_WIDTH) sample *= 2
        val dw = w / sample
        // 每块的高度（源图像素）：约为宽度的 2 倍
        val tileSrc = ((dw * 2).coerceAtLeast(1200)) * sample
        val overlapSrc = 120 * sample
        val pieces = mutableListOf<Piece>()

        if (h <= tileSrc * 5 / 4) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("无法解码图片")
            try { pieces += runOcr(bmp, 0) } finally { bmp.recycle() }
        } else {
            val decoder = newRegionDecoder(file)
            if (decoder == null) {
                // 不支持分块解码的格式：整体缩小后识别
                var s = sample
                while ((w / s).toLong() * (h / s) > MAX_PIXELS) s *= 2
                val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
                    ?: error("无法解码图片")
                try { pieces += runOcr(bmp, 0) } finally { bmp.recycle() }
            } else try {
                var top = 0
                var count = 0
                while (top < h && count < MAX_TILES) {
                    val bottom = (top + tileSrc).coerceAtMost(h)
                    if (bottom - top < 64) break
                    val tile = decoder.decodeRegion(Rect(0, top, w, bottom), BitmapFactory.Options().apply { inSampleSize = sample })
                    if (tile != null) {
                        try {
                            val got = runOcr(tile, top / sample)
                            got.forEach { p ->
                                val dup = pieces.any { it.text == p.text && kotlin.math.abs(it.box.top - p.box.top) < overlapSrc / sample }
                                if (!dup) pieces += p
                            }
                        } finally { tile.recycle() }
                    }
                    count++
                    if (bottom == h) break
                    top = bottom - overlapSrc
                }
            } finally {
                decoder.recycle()
            }
        }
        mergeRows(pieces)
    }

    @Suppress("DEPRECATION")
    private fun newRegionDecoder(file: File): BitmapRegionDecoder? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.absolutePath)
        else BitmapRegionDecoder.newInstance(file.absolutePath, false)
    }.getOrNull()

    private suspend fun runOcr(bitmap: Bitmap, offsetY: Int): List<Piece> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { b -> b.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            Piece(line.text, Rect(box.left, box.top + offsetY, box.right, box.bottom + offsetY))
        }
    }

    private fun mergeRows(pieces: List<Piece>): List<String> {
        val sorted = pieces.sortedBy { it.box.centerY() }
        val rows = mutableListOf<MutableList<Piece>>()
        for (p in sorted) {
            val last = rows.lastOrNull()
            if (last != null) {
                val ref = last.first().box
                val tol = minOf(ref.height(), p.box.height()) * 0.5f
                if (kotlin.math.abs(ref.centerY() - p.box.centerY()) <= tol) {
                    last += p; continue
                }
            }
            rows += mutableListOf(p)
        }
        return rows.map { r -> r.sortedBy { it.box.left }.joinToString(" ") { it.text } }
    }

    companion object {
        private const val MAX_WIDTH = 1600
        private const val MAX_TILES = 40
        private const val MAX_PIXELS = 12_000_000L
    }
}
