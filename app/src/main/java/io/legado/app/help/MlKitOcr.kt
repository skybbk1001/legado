package io.legado.app.help

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.legado.app.exception.NoStackTraceException
import java.io.ByteArrayInputStream

object MlKitOcr {

    fun recognize(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val originalBitmap = decodeBitmap(bytes) ?: throw NoStackTraceException("OCR图片解码失败")
        val rotationDegrees = readRotationDegrees(bytes)
        val preprocessedBitmap = preprocessBitmap(originalBitmap)
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        return try {
            val image = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
            val result = Tasks.await(recognizer.process(image))
            result.text
        } finally {
            recognizer.close()
            preprocessedBitmap.recycle()
            if (preprocessedBitmap !== originalBitmap) {
                originalBitmap.recycle()
            }
        }
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

    private fun preprocessBitmap(src: Bitmap): Bitmap {
        val out = createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val grayMatrix = ColorMatrix().apply {
            setSaturation(0f)
        }
        val contrast = 1.15f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayMatrix.postConcat(contrastMatrix)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
