package com.yjp.tgenhance.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.yjp.tgenhance.R
import kotlin.math.abs

/**
 * Telegram 官方风格的开关控件。
 *
 * 为什么自绘而不用系统 Switch：
 *  - 系统 Switch 在不同 ROM 上外观差异很大，无法保证和 TG 设置页一致；
 *  - TG 的滑块直径大于轨道高度、带投影，系统 Switch 的 thumb/track 尺寸关系是固定的，改不出来；
 *  - 自绘后可以精确复刻「点击切换 + 横向拖拽」这套交互。
 *
 * 状态变化通过 [onCheckedChangeListener] 通知。**直接写 [isChecked] 是静默的**，
 * 不会回调 —— 调用方可以用它来回滚（例如风险提示被取消），不会造成回调递归。
 */
class TgSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 状态变化回调；仅由用户交互触发，直接写 [isChecked] 不触发。 */
    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    /**
     * 开关状态。
     *
     * 赋值即静默生效（带动画、不回调），供外部回滚使用；
     * 用户点击/拖拽走 [apply]，那里会额外触发一次回调。
     */
    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    private val trackWidth = dp(40f)
    private val trackHeight = dp(22f)
    private val thumbRadius = dp(14f)

    /** 滑块比轨道大，视图需要额外留白，否则滑块（含投影）会被裁掉 */
    private val edgeInset = dp(5f)

    private val trackOffColor = context.getColor(R.color.switch_off)
    private val trackOnColor = context.getColor(R.color.accent)
    private val thumbColor = context.getColor(R.color.switch_thumb)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var animator: ValueAnimator? = null

    /** 0f = 关闭，1f = 开启；拖拽时直接跟随手指 */
    private var fraction = 0f

    private var downX = 0f
    private var tracking = false
    private var dragging = false

    init {
        // 投影需要软件图层。设置页里实例很少，这点开销可以忽略。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((trackWidth + edgeInset * 2f).toInt(), widthMeasureSpec),
            resolveSize((trackHeight + edgeInset * 2f).toInt(), heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val top = (h - trackHeight) / 2f
        trackRect.set(edgeInset, top, edgeInset + trackWidth, top + trackHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = trackRect.centerY()

        paint.reset()
        paint.isAntiAlias = true
        paint.color = blend(trackOffColor, trackOnColor, fraction)
        val radius = trackRect.height() / 2f
        canvas.drawRoundRect(trackRect, radius, radius, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = thumbColor
        paint.setShadowLayer(dp(1.5f), 0f, dp(1f), 0x33000000)
        canvas.drawCircle(thumbCenterX(fraction), cy, thumbRadius, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                tracking = true
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!dragging && abs(event.x - downX) > touchSlop) dragging = true
                if (dragging) {
                    stopAnimation()
                    fraction = progressAt(event.x)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                // 拖拽超过一半就顺势吸附到那一侧；否则视为一次点击
                val target = if (dragging) fraction >= 0.5f else !isChecked
                apply(target, notify = true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    /** 用户交互入口：改状态并在真正发生变化时回调一次。 */
    private fun apply(checked: Boolean, notify: Boolean) {
        val changed = isChecked != checked
        isChecked = checked   // 走属性 setter：静默 + 动画
        if (notify && changed) onCheckedChangeListener?.invoke(checked)
    }

    private fun animateTo(target: Float) {
        stopAnimation()
        if (fraction == target) return
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    /** 滑块圆心 x 坐标：关闭时贴轨道左内圈，开启时贴右内圈。 */
    private fun thumbCenterX(f: Float): Float {
        val start = trackRect.left + trackRect.height() / 2f
        val end = trackRect.right - trackRect.height() / 2f
        return start + (end - start) * f
    }

    private fun progressAt(x: Float): Float {
        val start = thumbCenterX(0f)
        val end = thumbCenterX(1f)
        if (end - start <= 0f) return 0f
        return ((x - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun dp(value: Float): Float = value * density

    private fun blend(from: Int, to: Int, t: Float): Int = Color.argb(
        (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt(),
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
    )
}
