package com.oyuntr.ocr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Readable two-line subtitle panel intended to be layered over the decoded game surface. */
class SubtitleOverlayView(context: Context) : FrameLayout(context) {
    private val original = TextView(context).apply {
        setTextColor(Color.rgb(220, 230, 235))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
    }
    private val translated = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    init {
        clipChildren = false
        clipToPadding = false
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(12), dp(28), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(220, 0, 0, 0))
            }
            addView(original, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(translated, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(dp(48), 0, dp(48), dp(42))
            },
        )
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    fun show(subtitle: TranslatedSubtitle) {
        post {
            original.text = subtitle.originalText
            translated.text = subtitle.translatedText
            visibility = if (subtitle.translatedText.isBlank()) GONE else VISIBLE
        }
    }

    fun clear() {
        post {
            original.text = ""
            translated.text = ""
            visibility = GONE
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
