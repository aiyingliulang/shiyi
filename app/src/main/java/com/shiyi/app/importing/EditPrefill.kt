package com.shiyi.app.importing

import com.shiyi.app.ocr.ParsedOrder
import java.io.File

/** 识别/分享结果 → 确认页的预填数据 */
data class EditPrefill(
    val parsed: ParsedOrder,
    val images: List<File>,
    val sharedText: String?,
    val ocrFailed: Boolean,
    val imageCount: Int,
    /** 识别过程中的额外提示（如识别进程异常、部分图片失败） */
    val note: String? = null,
)
