package com.oyuntr

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native Android TV home screen with the current integration status. */
class MainActivity : Activity() {
    private val background = Color.rgb(8, 16, 24)
    private val panel = Color.rgb(18, 31, 42)
    private val muted = Color.rgb(166, 184, 195)
    private val accent = Color.rgb(65, 214, 179)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        setContentView(buildHome())
    }

    private fun buildHome(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(40), dp(64), dp(48))
            setBackgroundColor(this@MainActivity.background)
        }

        content.addView(label("OYUNTR", 14, accent, bold = true))
        content.addView(label("Oyunları Türkçe yaşa.", 36, Color.WHITE, bold = true).apply {
            setPadding(0, dp(6), 0, dp(28))
        })

        val statusCard = card()
        statusCard.addView(label("PS4 REMOTE PLAY", 13, accent, bold = true))
        statusCard.addView(label("PS4 oturum katmanı eklendi", 23, Color.WHITE, bold = true).apply {
            setPadding(0, dp(8), 0, dp(6))
        })
        statusCard.addView(label(
            "Kayıt ekranı, H.264 decoder ve Türkçe altyazı bağlantısı eklendi. Android TV ve PS4 üzerinde henüz derlenip denenmedi.",
            16,
            muted,
        ))
        statusCard.addView(primaryButton("PS4 bağlantı durumunu görüntüle") {
            startActivity(android.content.Intent(this, RemotePlayActivity::class.java))
        })
        content.addView(statusCard, cardParams(bottom = 18))

        val pipeline = card()
        pipeline.addView(label("TÜRKÇE ÇEVİRİ AKIŞI", 13, accent, bold = true))
        pipeline.addView(label("Görüntü → OCR → çeviri → yapay zekâ seslendirme", 20, Color.WHITE, bold = true).apply {
            setPadding(0, dp(8), 0, dp(6))
        })
        pipeline.addView(label(
            "Altyazıları cihazdaki yerel sesle veya karakter başına ayrı yapay zekâ sesiyle dinle. AI sesi için OyunTR ses sunucusu kurulumu gerekir.",
            16,
            muted,
        ))
        content.addView(pipeline, cardParams(bottom = 18))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        actions.addView(primaryButton("Altyazı hattı") {
            startActivity(android.content.Intent(this, RemotePlayActivity::class.java))
        }, buttonParams(end = 12))
        actions.addView(primaryButton("AI ses ve karakter ayarları") {
            startActivity(android.content.Intent(this, RemotePlayActivity::class.java))
        })
        content.addView(actions)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(this@MainActivity.background)
            addView(content)
        }
        return scroll
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(22), dp(24), dp(22))
        background = rounded(panel, 18)
    }

    private fun label(text: String, sizeSp: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isEnabled = true
        isFocusable = true
        minHeight = dp(54)
        textSize = 15f
        setTextColor(this@MainActivity.background)
        background = rounded(accent, 12)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        contentDescription = text
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun cardParams(bottom: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(bottom) }

    private fun buttonParams(end: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = dp(end) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
