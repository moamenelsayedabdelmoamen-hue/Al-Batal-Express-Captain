package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.CaptainEntity
import com.example.data.LocationEligibilityResult
import com.example.data.LocationUploadResult
import com.example.data.ShiftEntity
import com.example.data.ShiftStatus
import com.example.data.StartupSessionState
import com.example.data.location.CaptainLocationService
import com.example.data.location.CaptainLocationTracker
import com.example.data.location.GpsConfig
import com.example.data.location.LocationServiceStartResult
import com.example.notifications.CaptainNotificationManager
import com.example.notifications.ShiftAlarmReceiver
import com.example.notifications.ShiftNotificationScheduler
import com.example.ui.CaptainViewModel
import com.example.ui.navigation.ScreenRoute
import com.example.ui.screens.AccountApprovalScreen
import com.example.ui.theme.ElBatalTheme
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h1400dp")
class Batch1CriticalFixesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        CaptainLocationService.resetTestHooks()
        ShiftAlarmReceiver.resetTestHooks()
        CaptainLocationTracker.isAppInForegroundOverrideForTesting = null
        CaptainLocationTracker.setTracking(false)
        CaptainNotificationManager.clearNotificationState(appContext)
        ShiftNotificationScheduler.cancelAllShiftAlarms(appContext)

        Shadows.shadowOf(appContext).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @After
    fun tearDown() {
        CaptainLocationService.resetTestHooks()
        ShiftAlarmReceiver.resetTestHooks()
        CaptainLocationTracker.isAppInForegroundOverrideForTesting = null
        CaptainLocationTracker.setTracking(false)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. CaptainLocationService: Slow internet -> synchronous startForeground prevents FGS timeout
    // ---------------------------------------------------------------------------------------------
    @Test
    fun locationService_slowInternet_callsStartForegroundSynchronouslyBeforeFirestoreResolves() {
        runBlocking {
        val gate = CompletableDeferred<LocationEligibilityResult>()
        val markOfflineCalls = AtomicInteger(0)

        CaptainLocationService.skipLocationClientForTesting = true
        CaptainLocationService.eligibilityCheckerOverride = { gate.await() }
        CaptainLocationService.markOfflineOverride = { markOfflineCalls.incrementAndGet() }

        val controller = Robolectric.buildService(CaptainLocationService::class.java).create()
        val service = controller.get()
        val shadowService = Shadows.shadowOf(service)

        val startIntent = Intent(appContext, CaptainLocationService::class.java).apply {
            action = GpsConfig.ACTION_START_LOCATION_SERVICE
        }
        controller.withIntent(startIntent).startCommand(0, 0)

        // MUST already be in foreground synchronously before slow Firestore check returns!
        assertNotNull(
            "startForeground() must be called synchronously inside onStartCommand before network I/O",
            shadowService.lastForegroundNotification
        )
        assertEquals(GpsConfig.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)
        assertFalse("Service must not be stopped while awaiting slow network response", shadowService.isStoppedBySelf)

        // Now complete slow network eligibility check as Eligible
        gate.complete(LocationEligibilityResult.Eligible)
        waitUntil(timeoutMs = 2_000L) { CaptainLocationTracker.isTracking.value }

        assertTrue("CaptainLocationTracker.isTracking must become true once server confirms eligible", CaptainLocationTracker.isTracking.value)
        assertFalse("Service must remain alive after eligible confirmation", shadowService.isStoppedBySelf)
        assertEquals("markCaptainOfflineInFirestore must never be called", 0, markOfflineCalls.get())

        controller.destroy()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. CaptainLocationService: Offline / NetworkError & Timeout -> NO FGS crash & NEVER offline
    // ---------------------------------------------------------------------------------------------
    @Test
    fun locationService_offlineOrTimeout_promotesForegroundThenStopsWithoutMarkingCaptainOffline() {
        runBlocking {
        val markOfflineCalls = AtomicInteger(0)

        CaptainLocationService.skipLocationClientForTesting = true
        CaptainLocationService.eligibilityTimeoutMsOverride = 120L
        CaptainLocationService.eligibilityCheckerOverride = {
            // Simulate hung network / offline timeout
            delay(2_000L)
            LocationEligibilityResult.Eligible
        }
        CaptainLocationService.markOfflineOverride = { markOfflineCalls.incrementAndGet() }

        val controller = Robolectric.buildService(CaptainLocationService::class.java).create()
        val service = controller.get()
        val shadowService = Shadows.shadowOf(service)

        val startIntent = Intent(appContext, CaptainLocationService::class.java).apply {
            action = GpsConfig.ACTION_START_LOCATION_SERVICE
        }
        controller.withIntent(startIntent).startCommand(0, 0)

        // Synchronous foreground promotion prevents ForegroundServiceDidNotStartInTimeException
        assertNotNull(
            "startForeground() must run synchronously even when network later times out",
            shadowService.lastForegroundNotification
        )
        assertEquals(GpsConfig.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)

        // Wait for eligibility timeout to complete and stop service cleanly
        waitUntil(timeoutMs = 2_000L) { shadowService.isStoppedBySelf }

        assertTrue("Service should stop cleanly after network timeout", shadowService.isStoppedBySelf)
        assertTrue("Foreground notification should be removed on clean stop", shadowService.isForegroundStopped)
        assertEquals(
            "CRITICAL: Captain must NEVER be marked offline in Firestore on network timeout/outage",
            0,
            markOfflineCalls.get()
        )

        controller.destroy()
        }
    }

    @Test
    fun locationService_activeTracking_transientNetworkErrorKeepsAlive_butConfirmedShiftEndMarksOffline() {
        runBlocking {
        val markOfflineCalls = AtomicInteger(0)
        var nextUploadResult: LocationUploadResult = LocationUploadResult.TransientFailure(IOException("Offline"))

        CaptainLocationService.skipLocationClientForTesting = true
        CaptainLocationService.eligibilityCheckerOverride = { LocationEligibilityResult.Eligible }
        CaptainLocationService.locationUploaderOverride = { nextUploadResult }
        CaptainLocationService.markOfflineOverride = { markOfflineCalls.incrementAndGet() }

        val controller = Robolectric.buildService(CaptainLocationService::class.java).create()
        val service = controller.get()
        val shadowService = Shadows.shadowOf(service)

        controller.withIntent(
            Intent(appContext, CaptainLocationService::class.java).apply {
                action = GpsConfig.ACTION_START_LOCATION_SERVICE
            }
        ).startCommand(0, 0)
        waitUntil(timeoutMs = 2_000L) { CaptainLocationTracker.isTracking.value }

        val loc = Location("gps").apply {
            latitude = 30.0444
            longitude = 31.2357
            accuracy = 10f
        }

        // Transient network outage during GPS upload -> must stay running and NOT mark offline
        service.onNewLocation(loc)
        delay(200L)
        assertFalse("Service must stay active on transient network error", shadowService.isStoppedBySelf)
        assertTrue("Tracking must stay active on transient network error", CaptainLocationTracker.isTracking.value)
        assertEquals("Transient network error must NOT mark captain offline", 0, markOfflineCalls.get())

        // Confirmed shift end -> must stop service and mark offline
        nextUploadResult = LocationUploadResult.ConfirmedIneligible(
            reason = "no_active_shift",
            shouldMarkOffline = true
        )
        service.onNewLocation(loc)
        waitUntil(timeoutMs = 2_000L) { shadowService.isStoppedBySelf && markOfflineCalls.get() == 1 }

        assertTrue("Confirmed shift end must stop service", shadowService.isStoppedBySelf)
        assertEquals("Confirmed shift end must mark captain offline once", 1, markOfflineCalls.get())

        controller.destroy()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. CaptainLocationTracker.stopService(): Safe background stop without startService()
    // ---------------------------------------------------------------------------------------------
    @Test
    fun locationTracker_stopServiceInBackground_usesStopServiceNeverStartService() {
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()
        drainStoppedServices(shadowApp)

        CaptainLocationTracker.isAppInForegroundOverrideForTesting = { false }
        CaptainLocationTracker.setTracking(true)
        assertTrue(CaptainLocationTracker.isTracking.value)

        CaptainLocationTracker.stopService(appContext)

        assertNull(
            "stopService() must NEVER call context.startService() (crashes in background on Android 8+)",
            shadowApp.nextStartedService
        )
        val stoppedIntent = shadowApp.nextStoppedService
        assertNotNull("stopService() must call context.stopService()", stoppedIntent)
        assertEquals(CaptainLocationService::class.java.name, stoppedIntent?.component?.className)
        assertFalse("Tracking state must reset to false", CaptainLocationTracker.isTracking.value)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. ShiftAlarmReceiver: Android 12+ background FGS restriction safe fallback + safe end stop
    // ---------------------------------------------------------------------------------------------
    @Test
    fun shiftAlarmReceiver_backgroundOnAndroid12Plus_skipsProhibitedFgsAndPostsActionableNotification() {
        runBlocking {
        val db = AppDatabase.getDatabase(appContext)
        val uid = "cap_alarm_bg_1"
        db.captainDao().insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن شفت",
                phoneNumber = "01012345678",
                email = "shift@elbatal.com",
                status = "active",
                isOnline = true,
                isLoggedIn = true
            )
        )
        // Seed an active shift covering the entire current Cairo day
        val todayDow = com.example.data.ShiftManager.getCurrentCairoCalendar().get(Calendar.DAY_OF_WEEK)
        db.shiftDao().atomicReplaceCaptainShifts(
            uid,
            listOf(
                ShiftEntity(
                    id = "${uid}_today",
                    captainUid = uid,
                    dayOfWeek = todayDow,
                    dayNameArabic = "اليوم",
                    startTime = "00:00",
                    endTime = "23:59",
                    isDayOff = false
                )
            )
        )

        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()
        drainStoppedServices(shadowApp)
        val notifManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.cancelAll()

        // Simulate app in background on API 36 without ACCESS_BACKGROUND_LOCATION
        CaptainLocationTracker.isAppInForegroundOverrideForTesting = { false }
        Shadows.shadowOf(appContext).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        // Direct check on startServiceSafely
        val directResult = CaptainLocationTracker.startServiceSafely(appContext)
        assertTrue(
            "startServiceSafely must return BlockedByBackgroundRestriction in background on API 36",
            directResult is LocationServiceStartResult.BlockedByBackgroundRestriction
        )

        val onlineUpdates = mutableListOf<Boolean>()
        ShiftAlarmReceiver.readinessCheckerOverride = { true }
        ShiftAlarmReceiver.eligibilityCheckerOverride = { LocationEligibilityResult.Eligible }
        ShiftAlarmReceiver.setOnlineStatusOverride = { onlineUpdates.add(it) }

        val receiver = ShiftAlarmReceiver()
        receiver.handleAlarmAction(
            appContext = appContext,
            action = ShiftNotificationScheduler.ACTION_SHIFT_START_ALERT,
            expectedUid = uid,
            dayNameArabic = "الأحد",
            startFormatted = "12:00 م",
            endFormatted = "8:00 م",
            dedupeKey = "2026-11-23_1_start"
        )

        // Must NOT attempt prohibited background startForegroundService
        assertNull(
            "ShiftAlarmReceiver must NOT call startForegroundService for location FGS from background on Android 12+",
            shadowApp.nextStartedService
        )
        assertEquals("Ready captain should still be promoted to working", listOf(true), onlineUpdates)

        // Must post actionable fallback notification telling captain to open app for GPS
        val postedTitles = Shadows.shadowOf(notifManager).allNotifications
            .map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }
        assertTrue(
            "Expected shift-start notification, got: $postedTitles",
            postedTitles.any { it.contains("بدأ شفت عملك") }
        )
        assertTrue(
            "Expected actionable open-app GPS fallback notification, got: $postedTitles",
            postedTitles.any { it.contains("افتح التطبيق لتفعيل GPS") }
        )

        // Now test ACTION_SHIFT_END_ALERT in background -> safe stopService, no startService, sets offline
        shadowApp.clearStartedServices()
        drainStoppedServices(shadowApp)
        receiver.handleAlarmAction(
            appContext = appContext,
            action = ShiftNotificationScheduler.ACTION_SHIFT_END_ALERT,
            expectedUid = uid,
            dayNameArabic = "الأحد",
            startFormatted = "12:00 م",
            endFormatted = "8:00 م",
            dedupeKey = "2026-11-23_1_end"
        )

        assertNull("Shift end in background must NEVER call startService()", shadowApp.nextStartedService)
        assertNotNull("Shift end in background must call stopService()", shadowApp.nextStoppedService)
        assertEquals(listOf(true, false), onlineUpdates)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. CaptainViewModel 1-Second Ticker: Network outage recovery (shift start & end) + Cancellation
    // ---------------------------------------------------------------------------------------------
    @Test
    fun ticker_survivesNetworkExceptions_recoversShiftStartAndEndWhenInternetReturns_andRethrowsCancellation() {
        runBlocking {
        val store = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            store,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(appContext)
        )[CaptainViewModel::class.java]
        try {
        CaptainLocationTracker.isAppInForegroundOverrideForTesting = { true }

        val shift = ShiftEntity(
            id = "cap_tick_1",
            captainUid = "cap_tick",
            dayOfWeek = Calendar.MONDAY,
            dayNameArabic = "الاثنين",
            startTime = "10:00",
            endTime = "18:00",
            isDayOff = false
        )
        var currentShiftStatus: ShiftStatus = ShiftStatus.UpcomingSoon(
            shift = shift,
            millisUntilStart = 15L * 60_000L,
            countdownFormatted = "00:15:00",
            startFormatted = "10:00 ص",
            endFormatted = "6:00 م"
        )
        var networkOnline = false
        var captainOnline = true
        val onlineTransitions = mutableListOf<Boolean>()
        val syncedOrdersCount = AtomicInteger(0)
        val pendingOfflineSynced = AtomicBoolean(false)
        var pendingOfflineNeedsRetry = false

        vm.tickerRetryBackoffMs = 0L
        vm.tickerShiftStatusOverride = { currentShiftStatus }
        vm.tickerCaptainLoggedInOverride = { true }
        vm.tickerCaptainOnlineOverride = { captainOnline }
        vm.tickerShiftReadinessOverride = {
            if (!networkOnline) throw IOException("No internet connection")
            true
        }
        vm.tickerLocationEligibilityOverride = {
            if (!networkOnline) {
                LocationEligibilityResult.NetworkError(IOException("No internet connection"))
            } else {
                LocationEligibilityResult.Eligible
            }
        }
        vm.tickerSetOnlineStatusOverride = { online ->
            onlineTransitions.add(online)
            captainOnline = online
            if (!online && !networkOnline) {
                pendingOfflineNeedsRetry = true
                throw IOException("Offline during shift end sync")
            }
        }
        vm.tickerSyncOrdersOverride = { syncedOrdersCount.incrementAndGet() }
        vm.tickerSyncPendingOfflineOverride = {
            if (pendingOfflineNeedsRetry && networkOnline) {
                pendingOfflineNeedsRetry = false
                pendingOfflineSynced.set(true)
            }
            !pendingOfflineNeedsRetry
        }

        val state = CaptainViewModel.TickerLoopState()

        // Tick 1: Before shift (UpcomingSoon, ready)
        vm.runSingleTickerCycle(state, nowMs = 1_000L)
        assertEquals(1, state.tickCount)
        assertTrue(state.previousLoadedShiftStatus is ShiftStatus.UpcomingSoon)

        // Tick 2: Shift starts while internet is DOWN -> must NOT crash & must NOT set captain offline
        currentShiftStatus = ShiftStatus.Active(
            shift = shift,
            remainingMillis = 8L * 3600_000L,
            remainingFormatted = "08:00:00",
            startFormatted = "10:00 ص",
            endFormatted = "6:00 م"
        )
        vm.runSingleTickerCycle(state, nowMs = 2_000L)
        assertEquals(2, state.tickCount)
        assertEquals("Ticker error count must record the caught network error", 1, state.consecutiveTickerErrors)
        assertTrue("Captain must remain online/ready during shift-start outage", captainOnline)
        assertTrue("No offline transition may occur during network outage", onlineTransitions.isEmpty())
        assertTrue(
            "previousLoadedShiftStatus must remain UpcomingSoon so shift-start retries when online",
            state.previousLoadedShiftStatus is ShiftStatus.UpcomingSoon
        )

        // Tick 3: Internet RETURNS -> ticker continues and completes shift-start promotion!
        networkOnline = true
        vm.runSingleTickerCycle(state, nowMs = 3_000L)
        assertEquals(3, state.tickCount)
        assertEquals("Captain must be promoted to working upon network recovery", listOf(true), onlineTransitions)
        assertEquals("Orders must sync once shift-start promotion succeeds", 1, syncedOrdersCount.get())
        assertTrue(
            "previousLoadedShiftStatus must now advance to Active",
            state.previousLoadedShiftStatus is ShiftStatus.Active
        )

        // Tick 4: Shift ENDS while internet is DOWN again -> stops GPS, goes locally offline, keeps ticking
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()
        drainStoppedServices(shadowApp)
        networkOnline = false
        currentShiftStatus = ShiftStatus.OutsideShift(
            nextShift = shift,
            nextShiftDescription = "غداً 10:00 ص",
            isDayOffToday = false
        )

        vm.runSingleTickerCycle(state, nowMs = 4_000L)
        assertEquals(4, state.tickCount)
        assertNotNull("Shift end must stop location service safely via stopService()", shadowApp.nextStoppedService)
        assertNull("Shift end must never call startService()", shadowApp.nextStartedService)
        assertFalse("Captain must be locally offline on shift end", captainOnline)
        assertTrue("previousLoadedShiftStatus must advance to OutsideShift", state.previousLoadedShiftStatus is ShiftStatus.OutsideShift)

        // Tick 5: Internet RETURNS after shift end -> deferred offline server sync completes!
        networkOnline = true
        vm.runSingleTickerCycle(state, nowMs = 5_000L)
        assertEquals(5, state.tickCount)
        assertTrue("Deferred server offline sync must succeed once internet recovers", pendingOfflineSynced.get())

        // Tick 6: Verify CancellationException is ALWAYS re-thrown (never swallowed!)
        val cancelState = CaptainViewModel.TickerLoopState(
            previousLoadedShiftStatus = ShiftStatus.UpcomingSoon(
                shift = shift,
                millisUntilStart = 60_000L,
                countdownFormatted = "00:01:00",
                startFormatted = "10:00 ص",
                endFormatted = "6:00 م"
            )
        )
        currentShiftStatus = ShiftStatus.Active(
            shift = shift,
            remainingMillis = 3600_000L,
            remainingFormatted = "01:00:00",
            startFormatted = "10:00 ص",
            endFormatted = "6:00 م"
        )
        captainOnline = true
        vm.tickerShiftReadinessOverride = { throw CancellationException("Cancelled test scope") }

        var cancellationRethrown = false
        try {
            vm.runSingleTickerCycle(cancelState, nowMs = 6_000L)
        } catch (_: CancellationException) {
            cancellationRethrown = true
        }
        assertTrue("CancellationException MUST be re-thrown and never swallowed", cancellationRethrown)
        } finally {
            store.clear()
        }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Startup Timeout -> Shows Network Error State ("تعذر التحقق من حالة الحساب"), NEVER PENDING
    // ---------------------------------------------------------------------------------------------
    @Test
    fun startupTimeout_populatesNetworkErrorApprovalSnapshot_andDisplaysNetworkErrorNotPending() {
        val store = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            store,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(appContext)
        )[CaptainViewModel::class.java]
        val snapshot: com.example.data.CaptainApprovalSnapshot
        val feedback: String?
        try {
            vm.startupSessionCheckTimeoutMs = 150L
            vm.startupSessionResolverOverride = {
                // Simulate hung server call exceeding startup timeout
                delay(5_000L)
                StartupSessionState.Authenticated
            }

            vm.prepareStartupSessionCheck()
            vm.onIntroSplashFinished()

            val deadline = System.currentTimeMillis() + 3_000L
            while (
                (vm.currentRoute.value != ScreenRoute.AccountApproval.route ||
                    vm.accountApprovalState.value == null) &&
                System.currentTimeMillis() < deadline
            ) {
                Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(50))
                Thread.sleep(10L)
            }

            assertEquals(
                "Startup timeout must fail closed and route to AccountApproval",
                ScreenRoute.AccountApproval.route,
                vm.currentRoute.value
            )
            val resolvedSnapshot = vm.accountApprovalState.value
            assertNotNull("accountApprovalState must NOT be null on startup timeout", resolvedSnapshot)
            assertFalse("Startup timeout must NEVER grant app access", resolvedSnapshot!!.canEnterApp)
            assertFalse("networkError must be populated", resolvedSnapshot.networkError.isNullOrBlank())
            snapshot = resolvedSnapshot
            feedback = vm.approvalFeedback.value
        } finally {
            // Cancel ViewModel ticker before starting Compose UI assertions
            store.clear()
        }

        // Verify AccountApprovalScreen renders the network-error title & badge (NOT PENDING!)
        composeTestRule.setContent {
            ElBatalTheme {
                AccountApprovalScreen(
                    approval = snapshot,
                    isRefreshing = false,
                    statusFeedback = feedback,
                    onRefreshStatus = {},
                    onLogout = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("account_approval_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("تعذر التحقق من حالة الحساب").assertIsDisplayed()
        composeTestRule.onNodeWithText("حالة الطلب: تعذر الاتصال").assertIsDisplayed()
        composeTestRule.onNodeWithText(snapshot.networkError!!).assertIsDisplayed()

        // Ensure misleading PENDING copy is NOT displayed
        composeTestRule.onNodeWithText("حسابك قيد المراجعة").assertDoesNotExist()
        composeTestRule.onNodeWithText("حالة الطلب: قيد المراجعة").assertDoesNotExist()
    }

    private suspend fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            delay(25L)
        }
    }

    private fun drainStoppedServices(shadowApp: org.robolectric.shadows.ShadowApplication) {
        while (shadowApp.nextStoppedService != null) {
            // Drain previously recorded stopService intents
        }
    }
}
