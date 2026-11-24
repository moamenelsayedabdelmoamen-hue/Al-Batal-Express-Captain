package com.example.data.location

import android.Manifest
import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class LocationServiceStartResult {
    object Started : LocationServiceStartResult()
    data class BlockedByBackgroundRestriction(val reason: String) : LocationServiceStartResult()
    data class Failed(val cause: Throwable) : LocationServiceStartResult()
}

object CaptainLocationTracker {
    private const val TAG = "CaptainLocationTracker"

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _gpsStatus = MutableStateFlow<GpsStatus>(GpsStatus.Inactive)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    /** Optional test hook to deterministically simulate foreground/background process importance in Robolectric. */
    internal var isAppInForegroundOverrideForTesting: (() -> Boolean)? = null

    fun updateLocation(location: Location) {
        _currentLocation.value = location
        _gpsStatus.value = GpsStatus.Active(location)
    }

    fun updateStatus(status: GpsStatus) {
        _gpsStatus.value = status
        if (status is GpsStatus.Inactive || status is GpsStatus.PermissionDenied || status is GpsStatus.GpsDisabled) {
            _currentLocation.value = null
        }
    }

    fun setTracking(tracking: Boolean) {
        _isTracking.value = tracking
        if (!tracking) {
            _gpsStatus.value = GpsStatus.Inactive
            _currentLocation.value = null
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission(context)
        }
    }

    fun isAppInForeground(): Boolean {
        isAppInForegroundOverrideForTesting?.let { return it() }
        return try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query process memory state: ${e.message}")
            false
        }
    }

    /**
     * On Android 12+ (API 31+), starting a `location` Foreground Service from the background is
     * restricted unless the app is currently in the foreground (or explicitly holds background
     * location permission with a valid exemption).
     */
    fun canStartLocationForegroundService(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return isAppInForeground() || hasBackgroundLocationPermission(context)
    }

    fun isGpsProviderEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val networkEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        return gpsEnabled || networkEnabled
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun startServiceSafely(context: Context): LocationServiceStartResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canStartLocationForegroundService(context)) {
            Log.w(TAG, "Skipping background startForegroundService for CaptainLocationService on Android 12+")
            return LocationServiceStartResult.BlockedByBackgroundRestriction(
                "android_12_plus_background_location_fgs_restricted"
            )
        }
        val intent = Intent(context, CaptainLocationService::class.java).apply {
            action = GpsConfig.ACTION_START_LOCATION_SERVICE
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            LocationServiceStartResult.Started
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "ForegroundServiceStartNotAllowedException starting CaptainLocationService: ${e.message}")
                LocationServiceStartResult.BlockedByBackgroundRestriction("foreground_service_start_not_allowed")
            } else if (e is SecurityException) {
                Log.w(TAG, "SecurityException starting CaptainLocationService: ${e.message}")
                LocationServiceStartResult.BlockedByBackgroundRestriction("security_exception")
            } else {
                Log.e(TAG, "Unexpected error starting CaptainLocationService: ${e.message}", e)
                LocationServiceStartResult.Failed(e)
            }
        }
    }

    fun startService(context: Context) {
        startServiceSafely(context)
    }

    fun stopService(context: Context) {
        val intent = Intent(context, CaptainLocationService::class.java)
        runCatching {
            context.stopService(intent)
        }.onFailure { e ->
            Log.w(TAG, "Non-fatal error stopping CaptainLocationService: ${e.message}")
        }
        setTracking(false)
    }
}
