package de.heineke.myvrnlite.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A ticket-shaped card background (as the official app's ticket list, and .tcard in the web
 * client): a lighter stub of height [stub] on top of the body, rounded corners, and a notch on
 * each side where they meet — circles in the page colour [page], half outside the card.
 */
class TicketCardDrawable(
    private val top: Int,
    private val body: Int,
    private val page: Int,
    private val stub: Float,
    private val radius: Float,
    private val notch: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        paint.color = body
        canvas.drawRoundRect(rect, radius, radius, paint)
        // The stub: rounded at the top only.
        path.reset()
        path.addRoundRect(RectF(rect.left, rect.top, rect.right, rect.top + stub),
            floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
        paint.color = top
        canvas.drawPath(path, paint)
        paint.color = page
        canvas.drawCircle(rect.left, rect.top + stub, notch, paint)
        canvas.drawCircle(rect.right, rect.top + stub, notch, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
