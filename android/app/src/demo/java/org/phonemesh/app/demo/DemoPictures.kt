package org.phonemesh.app.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader

/** Drawn pictures the demo people send: no photos of anyone or anywhere, just simple scenes. */
object DemoPictures {
    private val skies = listOf(0xFF7EC8F0.toInt() to 0xFFFFE9B0.toInt(), 0xFFFFB46B.toInt() to 0xFFFFE0A0.toInt(),
        0xFF9AD0C2.toInt() to 0xFFF4F1DE.toInt())

    fun scene(n: Int): Bitmap {
        val side = 320
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val (top, bottom) = skies[Math.floorMod(n, skies.size)]
        paint.shader = LinearGradient(0f, 0f, 0f, side * 0.7f, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFC94A.toInt()
        canvas.drawCircle(side * (0.25f + 0.25f * Math.floorMod(n, 3)), side * 0.22f, side * 0.09f, paint)
        paint.color = 0xFF5FA35A.toInt()
        canvas.drawRect(0f, side * 0.68f, side.toFloat(), side.toFloat(), paint)
        // A house: walls, roof, door.
        paint.color = 0xFFD9824B.toInt()
        canvas.drawRect(side * 0.18f, side * 0.48f, side * 0.52f, side * 0.74f, paint)
        paint.color = 0xFF8C4A2F.toInt()
        canvas.drawPath(Path().apply {
            moveTo(side * 0.13f, side * 0.49f); lineTo(side * 0.35f, side * 0.33f); lineTo(side * 0.57f, side * 0.49f); close()
        }, paint)
        paint.color = 0xFF4A2C20.toInt()
        canvas.drawRect(side * 0.31f, side * 0.6f, side * 0.39f, side * 0.74f, paint)
        // A tree.
        paint.color = 0xFF6B4226.toInt()
        canvas.drawRect(side * 0.73f, side * 0.5f, side * 0.77f, side * 0.74f, paint)
        paint.color = 0xFF2E7D32.toInt()
        canvas.drawCircle(side * 0.75f, side * 0.45f, side * 0.11f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(side * 0.62f, side * 0.15f, side * 0.04f, paint)
        canvas.drawCircle(side * 0.67f, side * 0.13f, side * 0.05f, paint)
        canvas.drawCircle(side * 0.72f, side * 0.15f, side * 0.04f, paint)
        return bitmap
    }
}
