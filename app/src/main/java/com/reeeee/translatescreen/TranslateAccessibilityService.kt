package com.reeeee.translatescreen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.math.abs

/**
 * خدمة إمكانية الوصول:
 *  1) تعرض زراً عائماً فوق كل التطبيقات.
 *  2) عند الضغط تقرأ كل النصوص الظاهرة ومواقعها.
 *  3) تترجمها على الجهاز بـ ML Kit.
 *  4) ترسم الترجمة فوق كل نص في مكانه بطبقة لا تعيق اللمس.
 */
class TranslateAccessibilityService : AccessibilityService() {

    private enum class State { READY, TRANSLATING, SHOWING }

    private class TextItem(val text: String, val bounds: Rect)

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var buttonParams: WindowManager.LayoutParams

    private var button: TextView? = null
    private var overlay: View? = null
    private var state = State.READY

    /** يزيد مع كل إلغاء/بدء حتى نتجاهل نتائج ترجمة قديمة. */
    private var generation = 0

    private var translator: Translator? = null
    private var translatorKey: String? = null

    // حالة سحب الزر
    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var dragged = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_DIRECTION) clearOverlay()
    }

    // ───────────────────────── دورة حياة الخدمة ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        createButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return
        // تغيّرت النافذة أو تم التمرير، فمواقع الترجمة القديمة لم تعد صحيحة
        if (state == State.SHOWING) clearOverlay()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (!::windowManager.isInitialized) return
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        generation++
        removeOverlayView()
        button?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // الزر غير مضاف
            }
        }
        button = null
        translator?.close()
        translator = null
        translatorKey = null
    }

    // ───────────────────────── الزر العائم ─────────────────────────

    private fun createButton() {
        if (button != null) return

        val size = dp(56)
        val tv = TextView(this)
        tv.setText(R.string.btn_translate)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 12f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC1565C0.toInt())
        }
        tv.setOnTouchListener { v, e -> handleTouch(v, e) }

        buttonParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        buttonParams.gravity = Gravity.TOP or Gravity.START
        buttonParams.x = prefs.getInt(Prefs.KEY_BTN_X, dp(16))
        buttonParams.y = prefs.getInt(Prefs.KEY_BTN_Y, dp(200))

        windowManager.addView(tv, buttonParams)
        button = tv
        state = State.READY
        updateLabel()
    }

    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = buttonParams.x
                dragStartY = buttonParams.y
                touchStartX = e.rawX
                touchStartY = e.rawY
                dragged = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - touchStartX).toInt()
                val dy = (e.rawY - touchStartY).toInt()
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(dx) > slop || abs(dy) > slop) dragged = true
                if (dragged) {
                    buttonParams.x = dragStartX + dx
                    buttonParams.y = dragStartY + dy
                    try {
                        windowManager.updateViewLayout(v, buttonParams)
                    } catch (ex: Exception) {
                        // النافذة أُزيلت أثناء السحب
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragged) {
                    prefs.edit()
                        .putInt(Prefs.KEY_BTN_X, buttonParams.x)
                        .putInt(Prefs.KEY_BTN_Y, buttonParams.y)
                        .apply()
                } else {
                    onButtonClick()
                }
            }
        }
        return true
    }

    private fun onButtonClick() {
        when (state) {
            State.READY -> startTranslation()
            State.TRANSLATING -> resetToReady() // الضغط أثناء الترجمة = إلغاء
            State.SHOWING -> clearOverlay()
        }
    }

    private fun updateLabel() {
        button?.setText(
            when (state) {
                State.READY -> R.string.btn_translate
                State.TRANSLATING -> R.string.btn_working
                State.SHOWING -> R.string.btn_cancel
            }
        )
    }

    /** يرجّع الزر فوق طبقة الترجمة حتى لا تغطيه. */
    private fun bringButtonToFront() {
        val b = button ?: return
        try {
            windowManager.removeView(b)
            windowManager.addView(b, buttonParams)
        } catch (e: Exception) {
            // تجاهل
        }
    }

    // ───────────────────────── الترجمة ─────────────────────────

    private fun getTranslator(direction: String): Translator {
        val current = translator
        if (current != null && translatorKey == direction) return current
        translator?.close()

        val source: String
        val target: String
        if (direction == Prefs.AR_EN) {
            source = TranslateLanguage.ARABIC
            target = TranslateLanguage.ENGLISH
        } else {
            source = TranslateLanguage.ENGLISH
            target = TranslateLanguage.ARABIC
        }
        val created = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )
        translator = created
        translatorKey = direction
        return created
    }

    private fun startTranslation() {
        val root = rootInActiveWindow
        if (root == null) {
            toast(R.string.err_no_content)
            return
        }

        val direction = Prefs.direction(this)
        val items = collectItems(root, direction)
        if (items.isEmpty()) {
            toast(R.string.msg_nothing)
            return
        }

        state = State.TRANSLATING
        updateLabel()
        val gen = ++generation
        val tr = getTranslator(direction)

        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (gen == generation) translateAll(tr, items, direction, gen)
            }
            .addOnFailureListener {
                if (gen == generation) {
                    toast(R.string.err_models)
                    resetToReady()
                }
            }
    }

    private fun translateAll(tr: Translator, items: List<TextItem>, direction: String, gen: Int) {
        val results = arrayOfNulls<String>(items.size)
        var pending = items.size

        fun done() {
            pending--
            if (pending == 0 && gen == generation) showOverlay(items, results, direction)
        }

        items.forEachIndexed { i, item ->
            val task = try {
                tr.translate(item.text)
            } catch (e: Exception) {
                null
            }
            if (task == null) {
                done()
            } else {
                task.addOnSuccessListener { results[i] = it }
                    .addOnCompleteListener { done() }
            }
        }
    }

    // ───────────────────────── قراءة الشاشة ─────────────────────────

    private fun collectItems(root: AccessibilityNodeInfo, direction: String): List<TextItem> {
        val out = ArrayList<TextItem>()
        val seen = HashSet<String>()
        val pattern = if (direction == Prefs.AR_EN) ARABIC else LATIN
        val minSize = dp(8)

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 60 || out.size >= MAX_ITEMS) return

            if (node.isVisibleToUser && !node.isPassword) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrEmpty() && pattern.containsMatchIn(text)) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() > minSize && r.height() > minSize) {
                        val key = r.flattenToString() + "|" + text
                        if (seen.add(key)) {
                            out.add(TextItem(text.take(MAX_TEXT_LENGTH), r))
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }

        walk(root, 0)
        return out
    }

    // ───────────────────────── عرض الترجمة ─────────────────────────

    private fun showOverlay(items: List<TextItem>, results: Array<String?>, direction: String) {
        removeOverlayView()

        val toArabic = direction == Prefs.EN_AR
        val container = FrameLayout(this)
        var count = 0

        for (i in items.indices) {
            val translated = results[i]?.trim()
            if (translated.isNullOrEmpty() || translated == items[i].text) continue

            val b = items[i].bounds
            val tv = TextView(this)
            tv.text = translated
            tv.setTextColor(Color.BLACK)
            tv.layoutDirection = if (toArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            tv.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            tv.setPadding(dp(4), dp(1), dp(4), dp(1))
            tv.background = GradientDrawable().apply {
                setColor(0xEEFFFFFF.toInt())
                cornerRadius = dp(4).toFloat()
            }
            // حجم الخط يتناسب مع مساحة النص الأصلي
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                tv, 6, 22, 1, TypedValue.COMPLEX_UNIT_SP
            )

            val lp = FrameLayout.LayoutParams(b.width(), b.height())
            lp.leftMargin = b.left
            lp.topMargin = b.top
            container.addView(tv, lp)
            count++
        }

        if (count == 0) {
            toast(R.string.msg_nothing)
            resetToReady()
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            windowManager.addView(container, params)
            overlay = container
        } catch (e: Exception) {
            resetToReady()
            return
        }

        bringButtonToFront()
        state = State.SHOWING
        updateLabel()
    }

    private fun removeOverlayView() {
        overlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // غير مضافة
            }
        }
        overlay = null
    }

    private fun resetToReady() {
        generation++
        state = State.READY
        updateLabel()
    }

    private fun clearOverlay() {
        if (!::windowManager.isInitialized) return
        removeOverlayView()
        resetToReady()
    }

    // ───────────────────────── أدوات ─────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_ITEMS = 150
        private const val MAX_TEXT_LENGTH = 1500
        private val ARABIC = Regex("[\\u0600-\\u06FF\\u0750-\\u077F]")
        private val LATIN = Regex("[A-Za-z]")
    }
}
