package de.skygazer.myvrnlite.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import de.skygazer.myvrnlite.R

/**
 * A slide-up card from the bottom (as the official app's info sheets): grab handle, title,
 * paragraphs and a wide close button. Closes by the button, a tap outside, back, or a swipe down.
 * Views are built with the activity, so the palette's colours apply.
 */
@SuppressLint("ClickableViewAccessibility")
fun Activity.infoSheet(title: String, paragraphs: List<CharSequence>) {
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val radius = dp(18).toFloat()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(attrColor(R.attr.cSurface))
            setCornerRadii(floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f))
        }
        setPadding(dp(24), dp(10), dp(24), dp(22))
        addView(View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(attrColor(R.attr.cTextMuted))
            }
        }, LinearLayout.LayoutParams(dp(40), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        })
        add(styled(R.style.Text_Heading, title.uppercase()).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        for (p in paragraphs) add(styled(R.style.Text, p).apply {
            textSize = 16f
            setTextColor(attrColor(R.attr.cTextHeading))
            setLineSpacing(0f, 1.15f)
        }, top = 14)
        add(Button(context, null, 0, R.style.Button).apply {
            setText(R.string.fp_close)
            // Modern: the blue action button; otherwise a light pill in the ticket colours.
            setTextColor(if (modern) Color.WHITE else attrColor(R.attr.cTcardInk))
            background = GradientDrawable().apply {
                cornerRadius = dp(if (modern) 14 else 26).toFloat()
                setColor(attrColor(if (modern) R.attr.brand else R.attr.cTcardTop))
            }
            setOnClickListener { dialog.dismiss() }
        }, top = 26)
    }
    // Swipe down to close: the card follows the finger; far enough (or fast) and it goes.
    var downY = 0f
    card.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = e.rawY
            MotionEvent.ACTION_MOVE -> v.translationY = maxOf(0f, e.rawY - downY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (v.translationY > v.height / 4f) dialog.dismiss()
                else ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f).setDuration(150).start()
        }
        true
    }
    // Edge to edge (Android 15+): keep the button clear of the navigation bar.
    if (Build.VERSION.SDK_INT >= 30) card.setOnApplyWindowInsetsListener { v, insets ->
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dp(22) + insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
        insets
    }
    dialog.setContentView(card)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.apply {
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setWindowAnimations(R.style.SheetAnimation)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.45f)
        // The default dialog window keeps a margin around its content.
        decorView.setPadding(0, 0, 0, 0)
    }
    dialog.show()
}
