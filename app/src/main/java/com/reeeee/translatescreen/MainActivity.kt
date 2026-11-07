package com.reeeee.translatescreen

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private lateinit var tvModels: TextView
    private lateinit var tvService: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnEnable: Button
    private lateinit var btnDirection: Button

    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModels = findViewById(R.id.tvModels)
        tvService = findViewById(R.id.tvService)
        btnDownload = findViewById(R.id.btnDownload)
        btnEnable = findViewById(R.id.btnEnable)
        btnDirection = findViewById(R.id.btnDirection)

        btnDownload.setOnClickListener { downloadModels() }

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnDirection.setOnClickListener {
            val next = if (Prefs.direction(this) == Prefs.AR_EN) Prefs.EN_AR else Prefs.AR_EN
            Prefs.get(this).edit().putString(Prefs.KEY_DIRECTION, next).apply()
            updateDirectionLabel()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDirectionLabel()
        updateServiceStatus()
        if (!downloading) refreshModelStatus()
    }

    private fun updateDirectionLabel() {
        btnDirection.setText(
            if (Prefs.direction(this) == Prefs.AR_EN) R.string.dir_ar_en else R.string.dir_en_ar
        )
    }

    private fun updateServiceStatus() {
        val on = isServiceEnabled()
        tvService.setText(if (on) R.string.service_on else R.string.service_off)
        tvService.setTextColor(
            getColor(if (on) R.color.ok else R.color.text_main)
        )
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, TranslateAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** يتأكد هل نموذجا العربية والإنجليزية محمّلان على الجهاز. */
    private fun refreshModelStatus() {
        val manager = RemoteModelManager.getInstance()
        val ar = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
        val en = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
        manager.isModelDownloaded(ar).addOnSuccessListener { arOk ->
            manager.isModelDownloaded(en).addOnSuccessListener { enOk ->
                if (downloading) return@addOnSuccessListener
                val ready = arOk && enOk
                tvModels.setText(if (ready) R.string.models_ready else R.string.models_missing)
                tvModels.setTextColor(getColor(if (ready) R.color.ok else R.color.text_main))
            }
        }
    }

    /** تحميل النماذج مرة واحدة (يحتاج إنترنت هذه المرة فقط). */
    private fun downloadModels() {
        if (downloading) return
        downloading = true
        btnDownload.isEnabled = false
        tvModels.setText(R.string.models_downloading)
        tvModels.setTextColor(getColor(R.color.text_main))

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ARABIC)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )

        var ok = false
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { ok = true }
            .addOnCompleteListener {
                downloading = false
                btnDownload.isEnabled = true
                translator.close()
                if (ok) {
                    refreshModelStatus()
                } else {
                    tvModels.setText(R.string.models_failed)
                }
            }
    }
}
