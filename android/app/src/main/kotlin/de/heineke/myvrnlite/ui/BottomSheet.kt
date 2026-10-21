package de.heineke.myvrnlite.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Bottom sheet over the map (Fahrplan), like webapp/static/bsheet.js:
 *   PEEK  only [peekView] is visible (the search form, a trip summary)
 *   HALF  about two thirds of the page
 *   FULL  the whole page; the content scrolls
 * Below FULL a vertical drag anywhere moves the sheet; in FULL the content scrolls, and a pull
 * down from its top (or on the grab bar) moves the sheet again.
 *
 * Children: a grab bar and a [ScrollView] (found by type). In [side] mode (wide screens)
 * the sheet is a fixed panel: no dragging, always scrollable.
 */
class BottomSheet @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    enum class State { PEEK, HALF, FULL }

    var state = State.PEEK
        private set
    /** The part that stays visible when peeking (measured from the top of the sheet). */
    var peekView: View? = null
    /** Called with how much of the page the sheet covers (px from the bottom), while it moves too. */
    var onMoved: ((Int) -> Unit)? = null
    var side = false
        set(v) {
            field = v
            if (v) { animator?.cancel(); translationY = 0f; scroll?.isVerticalScrollBarEnabled = true }
            requestLayout()
        }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private val scroll: ScrollView? get() = (0 until childCount).map(::getChildAt).firstOrNull { it is ScrollView } as ScrollView?
    private val grab: View? get() = getChildAt(0)
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var dragging = false
    private var onGrab = false
    private var tracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    /** Translation for a state: 0 = FULL. */
    private fun target(s: State): Float {
        val h = height.toFloat()
        val peek = peekView?.let { v ->
            var top = 0
            var cur: View? = v
            while (cur != null && cur !== this) {  // v's offset inside the sheet
                top += cur.top - (if (cur is ScrollView) cur.scrollY else 0)
                cur = cur.parent as? View
            }
            (h - (top + v.height + context.dp(14))).coerceIn(0f, h - context.dp(70))
        } ?: (h - context.dp(90))
        return when (s) {
            State.FULL -> 0f
            State.HALF -> minOf(h * 0.35f, peek)
            State.PEEK -> peek
        }
    }

    /** How much of the page the sheet covers right now. */
    val covered: Int get() = if (side) 0 else (height - translationY).toInt().coerceAtLeast(0)

    fun set(s: State, animate: Boolean = true) {
        state = s
        if (side || height == 0) return
        if (s != State.FULL) scroll?.scrollTo(0, 0)
        animator?.cancel()
        val to = target(s)
        if (!animate) {
            translationY = to
            onMoved?.invoke(covered)
            return
        }
        animator = ValueAnimator.ofFloat(translationY, to).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                translationY = it.animatedValue as Float
                onMoved?.invoke(covered)
            }
            start()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!side && !dragging && animator?.isRunning != true) {
            translationY = target(state)
            onMoved?.invoke(covered)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (side) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startY = translationY
                dragging = false
                onGrab = ev.y < (grab?.bottom ?: 0)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                animator?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.rawY - downY
                if (!dragging && abs(dy) > slop && abs(dy) > abs(ev.rawX - downX)) {
                    val atTop = (scroll?.scrollY ?: 0) == 0
                    dragging = state != State.FULL || onGrab || (dy > 0 && atTop)
                    if (dragging) downY = ev.rawY  // start without a jump
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (side) return false
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A touch on the sheet itself (padding, grab bar): keep it, so it can become a drag.
                downX = ev.rawX
                downY = ev.rawY
                startY = translationY
                onGrab = ev.y < (grab?.bottom ?: 0)
                if (tracker == null) tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (!dragging && abs(dy) > slop) { dragging = true; downY = ev.rawY }
                if (dragging) {
                    var y = startY + (ev.rawY - downY)
                    if (y < 0) y /= 4  // a little resistance past the top
                    translationY = y.coerceAtMost(target(State.PEEK) + context.dp(40))
                    onMoved?.invoke(covered)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle()
                else if (ev.actionMasked == MotionEvent.ACTION_UP && onGrab) {
                    set(if (state == State.PEEK) State.HALF else if (state == State.HALF) State.FULL else State.HALF)
                }
                dragging = false
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    /** A fling goes one step in its direction; otherwise snap to the nearest position. */
    private fun settle() {
        val v = tracker?.let { it.computeCurrentVelocity(1000); it.yVelocity } ?: 0f
        val y = translationY
        val order = listOf(State.FULL, State.HALF, State.PEEK)
        val next = when {
            v < -flingVelocity -> order.lastOrNull { target(it) < y - 4 } ?: State.FULL
            v > flingVelocity -> order.firstOrNull { target(it) > y + 4 } ?: State.PEEK
            else -> order.minByOrNull { abs(target(it) - y) }!!
        }
        set(next)
    }
}

/** Lays children out left to right, wrapping into new lines (line badges in a trip row). */
class FlowLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : android.view.ViewGroup(context, attrs) {
    var hGap = context.dp(2)
    var vGap = context.dp(4)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += c.measuredWidth + hGap
            widest = maxOf(widest, x)
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(y + lineH + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        // Children of a line are centred vertically on the line's height.
        val lines = mutableListOf<MutableList<View>>(mutableListOf())
        val heights = mutableListOf(0)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; lines += mutableListOf<View>(); heights += 0 }
            lines.last() += c
            heights[heights.size - 1] = maxOf(heights.last(), c.measuredHeight)
            x += c.measuredWidth + hGap
        }
        lines.forEachIndexed { n, line ->
            x = 0
            lineH = heights[n]
            for (c in line) {
                val top = paddingTop + y + (lineH - c.measuredHeight) / 2
                c.layout(paddingLeft + x, top, paddingLeft + x + c.measuredWidth, top + c.measuredHeight)
                x += c.measuredWidth + hGap
            }
            y += lineH + vGap
        }
    }
}
