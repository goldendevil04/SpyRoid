package com.myrat.app.data

import com.google.gson.annotations.SerializedName

data class Sms(
    @SerializedName("sender") val sender: String,
    @SerializedName("message") val message: String,
    @SerializedName("timestamp") val timestamp: Long
)