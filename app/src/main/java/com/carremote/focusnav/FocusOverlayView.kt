package com.carremote.focusnav

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** 全螢幕透明圖層，只負責畫出「焦點框」，不會攔截任何觸控。 */
class FocusOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val from = RectF()
    private val to = RectF()
    private val drawRect = RectF()
    private val loc = IntArray(2)

    private var shown = false
    private var pulse = 0f
    private var moveAnim: ValueAnimator? = null

    private val radius = 12f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x22FFFFFF
    }
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f * density
        color = 0xB0000000.toInt()
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        color = Color.WHITE
    }

    /** 顯示焦點框；若原本已顯示且 animate=true，則平滑移動過去。 */
    fun showAt(r: Rect, animate: Boolean) {
        val target = RectF(r)
        moveAnim?.cancel()
        if (!shown || !animate) {
            rect.set(target)
            shown = true
            invalidate()
            return
        }
        from.set(rect)
        to.set(target)
        moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            addUpdateListener { a ->
                val t = a.animatedFraction
                rect.set(
                    from.left + (to.left - from.left) * t,
                    from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t,
                    from.bottom + (to.bottom - from.bottom) * t
                )
                invalidate()
            }
            start()
        }
    }

    fun hideFrame() {
        moveAnim?.cancel()
        shown = false
        invalidate()
    }

    /** 點選或到邊界時的小小脈衝回饋。 */
    fun pulse() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener { a ->
                pulse = sin(PI * a.animatedFraction).toFloat()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        getLocationOnScreen(loc)
        val grow = (3f + 5f * pulse) * density
        drawRect.set(
            rect.left - loc[0] - grow,
            rect.top - loc[1] - grow,
            rect.right - loc[0] + grow,
            rect.bottom - loc[1] + grow
        )
        canvas.drawRoundRect(drawRect, radius, radius, fillPaint)
        canvas.drawRoundRect(drawRect, radius, radius, outerPaint)
        canvas.drawRoundRect(drawRect, radius, radius, innerPaint)
    }
}
