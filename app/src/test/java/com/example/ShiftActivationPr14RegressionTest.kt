package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AuthUserSnapshot
import com.example.data.CaptainAccountStatus
import com.example.data.CaptainApprovalSnapshot
import com.example.data.CaptainAuthBackendPort
import com.example.data.CaptainEntity
import com.example.data.CaptainPresenceException
import com.example.data.CaptainRepository
import com.example.data.LocationEligibilityResult
import com.example.data.OrderEntity
import com.example.data.OrderStatus
import com.example.data.PresenceErrorReason
import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import com.example.data.location.CaptainLocationService
import com.example.data.location.CaptainLocationTracker
import com.example.data.remote.CloudFunctionsOrderClient
import com.example.notifications.CaptainNotificationManager
import com.example.ui.CaptainViewModel
import com.example.ui.UiEvent
import com.example.ui.navigation.ScreenRoute
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShiftActivationPr14RegressionTest {

    private lateinit var appContext: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        CaptainRepository.resetPresenceFallbackStateForTesting()
        CaptainNotificationManager.clearNotificationState(appContext)
        CaptainLocationTracker.isAppInForegroundOverrideForTesting = { true }
        CaptainLocationTracker.setTracking(false)
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        CaptainRepository.resetPresenceFallbackStateForTesting()
        CaptainNotificationManager.clearNotificationState(appContext)
        CaptainLocationTracker.isAppInForegroundOverrideForTesting = null
        CaptainLocationTracker.setTracking(false)
        db.close()
    }

    private class FakeBackend : CaptainAuthBackendPort {
        var currentUid: String? = "cap_pr14"
        val docs = mutableMapOf<String, MutableMap<String, Any?>>()
        var updateFailure: Throwable? = null

        override fun currentUser(): AuthUserSnapshot? = currentUid?.let {
            AuthUserSnapshot(uid = it, email = "$it@elbatal.test", isEmailVerified = true, displayName = "كابتن اختبار")
        }

        override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot =
            throw UnsupportedOperationException()

        override suspend fun sendEmailVerification() {}
        override suspend fun reloadCurrentUser(): AuthUserSnapshot? = currentUser()
        override suspend fun updateDisplayName(displayName: String) {}
        override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot =
            throw UnsupportedOperationException()

        override fun signOut() {
            currentUid = null
        }

        override suspend fun sendPasswordResetEmail(email: String) {}
        override suspend fun createCaptainDoc(uid: String, data: Map<String, Any>) {
            docs[uid] = data.toMutableMap()
        }

        override suspend fun getCaptainDoc(uid: String): Map<String, Any?>? = docs[uid]?.toMap()
        override suspend fun getCaptainDocFromServer(uid: String): Map<String, Any?>? = docs[uid]?.toMap()

        override suspend fun updateCaptainDoc(uid: String, data: Map<String, Any>) {
            updateFailure?.let { throw it }
            val map = docs.getOrPut(uid) { mutableMapOf() }
            data.forEach { (k, v) -> map[k] = v }
        }

        override suspend fun queryCaptainEmailByPhoneVariants(
            variants: List<String>,
            numericVariants: List<Long>,
            normalizedLocal: String
        ): String? = null
    }

    private fun buildFirebaseFunctionsException(
        code: FirebaseFunctionsException.Code,
        message: String
    ): FirebaseFunctionsException {
        val ctor = FirebaseFunctionsException::class.java.declaredConstructors.firstOrNull {
            val params = it.parameterTypes
            params.size == 3 &&
                params[0] == String::class.java &&
                params[1] == FirebaseFunctionsException.Code::class.java
        } ?: error("Could not find FirebaseFunctionsException constructor")
        ctor.isAccessible = true
        return ctor.newInstance(message, code, null) as FirebaseFunctionsException
    }

    private fun buildAllDayShifts(uid: String): List<ShiftEntity> = (1..7).map { day ->
        ShiftEntity(
            id = "${uid}_day_$day",
            captainUid = uid,
            dayOfWeek = day,
            dayNameArabic = "يوم",
            startTime = "00:00",
            endTime = "23:59",
            isDayOff = false
        )
    }

    private fun buildAllDayCanonicalDoc(): Map<String, Any> {
        val days = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")
        return mapOf(
            "weeklySchedule" to days.associateWith {
                mapOf("startTime" to "00:00", "endTime" to "23:59", "isDayOff" to false)
            }
        )
    }

    private fun buildAllDaysOffCanonicalDoc(): Map<String, Any> {
        val days = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")
        return mapOf(
            "weeklySchedule" to days.associateWith {
                mapOf("startTime" to "00:00", "endTime" to "00:00", "isDayOff" to true)
            }
        )
    }

    private fun grantLocationAndEnableGps(gpsEnabled: Boolean = true) {
        Shadows.shadowOf(appContext).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, gpsEnabled)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, gpsEnabled)
    }

    private fun revokeLocationPermissions() {
        Shadows.shadowOf(appContext).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private fun awaitFirstToast(events: List<UiEvent>, timeoutMs: Long = 3_000L): UiEvent.ShowToast {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            events.filterIsInstance<UiEvent.ShowToast>().firstOrNull()?.let { return it }
            Thread.sleep(20L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return events.filterIsInstance<UiEvent.ShowToast>().firstOrNull()
            ?: throw AssertionError("Timed out waiting for toast; collected=$events")
    }

    private fun awaitCondition(timeoutMs: Long = 2_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    // 1. كابتن معتمد داخل وقت الشفت
    @Test
    fun scenario01_approvedCaptainInsideActiveShift_activatesReadyWorkingGpsAndOrders() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_approved_active"
        val actions = mutableListOf<String>()
        var syncOrdersCalls = 0
        vm.setCaptainReadyStatusOverride = { ready ->
            actions += if (ready) "ready" else "offline"
            vm.repository.captainDao.updateOnlineStatus(ready)
        }
        vm.tickerSetOnlineStatusOverride = { online ->
            actions += if (online) "working" else "offline"
            vm.repository.captainDao.updateOnlineStatus(online)
        }
        vm.tickerLocationEligibilityOverride = { LocationEligibilityResult.Eligible }
        vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }
        vm.repository.setAccountApprovalSnapshot(
            CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true)
        )
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "كابتن معتمد", phoneNumber = "01011111111", isOnline = false, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = uid, exists = true, docData = buildAllDayCanonicalDoc())

        val events = CopyOnWriteArrayList<UiEvent>()
        val shiftsJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.shifts.collect { } }
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            val toast = awaitFirstToast(events)

            assertEquals(listOf("working"), actions)
            assertTrue("Captain must be online locally", vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertEquals("Orders sync must start after server confirmation", 1, syncOrdersCalls)
            val started = shadowApp.nextStartedService
            assertNotNull("CaptainLocationService must start", started)
            assertEquals(CaptainLocationService::class.java.name, started?.component?.className)
            assertTrue("Expected success toast, got: ${toast.message}", toast.message.contains("متصل"))
        } finally {
            eventJob.cancel()
            shiftsJob.cancel()
        }
    }

    // 2. كابتن غير معتمد
    @Test
    fun scenario02_unapprovedCaptain_isBlockedFromReadyWorkingGpsAndOrders() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val backend = FakeBackend().apply { currentUid = "cap_unapproved" }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.PENDING, isApproved = false))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = "cap_unapproved", fullName = "غير معتمد", phoneNumber = "01022222222", isOnline = false, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(buildAllDayShifts("cap_unapproved"))

        try {
            repo.setCaptainReadyStatus(true)
            fail("Expected CaptainPresenceException for unapproved ready attempt")
        } catch (e: CaptainPresenceException) {
            assertEquals(PresenceErrorReason.PERMISSION_DENIED, e.reason)
        }
        try {
            repo.setOnlineStatus(true)
            fail("Expected CaptainPresenceException for unapproved working attempt")
        } catch (e: CaptainPresenceException) {
            assertEquals(PresenceErrorReason.PERMISSION_DENIED, e.reason)
        }
        assertFalse(db.captainDao().getCaptain()?.isOnline == true)

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        var syncOrdersCalls = 0
        vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.PENDING, isApproved = false))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = "cap_unapproved", fullName = "غير معتمد", phoneNumber = "01022222222", isOnline = false, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = "cap_unapproved", exists = true, docData = buildAllDayCanonicalDoc())

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            val toast = awaitFirstToast(events)
            assertTrue("Expected unapproved toast, got: ${toast.message}", toast.message.contains("غير معتمد"))
            assertNull("GPS service must NOT start for unapproved captain", shadowApp.nextStartedService)
            assertEquals("Orders must NOT sync for unapproved captain", 0, syncOrdersCalls)

            events.clear()
            vm.onLocationPermissionGranted()
            val permToast = awaitFirstToast(events)
            assertTrue("Expected unapproved toast on permission grant, got: ${permToast.message}", permToast.message.contains("غير معتمد"))
            assertNull("GPS service must NOT start after permission grant for unapproved captain", shadowApp.nextStartedService)
        } finally {
            eventJob.cancel()
        }
    }

    // 3. كابتن خارج وقت الشفت
    @Test
    fun scenario03_captainOutsideShift_cannotGoOnlineOrStartGps() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val backend = FakeBackend().apply { currentUid = "cap_outside" }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = "cap_outside", fullName = "خارج الشفت", phoneNumber = "01033333333", isOnline = false, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(
            (1..7).map { day ->
                ShiftEntity(id = "off_$day", captainUid = "cap_outside", dayOfWeek = day, dayNameArabic = "يوم", startTime = "00:00", endTime = "00:00", isDayOff = true)
            }
        )

        try {
            repo.setOnlineStatus(true)
            fail("Expected CaptainPresenceException outside shift")
        } catch (e: CaptainPresenceException) {
            assertEquals(PresenceErrorReason.FAILED_PRECONDITION, e.reason)
        }
        assertFalse(db.captainDao().getCaptain()?.isOnline == true)

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        var syncOrdersCalls = 0
        vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = "cap_outside", fullName = "خارج الشفت", phoneNumber = "01033333333", isOnline = false, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = "cap_outside", exists = true, docData = buildAllDaysOffCanonicalDoc())
        assertTrue(vm.shiftStatus.value is ShiftStatus.OutsideShift)

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            val toast = awaitFirstToast(events)
            assertTrue("Expected outside-shift toast, got: ${toast.message}", toast.message.contains("خارج الشفت"))
            assertNull("GPS must NOT start outside shift", shadowApp.nextStartedService)
            assertEquals(0, syncOrdersCalls)
        } finally {
            eventJob.cancel()
        }
    }

    // 4. كابتن قبل بداية الشفت بـ30 دقيقة
    @Test
    fun scenario04_captainWithin30MinPreShiftWindow_confirmsReadyWithoutGps_andFailsCleanlyOnServerError() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_preshift_30m"
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "قبل الشفت", phoneNumber = "01044444444", isOnline = false, isLoggedIn = true)
        )
        val upcomingShift = ShiftEntity(id = "s1", captainUid = uid, dayOfWeek = 1, dayNameArabic = "الأحد", startTime = "14:00", endTime = "22:00")
        val tickerState = CaptainViewModel.TickerLoopState()

        // 4A. 45 minutes before shift -> blocked (>30m)
        vm.tickerShiftStatusOverride = {
            ShiftStatus.UpcomingSoon(upcomingShift, 45L * 60_000L, "45 د", "02:00 م", "10:00 م")
        }
        vm.runSingleTickerCycle(tickerState)

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            val tooEarlyToast = awaitFirstToast(events)
            assertTrue(tooEarlyToast.message.contains("30 دقيقة"))
            assertNull(shadowApp.nextStartedService)

            // 4B. 15 minutes before shift, server FAILS ready -> MUST NOT treat as success!
            events.clear()
            vm.tickerShiftStatusOverride = {
                ShiftStatus.UpcomingSoon(upcomingShift, 15L * 60_000L, "15 د", "02:00 م", "10:00 م")
            }
            vm.runSingleTickerCycle(tickerState)
            vm.setCaptainReadyStatusOverride = {
                throw buildFirebaseFunctionsException(FirebaseFunctionsException.Code.NOT_FOUND, "NOT_FOUND")
            }
            vm.toggleOnlineStatus()
            val failToast = awaitFirstToast(events)
            assertTrue("Expected NOT_FOUND Arabic toast, got: ${failToast.message}", failToast.message.contains("غير متاحة مؤقتاً"))
            assertFalse("Captain must remain offline when ready fails", vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertNull("GPS must NOT start in pre-shift ready window", shadowApp.nextStartedService)

            // 4C. 15 minutes before shift, server SUCCEEDS ready -> confirms ready, does NOT start GPS
            events.clear()
            vm.setCaptainReadyStatusOverride = { ready -> vm.repository.captainDao.updateOnlineStatus(ready) }
            vm.toggleOnlineStatus()
            val okToast = awaitFirstToast(events)
            assertTrue("Expected ready confirmation toast, got: ${okToast.message}", okToast.message.contains("تم تسجيلك جاهزاً"))
            assertTrue(vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertNull("GPS must NOT start before shift actually begins", shadowApp.nextStartedService)
        } finally {
            eventJob.cancel()
        }
    }

    // 5. فشل Cloud Function (all 6 required FirebaseFunctionsException codes)
    @Test
    fun scenario05_cloudFunctionsErrors_allCodesPropagateFromRepositoryAndMapToArabicWithoutStartingGps() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val backend = FakeBackend().apply { currentUid = "cap_cf_err" }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = "cap_cf_err", fullName = "اختبار الدالة", phoneNumber = "01055555555", isOnline = false, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(buildAllDayShifts("cap_cf_err"))

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val expectedCases = listOf(
            FirebaseFunctionsException.Code.UNAUTHENTICATED to "انتهت صلاحية الجلسة",
            FirebaseFunctionsException.Code.PERMISSION_DENIED to "لا تملك صلاحية",
            FirebaseFunctionsException.Code.FAILED_PRECONDITION to "تعذر تفعيل العمل حالياً",
            FirebaseFunctionsException.Code.NOT_FOUND to "غير متاحة مؤقتاً",
            FirebaseFunctionsException.Code.UNAVAILABLE to "تعذر الوصول إلى الخادم",
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED to "تعذر الوصول إلى الخادم"
        )

        for ((code, expectedArabicSnippet) in expectedCases) {
            val ffe = buildFirebaseFunctionsException(code, code.name)
            repo.presenceCallableOverride = { throw ffe }
            try {
                repo.setOnlineStatus(true)
                fail("Expected setOnlineStatus(true) to throw FirebaseFunctionsException($code) instead of returning silently")
            } catch (thrown: FirebaseFunctionsException) {
                assertEquals("Must rethrow exact code $code", code, thrown.code)
                val msg = vm.mapPresenceErrorToArabicMessage(thrown, "fallback")
                assertTrue("Expected Arabic snippet '$expectedArabicSnippet' for $code, got: $msg", msg.contains(expectedArabicSnippet))
            }
            assertFalse("Captain must remain offline locally after $code", db.captainDao().getCaptain()?.isOnline == true)
            assertNull("GPS must NOT start after $code", shadowApp.nextStartedService)
        }
    }

    // 6. انقطاع الإنترنت أثناء التفعيل
    @Test
    fun scenario06_networkDisconnectionDuringActivation_keepsOfflineAndBlocksGpsAndOrders() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_net_drop"
        var syncOrdersCalls = 0
        vm.tickerSetOnlineStatusOverride = { throw IOException("Network unreachable") }
        vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "انقطاع إنترنت", phoneNumber = "01066666666", isOnline = false, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = uid, exists = true, docData = buildAllDayCanonicalDoc())

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            val toast = awaitFirstToast(events)
            assertTrue("Expected network Arabic message, got: ${toast.message}", toast.message.contains("انقطاع الإنترنت"))
            assertFalse("Captain must stay offline", vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertNull("GPS must NOT start on network drop", shadowApp.nextStartedService)
            assertEquals("Orders must NOT sync on network drop", 0, syncOrdersCalls)
        } finally {
            eventJob.cancel()
        }
    }

    // 7. رفض الخادم تفعيل working (بما في ذلك استئناف العمل عند بداية الشفت بعد الجاهزية)
    @Test
    fun scenario07_serverRejectsWorkingAtShiftStartAfterReadiness_revertsOfflineBlocksGpsAndShowsArabicError() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_reject_working"
        var syncOrdersCalls = 0
        val shift = ShiftEntity(id = "s_act", captainUid = uid, dayOfWeek = 1, dayNameArabic = "الأحد", startTime = "00:00", endTime = "23:59")
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "رفض الخادم", phoneNumber = "01077777777", isOnline = true, isLoggedIn = true)
        )
        vm.tickerCaptainLoggedInOverride = { true }
        vm.tickerCaptainOnlineOverride = { true }
        vm.tickerShiftReadinessOverride = { true }
        vm.tickerSetOnlineStatusOverride = {
            throw buildFirebaseFunctionsException(
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                "لم يتم اعتماد الشفت على الخادم"
            )
        }
        vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }

        val tickerState = CaptainViewModel.TickerLoopState(
            previousLoadedShiftStatus = ShiftStatus.UpcomingSoon(shift, 5_000L, "5 ث", "12:00 ص", "11:59 م")
        )
        vm.tickerShiftStatusOverride = {
            ShiftStatus.Active(shift, 3_600_000L, "1 س", "12:00 ص", "11:59 م")
        }

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.runSingleTickerCycle(tickerState)
            val toast = awaitFirstToast(events)

            assertTrue("Expected server rejection Arabic message, got: ${toast.message}", toast.message.contains("لم يتم اعتماد الشفت على الخادم"))
            assertFalse("Must NOT show false success toast", toast.message.contains("تم تحويل حالتك إلى يعمل الآن"))
            assertFalse("Captain must be reverted to offline locally", vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertNull("GPS must NOT start when server rejects working", shadowApp.nextStartedService)
            assertEquals("Orders must NOT sync when server rejects working", 0, syncOrdersCalls)
        } finally {
            eventJob.cancel()
        }
    }

    // 8. نجاح ready وفشل working
    @Test
    fun scenario08_workingFails_rollsBackLocalOnlineAndNeverStartsGpsOrOrders() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_ready_ok_working_fail"
        var workingCalls = 0
        var syncOrdersCalls = 0

        vm.tickerSetOnlineStatusOverride = { online ->
            if (online) {
                workingCalls++
                throw buildFirebaseFunctionsException(FirebaseFunctionsException.Code.FAILED_PRECONDITION, "FAILED_PRECONDITION")
            }
        }
        vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "جاهز فقط", phoneNumber = "01088888888", isOnline = false, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = uid, exists = true, docData = buildAllDayCanonicalDoc())

        val events = CopyOnWriteArrayList<UiEvent>()
        val shiftsJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.shifts.collect { } }
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            val toast = awaitFirstToast(events)

            assertEquals("Working should be the only presence call during an active shift", 1, workingCalls)
            assertFalse("Local online flag must roll back to offline after working fails", vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertNull("GPS must NOT start when working fails", shadowApp.nextStartedService)
            assertEquals("Orders must NOT sync when working fails", 0, syncOrdersCalls)
            assertTrue("Expected Arabic failure toast, got: ${toast.message}", toast.message.contains("تعذر تفعيل العمل"))
        } finally {
            eventJob.cancel()
            shiftsJob.cancel()
        }
    }

    // 9. منح إذن GPS بعد رفضه سابقاً
    @Test
    fun scenario09_grantingGpsPermissionAfterPreviousDenial_handlesFailureAndThenActivatesCleanly() = runBlocking {
        revokeLocationPermissions()
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_perm_retry"
        var syncOrdersCalls = 0
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "إذن لاحق", phoneNumber = "01099999991", isOnline = false, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = uid, exists = true, docData = buildAllDayCanonicalDoc())

        val events = CopyOnWriteArrayList<UiEvent>()
        val shiftsJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.shifts.collect { } }
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            // Step 1: Tap Online while permission missing -> requests permission
            vm.toggleOnlineStatus()
            awaitCondition { events.any { it is UiEvent.RequestLocationPermission } }
            assertTrue(events.any { it is UiEvent.RequestLocationPermission })
            assertNull(shadowApp.nextStartedService)

            // Step 2: Captain denies permission
            events.clear()
            vm.onLocationPermissionDenied()
            val deniedToast = awaitFirstToast(events)
            assertTrue(deniedToast.message.contains("دون إذن الموقع"))
            assertNull(shadowApp.nextStartedService)

            // Step 3: Captain later grants permission, but server working fails -> stays offline, no GPS
            events.clear()
            grantLocationAndEnableGps(true)
            vm.setCaptainReadyStatusOverride = { vm.repository.captainDao.updateOnlineStatus(it) }
            vm.tickerSetOnlineStatusOverride = {
                throw buildFirebaseFunctionsException(FirebaseFunctionsException.Code.UNAVAILABLE, "UNAVAILABLE")
            }
            vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }
            vm.onLocationPermissionGranted()
            val failToast = awaitFirstToast(events)
            assertTrue(failToast.message.contains("تعذر الوصول إلى الخادم"))
            assertFalse(vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertNull(shadowApp.nextStartedService)
            assertEquals(0, syncOrdersCalls)

            // Step 4: Captain grants/retries when server confirms -> GPS & orders start
            events.clear()
            vm.tickerSetOnlineStatusOverride = { vm.repository.captainDao.updateOnlineStatus(it) }
            vm.tickerLocationEligibilityOverride = { LocationEligibilityResult.Eligible }
            vm.onLocationPermissionGranted()
            val okToast = awaitFirstToast(events)
            assertTrue(okToast.message.contains("متصل"))
            assertTrue(vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertEquals(1, syncOrdersCalls)
            assertNotNull(shadowApp.nextStartedService)
        } finally {
            eventJob.cancel()
            shiftsJob.cancel()
        }
    }

    // 10. تعطيل GPS أثناء محاولة الاتصال
    @Test
    fun scenario10_gpsDisabledDuringConnectAttempt_requestsGpsWithoutCallingWorkingOrStartingService() = runBlocking {
        grantLocationAndEnableGps(gpsEnabled = false)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_gps_off"
        var readyCalls = 0
        var workingCalls = 0
        var syncOrdersCalls = 0
        vm.setCaptainReadyStatusOverride = { readyCalls++ }
        vm.tickerSetOnlineStatusOverride = { workingCalls++ }
        vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "بدون GPS", phoneNumber = "01099999992", isOnline = false, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = uid, exists = true, docData = buildAllDayCanonicalDoc())

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            val toast = awaitFirstToast(events)
            assertTrue(events.any { it is UiEvent.RequestGpsEnable })
            assertTrue(toast.message.contains("GPS"))
            assertEquals(0, readyCalls)
            assertEquals(0, workingCalls)
            assertEquals(0, syncOrdersCalls)
            assertFalse(vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertNull(shadowApp.nextStartedService)
        } finally {
            eventJob.cancel()
        }
    }

    // 11. انتهاء جلسة Firebase Authentication
    @Test
    fun scenario11_expiredFirebaseAuthSession_throwsUnauthenticatedKeepsOfflineAndBlocksGps() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val backend = FakeBackend().apply { currentUid = null }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = "cap_expired_auth", fullName = "جلسة منتهية", phoneNumber = "01099999993", isOnline = false, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(buildAllDayShifts("cap_expired_auth"))

        try {
            repo.setCaptainReadyStatus(true)
            fail("Expected CaptainPresenceException(UNAUTHENTICATED) when auth session expired")
        } catch (e: CaptainPresenceException) {
            assertEquals(PresenceErrorReason.UNAUTHENTICATED, e.reason)
            assertTrue(e.userMessage.contains("انتهت صلاحية الجلسة"))
        }

        try {
            repo.setOnlineStatus(true)
            fail("Expected CaptainPresenceException(UNAUTHENTICATED) when auth session expired")
        } catch (e: CaptainPresenceException) {
            assertEquals(PresenceErrorReason.UNAUTHENTICATED, e.reason)
            assertTrue(e.userMessage.contains("انتهت صلاحية الجلسة"))
        }
        assertFalse(db.captainDao().getCaptain()?.isOnline == true)
        assertNull(shadowApp.nextStartedService)
    }

    // 12. انتهاء الشفت أثناء تشغيل التطبيق
    @Test
    fun scenario12_shiftEndsWhileAppRunning_stopsGpsMarksOfflineAndClearsPendingOrders() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()
        CaptainLocationTracker.setTracking(true)

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_shift_end"
        val shift = ShiftEntity(id = "s_end", captainUid = uid, dayOfWeek = 1, dayNameArabic = "الأحد", startTime = "08:00", endTime = "16:00")
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "نهاية الشفت", phoneNumber = "01099999994", isOnline = true, isLoggedIn = true)
        )
        vm.repository.orderDao.insertOrder(
            OrderEntity(
                id = 901L,
                orderNumber = "END-901",
                firestoreDocId = "ORD_END_901",
                customerName = "عميل",
                customerPhone = "01000000099",
                storeName = "متجر",
                pickupAddress = "أ",
                deliveryAddress = "ب",
                itemsSummary = "طلب",
                itemsCount = 1,
                orderTotalAmount = 50.0,
                deliveryFee = 15.0,
                distanceKm = 1.5,
                status = OrderStatus.PENDING,
                captainId = ""
            )
        )

        var offlineCallCount = 0
        vm.tickerCaptainLoggedInOverride = { true }
        vm.tickerCaptainOnlineOverride = { true }
        vm.tickerSetOnlineStatusOverride = { online ->
            if (!online) {
                offlineCallCount++
                vm.repository.revertLocalOnlineStatusToOffline()
            }
        }
        val tickerState = CaptainViewModel.TickerLoopState(
            previousLoadedShiftStatus = ShiftStatus.Active(shift, 5_000L, "5 ث", "08:00 ص", "04:00 م")
        )
        vm.tickerShiftStatusOverride = {
            ShiftStatus.OutsideShift(nextShift = null, nextShiftDescription = "انتهت ورديات اليوم", isDayOffToday = false)
        }

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.runSingleTickerCycle(tickerState)
            val toast = awaitFirstToast(events)

            assertEquals("Offline transition must run on shift end", 1, offlineCallCount)
            assertFalse("Captain must be offline locally", vm.repository.captainDao.getCaptain()?.isOnline == true)
            assertFalse("Location tracking must stop", CaptainLocationTracker.isTracking.value)
            assertNotNull("stopService must be invoked for CaptainLocationService", shadowApp.nextStoppedService)
            assertTrue("Pending orders must be cleared on shift end", vm.repository.allOrdersFlow.first().isEmpty())
            assertTrue("Expected shift-end toast, got: ${toast.message}", toast.message.contains("انتهى شفت العمل"))
        } finally {
            eventJob.cancel()
        }
    }

    // 13. الحفاظ على CancellationException وتوافق الحقول والمنطقة الزمنية ومنطقة Firebase
    @Test
    fun scenario13_cancellationExceptionPropagates_andPresenceFieldContractAndCairoTimezoneConsistent() = runBlocking {
        assertEquals("Africa/Cairo", ShiftManager.CAIRO_TIME_ZONE.id)
        assertEquals("us-central1", CloudFunctionsOrderClient.FUNCTIONS_REGION)

        val backend = FakeBackend().apply { currentUid = "cap_contract" }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = "cap_contract", fullName = "توافق الحقول", phoneNumber = "01099999995", isOnline = false, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(buildAllDayShifts("cap_contract"))

        // 1) Ready writes consistent fields verified by hasConfirmedShiftReadiness()
        repo.setCaptainReadyStatus(true)
        assertTrue("hasConfirmedShiftReadiness must return true after server ready", repo.hasConfirmedShiftReadiness())
        val readyDoc = backend.docs["cap_contract"]!!
        assertEquals("ready", readyDoc["workStatus"])
        assertEquals(true, readyDoc["readyConfirmed"])
        assertEquals("ready", readyDoc["presenceStatus"])
        assertEquals("ready", readyDoc["availabilityStatus"])
        assertEquals(true, readyDoc["isOnline"])
        assertTrue((readyDoc["readyShiftKey"] as? String).orEmpty().isNotBlank())

        // 2) Working writes consistent fields verified by checkLocationTrackingEligibility()
        repo.setOnlineStatus(true)
        assertEquals(LocationEligibilityResult.Eligible, repo.checkLocationTrackingEligibility())
        val workingDoc = backend.docs["cap_contract"]!!
        assertEquals("working", workingDoc["workStatus"])
        assertEquals(true, workingDoc["readyConfirmed"])
        assertEquals("working", workingDoc["presenceStatus"])
        assertEquals("working", workingDoc["availabilityStatus"])
        assertEquals(true, workingDoc["isOnline"])
        assertEquals(workingDoc["readyShiftKey"], workingDoc["workingShiftKey"])

        // 3) CancellationException must NEVER be swallowed in ready or working
        repo.presenceCallableOverride = { throw CancellationException("cancelled") }
        try {
            repo.setCaptainReadyStatus(true)
            fail("Expected CancellationException from setCaptainReadyStatus(true)")
        } catch (_: CancellationException) {
            // Expected
        }
        try {
            repo.setOnlineStatus(true)
            fail("Expected CancellationException from setOnlineStatus(true)")
        } catch (_: CancellationException) {
            // Expected
        }
    }
}
