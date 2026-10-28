package com.shiyi.app.ocr

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.shiyi.app.importing.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** 离线识别商品条码（ML Kit 打包模型）。 */
class BarcodeEngine {
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE,
            ).build()
        )
    }

    suspend fun scan(file: File): List<String> = withContext(Dispatchers.Default) {
        val bmp = ImageUtil.decodeOriented(file, 2000) ?: error("无法解码图片")
        try {
            val codes = scanner.process(InputImage.fromBitmap(bmp, 0)).await()
            // 商品条码优先，其次其他格式
            codes.sortedBy { if (it.format in PRODUCT_FORMATS) 0 else 1 }
                .mapNotNull { it.rawValue?.trim()?.takeIf { v -> v.isNotEmpty() } }
                .distinct()
        } finally {
            bmp.recycle()
        }
    }

    companion object {
        private val PRODUCT_FORMATS = setOf(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
    }
}
