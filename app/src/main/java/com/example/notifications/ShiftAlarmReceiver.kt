package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.CaptainRepository
import com.example.data.LocationEligibilityResult
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import com.example.data.location.CaptainLocationTracker
import com.example.data.location.LocationServiceStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class ShiftAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ShiftNotificationScheduler.ACTION_SHIFT_START_ALERT &&
            action != ShiftNotificationScheduler.ACTION_SHIFT_END_ALERT &&
            action != ShiftNotificationScheduler.ACTION_SHIFT_READY_ALERT
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val expectedUid = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_CAPTAIN_UID).orEmpty()
        val dayNameArabic = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_DAY_NAME_ARABIC).orEmpty()
        val startFormatted = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_START_FORMATTED).orEmpty()
        val endFormatted = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_END_FORMATTED).orEmpty()
        val dedupeKey = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_DEDUPE_KEY).orEmpty()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleAlarmAction(
                    appContext = appContext,
                    action = action,
                    expectedUid = expectedUid,
                    dayNameArabic = dayNameArabic,
                    startFormatted = startFormatted,
                    endFormatted = endFormatted,
                    dedupeKey = dedupeKey
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Error handling shift alarm: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun handleAlarmAction(
        appContext: Context,
        action: String,
        expectedUid: String,
        dayNameArabic: String,
        startFormatted: String,
        endFormatted: String,
        dedupeKey: String
    ) {
        val db = AppDatabase.getDatabase(appContext)
        val captain = db.captainDao().getCaptain()
        if (captain == null || !captain.isLoggedIn) {
            ShiftNotificationScheduler.cancelAllShiftAlarms(appContext)
            return
        }
        if (expectedUid.isNotBlank() && captain.uid.isNotBlank() && expectedUid != captain.uid) {
            return
        }

        val shifts = if (captain.uid.isNotBlank()) {
            db.shiftDao().getShiftsForCaptainFlow(captain.uid).firstOrNull()
                ?: db.shiftDao().getAllShiftsFlow().firstOrNull()
                ?: emptyList()
        } else {
            db.shiftDao().getAllShiftsFlow().firstOrNull() ?: emptyList()
        }
        val repository = if (
            readinessCheckerOverride == null ||
            eligibilityCheckerOverride == null ||
            setOnlineStatusOverride == null
        ) {
            CaptainRepository(
                captainDao = db.captainDao(),
                orderDao = db.orderDao(),
                shiftDao = db.shiftDao(),
                context = appContext
            )
        } else {
            null
        }

        when (action) {
            ShiftNotificationScheduler.ACTION_SHIFT_READY_ALERT -> {
                CaptainNotificationManager.showShiftReadyWindowNotification(
                    context = appContext,
                    dayNameArabic = dayNameArabic,
                    startFormatted = startFormatted,
                    endFormatted = endFormatted,
                    dedupeKey = dedupeKey
                )
            }
            ShiftNotificationScheduler.ACTION_SHIFT_START_ALERT -> {
                CaptainNotificationManager.showShiftStartNotification(
                    context = appContext,
                    dayNameArabic = dayNameArabic,
                    startFormatted = startFormatted,
                    endFormatted = endFormatted,
                    dedupeKey = dedupeKey
                )
                val activeNow = ShiftManager.calculateShiftStatus(shifts) is ShiftStatus.Active
                val hasReadiness = try {
                    if (captain.isOnline && activeNow) {
                        readinessCheckerOverride?.invoke()
                            ?: repository?.hasConfirmedShiftReadiness()
                            ?: false
                    } else {
                        false
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "Network/transient error verifying shift readiness in alarm; keeping captain status unchanged: ${e.message}")
                    false
                }

                if (hasReadiness &&
                    CaptainLocationTracker.hasLocationPermission(appContext) &&
                    CaptainLocationTracker.isGpsProviderEnabled(appContext)
                ) {
                    val promoteOk = try {
                        val override = setOnlineStatusOverride
                        if (override != null) {
                            override(true)
                        } else {
                            repository?.setOnlineStatus(true)
                        }
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.w(TAG, "Error promoting ready captain to working at shift start: ${e.message}")
                        false
                    }

                    if (promoteOk) {
                        val eligibility = eligibilityCheckerOverride?.invoke()
                            ?: repository?.checkLocationTrackingEligibility()
                            ?: LocationEligibilityResult.Ineligible("repository_unavailable")
                        when (eligibility) {
                            is LocationEligibilityResult.Eligible -> {
                                when (val startResult = CaptainLocationTracker.startServiceSafely(appContext)) {
                                    is LocationServiceStartResult.Started -> {
                                        Log.i(TAG, "Location service started from shift-start alarm")
                                    }
                                    is LocationServiceStartResult.BlockedByBackgroundRestriction -> {
                                        // Android 12+ restricts starting a location FGS from a background BroadcastReceiver.
                                        // Provide an actionable high-priority notification so the captain opens the app
                                        // and starts GPS legally from the foreground.
                                        Log.i(
                                            TAG,
                                            "Background location FGS blocked (${startResult.reason}); posting actionable open-app notification"
                                        )
                                        CaptainNotificationManager.showShiftStartOpenAppForGpsNotification(
                                            context = appContext,
                                            dayNameArabic = dayNameArabic,
                                            startFormatted = startFormatted,
                                            endFormatted = endFormatted,
                                            dedupeKey = dedupeKey
                                        )
                                    }
                                    is LocationServiceStartResult.Failed -> {
                                        Log.w(TAG, "Failed starting location service from shift alarm: ${startResult.cause.message}")
                                        CaptainNotificationManager.showShiftStartOpenAppForGpsNotification(
                                            context = appContext,
                                            dayNameArabic = dayNameArabic,
                                            startFormatted = startFormatted,
                                            endFormatted = endFormatted,
                                            dedupeKey = dedupeKey
                                        )
                                    }
                                }
                            }
                            is LocationEligibilityResult.NetworkError -> {
                                Log.w(TAG, "Network error checking location eligibility at shift start; leaving captain online without marking offline: ${eligibility.cause.message}")
                            }
                            is LocationEligibilityResult.Ineligible -> {
                                Log.i(TAG, "Captain not eligible for GPS tracking at shift start (${eligibility.reason})")
                            }
                        }
                    }
                }
            }
            ShiftNotificationScheduler.ACTION_SHIFT_END_ALERT -> {
                CaptainNotificationManager.showShiftEndNotification(
                    context = appContext,
                    dayNameArabic = dayNameArabic,
                    endFormatted = endFormatted,
                    dedupeKey = dedupeKey
                )
                CaptainLocationTracker.stopService(appContext)
                val override = setOnlineStatusOverride
                if (override != null) {
                    override(false)
                } else {
                    repository?.setOnlineStatus(false)
                }
            }
        }

        // Reschedule the next week's alarms even if the app stays closed.
        if (shifts.isNotEmpty() && captain.uid.isNotBlank()) {
            ShiftNotificationScheduler.scheduleShiftNotifications(
                context = appContext,
                shifts = shifts,
                captainUid = captain.uid,
                nowMs = System.currentTimeMillis() + 61_000L
            )
        }
    }

    companion object {
        private const val TAG = "ShiftAlarmReceiver"

        internal var readinessCheckerOverride: (suspend () -> Boolean)? = null
        internal var eligibilityCheckerOverride: (suspend () -> LocationEligibilityResult)? = null
        internal var setOnlineStatusOverride: (suspend (Boolean) -> Unit)? = null

        internal fun resetTestHooks() {
            readinessCheckerOverride = null
            eligibilityCheckerOverride = null
            setOnlineStatusOverride = null
        }
    }
}
