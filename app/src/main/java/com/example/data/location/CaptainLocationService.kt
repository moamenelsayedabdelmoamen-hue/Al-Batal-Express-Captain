package com.example.data.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.CaptainRepository
import com.example.data.LocationEligibilityResult
import com.example.data.LocationUploadResult
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class CaptainLocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var repository: CaptainRepository? = null
    private var eligibilityCheckJob: Job? = null
    @Volatile
    private var isInForeground: Boolean = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (eligibilityCheckerOverride == null && locationUploaderOverride == null) {
            try {
                repository = CaptainRepository.getInstance(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing repository in service", e)
            }
        }

        setupLocationCallback()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            GpsConfig.ACTION_START_LOCATION_SERVICE -> {
                startLocationTrackingSynchronously()
            }
            GpsConfig.ACTION_STOP_LOCATION_SERVICE -> {
                stopLocationTracking(markOfflineInFirestore = false)
                START_NOT_STICKY
            }
            null -> {
                // Defensive handling if the OS recreates the service with a null intent.
                if (CaptainLocationTracker.hasLocationPermission(this) &&
                    promoteToForegroundSynchronously("جاري التحقق من الشفت وتشغيل التتبع المباشر...")
                ) {
                    launchEligibilityCheckAndBeginUpdates()
                    START_STICKY
                } else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }
            else -> START_NOT_STICKY
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onNewLocation(location)
            }
        }
    }

    internal fun onNewLocation(location: Location) {
        Log.d(TAG, "New GPS location received: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")

        // Update Tracker state flow for UI
        CaptainLocationTracker.updateLocation(location)

        // Sync to Firestore via repository
        serviceScope.launch {
            try {
                val uploadResult = uploadLocation(location)
                when (uploadResult) {
                    is LocationUploadResult.Success -> Unit
                    is LocationUploadResult.TransientFailure -> {
                        // Never mark captain offline or kill the service on transient network/timeout errors.
                        Log.w(
                            TAG,
                            "Transient failure syncing GPS location; keeping service active without marking offline: ${uploadResult.cause.message}"
                        )
                    }
                    is LocationUploadResult.ConfirmedIneligible -> {
                        Log.i(
                            TAG,
                            "Confirmed ineligible (${uploadResult.reason}); stopping GPS and order listeners"
                        )
                        stopLocationTracking(markOfflineInFirestore = uploadResult.shouldMarkOffline)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error syncing location from service to Firestore", e)
            }
        }

        // Update notification subtext if wanted
        updateNotification("تم تحديث موقعك: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}")
    }

    /**
     * Promotes the service to foreground synchronously on the Main Thread inside `onStartCommand`
     * BEFORE any Firestore/network eligibility check runs, eliminating
     * `ForegroundServiceDidNotStartInTimeException` on slow or offline networks.
     */
    private fun startLocationTrackingSynchronously(): Int {
        if (!CaptainLocationTracker.hasLocationPermission(this)) {
            Log.w(TAG, "Location permission missing, stopping service")
            CaptainLocationTracker.updateStatus(GpsStatus.PermissionDenied)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForegroundSynchronously("جاري التحقق من الشفت وتشغيل التتبع المباشر...")) {
            return START_NOT_STICKY
        }

        launchEligibilityCheckAndBeginUpdates()
        return START_STICKY
    }

    private fun promoteToForegroundSynchronously(statusText: String): Boolean {
        return try {
            createNotificationChannel()
            val notification = buildForegroundNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    GpsConfig.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(GpsConfig.NOTIFICATION_ID, notification)
            }
            isInForeground = true
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException promoting CaptainLocationService to foreground", e)
            CaptainLocationTracker.updateStatus(GpsStatus.PermissionDenied)
            stopSelf()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote CaptainLocationService to foreground", e)
            stopSelf()
            false
        }
    }

    private fun launchEligibilityCheckAndBeginUpdates() {
        eligibilityCheckJob?.cancel()
        val timeoutMs = eligibilityTimeoutMsOverride ?: ELIGIBILITY_CHECK_TIMEOUT_MS
        eligibilityCheckJob = serviceScope.launch {
            val result = try {
                withTimeoutOrNull(timeoutMs) {
                    checkEligibility()
                } ?: LocationEligibilityResult.NetworkError(
                    TimeoutException("Location eligibility check timed out after ${timeoutMs}ms")
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LocationEligibilityResult.NetworkError(e)
            }

            when (result) {
                is LocationEligibilityResult.Eligible -> {
                    startLocationTrackingAuthorized()
                }
                is LocationEligibilityResult.Ineligible -> {
                    Log.w(TAG, "GPS start denied (confirmed ineligible: ${result.reason}); stopping without overwriting server status")
                    stopLocationTracking(markOfflineInFirestore = false)
                }
                is LocationEligibilityResult.NetworkError -> {
                    // CRITICAL: Never mark the captain offline in Firestore when the server is unreachable or times out.
                    Log.w(
                        TAG,
                        "GPS eligibility check could not reach server (${result.cause.message}); stopping service cleanly without marking captain offline"
                    )
                    stopLocationTracking(markOfflineInFirestore = false)
                }
            }
        }
    }

    private suspend fun checkEligibility(): LocationEligibilityResult {
        eligibilityCheckerOverride?.let { return it() }
        val repo = repository
            ?: return LocationEligibilityResult.NetworkError(IllegalStateException("Repository unavailable"))
        return repo.checkLocationTrackingEligibility()
    }

    private suspend fun uploadLocation(location: Location): LocationUploadResult {
        locationUploaderOverride?.let { return it(location) }
        val repo = repository
            ?: return LocationUploadResult.TransientFailure(IllegalStateException("Repository unavailable"))
        return repo.updateCaptainGpsLocationDetailed(location)
    }

    private fun startLocationTrackingAuthorized() {
        if (!CaptainLocationTracker.hasLocationPermission(this)) {
            Log.w(TAG, "Location permission revoked before updates began, stopping service")
            CaptainLocationTracker.updateStatus(GpsStatus.PermissionDenied)
            stopLocationTracking(markOfflineInFirestore = false)
            return
        }

        if (!CaptainLocationTracker.isGpsProviderEnabled(this)) {
            Log.w(TAG, "GPS Provider is disabled")
            CaptainLocationTracker.updateStatus(GpsStatus.GpsDisabled)
        } else {
            CaptainLocationTracker.updateStatus(GpsStatus.Connecting)
        }

        CaptainLocationTracker.setTracking(true)
        updateNotification("جاري تحديد موقعك عبر الأقمار الصناعية GPS...")

        if (!skipLocationClientForTesting) {
            requestLocationUpdates()
            runCatching {
                repository?.startRealtimeOrdersListener(
                    CaptainRepository.ORDERS_LISTENER_CONSUMER_LOCATION_SERVICE
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                GpsConfig.UPDATE_INTERVAL_MS
            ).apply {
                setMinUpdateIntervalMillis(GpsConfig.FASTEST_INTERVAL_MS)
                setMinUpdateDistanceMeters(GpsConfig.MIN_DISPLACEMENT_METERS)
                setWaitForAccurateLocation(false)
            }.build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // Also request last known location immediately for instant startup
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                if (lastLoc != null) {
                    onNewLocation(lastLoc)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during requestLocationUpdates", e)
            CaptainLocationTracker.updateStatus(GpsStatus.PermissionDenied)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
        }
    }

    private fun stopLocationTracking(markOfflineInFirestore: Boolean) {
        Log.i(TAG, "Stopping location tracking service (markOfflineInFirestore=$markOfflineInFirestore)")
        eligibilityCheckJob?.cancel()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }

        if (markOfflineInFirestore) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val override = markOfflineOverride
                    if (override != null) {
                        override()
                    } else {
                        repository?.markCaptainOfflineInFirestore()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "Error marking captain offline in Firestore: ${e.message}")
                }
            }
        }

        CaptainLocationTracker.setTracking(false)
        runCatching {
            if (markOfflineInFirestore) {
                repository?.stopRealtimeOrdersListener()
            } else {
                repository?.releaseOrdersListener(
                    CaptainRepository.ORDERS_LISTENER_CONSUMER_LOCATION_SERVICE
                )
            }
        }
        if (isInForeground) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            isInForeground = false
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GpsConfig.NOTIFICATION_CHANNEL_ID,
                GpsConfig.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار تشغيل خدمة بث وتتبع موقع الكابتن المباشر"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIconRes = try {
            R.drawable.ic_notification_captain
        } catch (_: Exception) {
            android.R.drawable.ic_menu_mylocation
        }

        return NotificationCompat.Builder(this, GpsConfig.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("البطل إكسبريس - الكابتن متصل ⚡")
            .setContentText(statusText)
            .setSmallIcon(smallIconRes)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.notify(GpsConfig.NOTIFICATION_ID, buildForegroundNotification(statusText))
        } catch (e: Exception) {
            Log.w(TAG, "Could not update notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        eligibilityCheckJob?.cancel()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        serviceScope.cancel()
        runCatching {
            repository?.releaseOrdersListener(
                CaptainRepository.ORDERS_LISTENER_CONSUMER_LOCATION_SERVICE
            )
        }
        isInForeground = false
        CaptainLocationTracker.setTracking(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CaptainLocationService"
        const val ELIGIBILITY_CHECK_TIMEOUT_MS = 15_000L

        internal var eligibilityCheckerOverride: (suspend () -> LocationEligibilityResult)? = null
        internal var locationUploaderOverride: (suspend (Location) -> LocationUploadResult)? = null
        internal var markOfflineOverride: (suspend () -> Unit)? = null
        internal var eligibilityTimeoutMsOverride: Long? = null
        internal var skipLocationClientForTesting: Boolean = false

        internal fun resetTestHooks() {
            eligibilityCheckerOverride = null
            locationUploaderOverride = null
            markOfflineOverride = null
            eligibilityTimeoutMsOverride = null
            skipLocationClientForTesting = false
        }
    }
}
