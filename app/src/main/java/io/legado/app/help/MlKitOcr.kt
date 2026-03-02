package io.legado.app.help

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.legado.app.exception.NoStackTraceException
import java.io.ByteArrayInputStream
import kotlin.math.max

object MlKitOcr {

    enum class OcrMode(val value: String) {
        RAW("raw"),
        LINE("line"),
        CAPTCHA("captcha");

        companion object {
            fun from(raw: String?): OcrMode? {
                if (raw.isNullOrBlank()) return RAW
                return entries.firstOrNull { it.value.equals(raw.trim(), ignoreCase = true) }
            }
        }
    }

    fun recognize(bytes: ByteArray, mode: OcrMode = OcrMode.RAW): String {
        if (bytes.isEmpty()) return ""
        val originalBitmap = decodeBitmap(bytes) ?: throw NoStackTraceException("OCR图片解码失败")
        val rotationDegrees = readRotationDegrees(bytes)
        val bitmap = rotateBitmapIfNeeded(originalBitmap, rotationDegrees)
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        return try {
            val rawResult = recognizeResultByBitmap(recognizer, bitmap)
            when (mode) {
                OcrMode.RAW -> rawResult.text.trim()
                OcrMode.LINE -> recognizeByLineSegmentation(
                    recognizer = recognizer,
                    sourceBitmap = bitmap,
                    rawResult = rawResult
                ).orEmpty().trim()

                OcrMode.CAPTCHA -> normalizeCaptcha(rawResult.text)
            }
        } finally {
            recognizer.close()
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            if (bitmap !== originalBitmap && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }
        }
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        return runCatching {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
        }.getOrElse {
            bitmap
        }
    }

    private fun normalizeCaptcha(text: String): String {
        return text.filter { it.isLetterOrDigit() }
    }

    private fun recognizeResultByBitmap(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): Text {
        val image = InputImage.fromBitmap(bitmap, 0)
        return Tasks.await(recognizer.process(image))
    }

    private fun recognizeByBitmap(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): String {
        return recognizeResultByBitmap(recognizer, bitmap).text
    }

    private fun recognizeByLineSegmentation(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        sourceBitmap: Bitmap,
        rawResult: Text
    ): String? {
        val rects = buildLineRectsFromResult(rawResult, sourceBitmap.width, sourceBitmap.height)
        if (rects.isEmpty()) return null

        val lineTexts = arrayListOf<String>()
        rects.take(120).forEach { rect ->
            val safeRect = clampRect(rect, sourceBitmap.width, sourceBitmap.height) ?: return@forEach
            val lineBitmap = Bitmap.createBitmap(
                sourceBitmap,
                safeRect.left,
                safeRect.top,
                safeRect.width(),
                safeRect.height()
            )
            try {
                val lineText = recognizeByBitmap(recognizer, lineBitmap).trim()
                if (lineText.isNotBlank()) {
                    lineTexts += lineText
                }
            } finally {
                if (!lineBitmap.isRecycled) {
                    lineBitmap.recycle()
                }
            }
        }
        if (lineTexts.isEmpty()) return null
        return lineTexts.joinToString("\n")
    }

    private fun buildLineRectsFromResult(
        rawResult: Text,
        imageWidth: Int,
        imageHeight: Int
    ): List<Rect> {
        val rawRects = rawResult.textBlocks
            .flatMap { it.lines }
            .mapNotNull { it.boundingBox }
            .filter { it.width() > 2 && it.height() > 2 }
        if (rawRects.isEmpty()) return emptyList()

        val horizontalPad = (imageWidth * 0.02f).toInt().coerceAtLeast(2)
        val verticalPad = (imageHeight * 0.002f).toInt().coerceAtLeast(1)
        val expanded = rawRects
            .sortedBy { it.top }
            .map {
                Rect(
                    (it.left - horizontalPad).coerceAtLeast(0),
                    (it.top - verticalPad).coerceAtLeast(0),
                    (it.right + horizontalPad).coerceAtMost(imageWidth),
                    (it.bottom + verticalPad).coerceAtMost(imageHeight)
                )
            }

        val merged = mutableListOf<Rect>()
        expanded.forEach { rect ->
            if (merged.isEmpty()) {
                merged += Rect(rect)
                return@forEach
            }
            val last = merged.last()
            val gap = rect.top - last.bottom
            val mergeGap = max(6, minOf(last.height(), rect.height()) / 3)
            if (gap <= mergeGap) {
                last.union(rect)
            } else {
                merged += Rect(rect)
            }
        }
        return merged.filter { it.height() >= 8 && it.width() >= 16 }
    }

    private fun clampRect(rect: Rect, width: Int, height: Int): Rect? {
        val left = rect.left.coerceIn(0, width - 1)
        val top = rect.top.coerceIn(0, height - 1)
        val right = rect.right.coerceIn(left + 1, width)
        val bottom = rect.bottom.coerceIn(top + 1, height)
        if (right - left <= 1 || bottom - top <= 1) return null
        return Rect(left, top, right, bottom)
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun readRotationDegrees(bytes: ByteArray): Int {
        return runCatching {
            ByteArrayInputStream(bytes).use { inputStream ->
                when (
                    ExifInterface(inputStream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSPOSE -> 90

                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSVERSE -> 270

                    else -> 0
                }
            }
        }.getOrDefault(0)
    }
}
