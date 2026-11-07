package com.reeeee.translatescreen

import android.content.Context
import android.content.SharedPreferences

/** إعدادات التطبيق البسيطة: اتجاه الترجمة ومكان الزر العائم. */
object Prefs {
    private const val FILE = "translate_screen"

    const val KEY_DIRECTION = "direction"
    const val KEY_BTN_X = "btn_x"
    const val KEY_BTN_Y = "btn_y"

    /** عربي إلى إنجليزي */
    const val AR_EN = "ar_en"

    /** إنجليزي إلى عربي */
    const val EN_AR = "en_ar"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun direction(context: Context): String =
        get(context).getString(KEY_DIRECTION, AR_EN) ?: AR_EN
}
