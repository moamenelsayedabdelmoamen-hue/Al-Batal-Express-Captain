package com.example.data.location

/**
 * Configurable parameters for Captain GPS Tracking in El-Batal Express.
 * Balances battery efficiency and Firestore write limits with real-time accuracy for Dispatch & Admin Dashboard.
 */
object GpsConfig {
    /** Target GPS update interval while working (5 minutes) */
    const val UPDATE_INTERVAL_MS = 300_000L

    /** Do not request faster GPS callbacks than the five-minute cadence */
    const val FASTEST_INTERVAL_MS = 300_000L

    /** Distance filter disabled: movement must never increase Firestore write frequency */
    const val MIN_DISPLACEMENT_METERS = 0f

    /** Minimum interval between Firestore GPS writes while working */
    const val FIRESTORE_HEARTBEAT_INTERVAL_MS = 300_000L

    /** Threshold to consider a GPS fix stale (10 minutes, allowing the five-minute update cadence) */
    const val STALE_GPS_THRESHOLD_MS = 600_000L

    /** Notification constants */
    const val NOTIFICATION_CHANNEL_ID = "elbatal_captain_location_channel"
    const val NOTIFICATION_CHANNEL_NAME = "خدمة التتبع المباشر - البطل إكسبريس"
    const val NOTIFICATION_ID = 1001

    /** Intent Actions */
    const val ACTION_START_LOCATION_SERVICE = "com.example.action.START_LOCATION_SERVICE"
    const val ACTION_STOP_LOCATION_SERVICE = "com.example.action.STOP_LOCATION_SERVICE"
}
