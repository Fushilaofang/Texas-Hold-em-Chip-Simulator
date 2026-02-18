package com.fushilaofang.texasholdemchipsim.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

object AvatarHelper {

    private const val AVATAR_SIZE = 96   // 96×96 像素
    private const val JPEG_QUALITY = 75  // JPEG 质量，~2-4KB

    /**
     * 从相册 URI 读取图片，做中心裁切正方形，缩放到 96×96，返回 Base64 字符串。
     * 若失败返回空字符串。
     */
    fun uriToBase64(context: Context, uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return ""
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            original ?: return ""

            val cropped = centerCropSquare(original)
            val scaled = Bitmap.createScaledBitmap(cropped, AVATAR_SIZE, AVATAR_SIZE, true)

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 将 Base64 字符串解码为 Bitmap，失败返回 null。
     */
    fun base64ToBitmap(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /** 从 Bitmap 中心裁切正方形 */
    private fun centerCropSquare(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        return if (w == h) {
            src
        } else {
            val size = minOf(w, h)
            val x = (w - size) / 2
            val y = (h - size) / 2
            Bitmap.createBitmap(src, x, y, size, size)
        }
    }
}
