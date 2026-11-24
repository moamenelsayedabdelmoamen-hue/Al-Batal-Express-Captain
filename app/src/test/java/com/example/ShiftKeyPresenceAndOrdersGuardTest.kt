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
import com.example.data.LoginOutcome
import com.example.data.OrderEntity
import com.example.data.OrderStatus
import com.example.data.PresenceErrorReason
import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import com.example.data.StartupSessionState
import com.example.data.location.CaptainLocationService
import com.example.data.location.CaptainLocationTracker
import com.example.notifications.CaptainNotificationManager
import com.example.ui.CaptainViewModel
import com.example.ui.UiEvent
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
class ShiftKeyPresenceAndOrdersGuardTest {

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
        var currentUid: String? = "cap_shift_guard"
        val usersByEmail = mutableMapOf<String, Pair<String, String>>() // email -> (uid, pass)
        val docs = mutableMapOf<String, MutableMap<String, Any?>>()
        val updatedPayloads = mutableListOf<Pair<String, Map<String, Any>>>()

        fun seedApprovedCaptain(uid: String, email: String, pass: String, name: String = "كابتن البطل") {
            usersByEmail[email.lowercase()] = uid to pass
            docs[uid] = mutableMapOf(
                "uid" to uid,
                "fullName" to name,
                "email" to email,
                "phone" to "01012345678",
                "accountType" to "captain",
                "role" to "captain",
                "accountStatus" to "approved",
                "isApproved" to true,
                "status" to "active"
            )
        }

        override fun currentUser(): AuthUserSnapshot? = currentUid?.let { uid ->
            val doc = docs[uid]
            AuthUserSnapshot(
                uid = uid,
                email = doc?.get("email")?.toString() ?: "$uid@elbatal.test",
                isEmailVerified = true,
                displayName = doc?.get("fullName")?.toString() ?: "كابتن اختبار"
            )
        }

        override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot =
            throw UnsupportedOperationException()

        override suspend fun sendEmailVerification() {}
        override suspend fun reloadCurrentUser(): AuthUserSnapshot? = currentUser()
        override suspend fun updateDisplayName(displayName: String) {}

        override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val match = usersByEmail[email.lowercase()]
                ?: throw IllegalArgumentException("Invalid credentials")
            require(match.second == password) { "Wrong password" }
            currentUid = match.first
            return currentUser()!!
        }

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
            updatedPayloads += uid to data
            val map = docs.getOrPut(uid) { mutableMapOf() }
            data.forEach { (k, v) -> map[k] = v }
        }

        override suspend fun queryCaptainEmailByPhoneVariants(
            variants: List<String>,
            numericVariants: List<Long>,
            normalizedLocal: String
        ): String? = null
    }

    private fun cairoCal(year: Int, monthZeroBased: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun buildAllDayShifts(uid: String, startTime: String = "00:00", endTime: String = "23:59"): List<ShiftEntity> =
        (1..7).map { day ->
            ShiftEntity(
                id = "${uid}_day_$day",
                captainUid = uid,
                dayOfWeek = day,
                dayNameArabic = "يوم",
                startTime = startTime,
                endTime = endTime,
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

    private fun grantLocationAndEnableGps(gpsEnabled: Boolean = true) {
        Shadows.shadowOf(appContext).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, gpsEnabled)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, gpsEnabled)
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

    // 1. صيغة shiftKey الموحدة: 00:00، 08:40، 09:30، 14:00، والشفت الليلي بعد منتصف الليل
    @Test
    fun shiftKeyFormat_matchesCanonicalHHmmForAllRequiredTimesAndOvernightShift() {
        val backend = FakeBackend()
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)

        // Tuesday 2026-11-24 in Africa/Cairo
        val tuesdayNoon = cairoCal(2026, Calendar.NOVEMBER, 24, 12, 0)
        assertEquals(Calendar.TUESDAY, tuesdayNoon.get(Calendar.DAY_OF_WEEK))

        val s0000 = ShiftEntity("s0", "cap", Calendar.TUESDAY, "الثلاثاء", "00:00", "08:00")
        val s0840 = ShiftEntity("s1", "cap", Calendar.TUESDAY, "الثلاثاء", "08:40", "16:40")
        val s0930 = ShiftEntity("s2", "cap", Calendar.TUESDAY, "الثلاثاء", "09:30", "17:30")
        val s1400 = ShiftEntity("s3", "cap", Calendar.TUESDAY, "الثلاثاء", "14:00", "22:00")

        assertEquals("2026-11-24|tuesday|0000", repo.buildShiftInstanceKey(s0000, tuesdayNoon))
        assertEquals("2026-11-24|tuesday|0840", repo.buildShiftInstanceKey(s0840, tuesdayNoon))
        assertEquals("2026-11-24|tuesday|0930", repo.buildShiftInstanceKey(s0930, tuesdayNoon))
        assertEquals("2026-11-24|tuesday|1400", repo.buildShiftInstanceKey(s1400, tuesdayNoon))

        // Overnight shift: Tuesday 22:00 -> 04:00 evaluated on Wednesday 2026-11-25 at 01:30 Cairo
        val overnightTuesday = ShiftEntity("s_night", "cap", Calendar.TUESDAY, "الثلاثاء", "22:00", "04:00")
        val wednesdayAfterMidnight = cairoCal(2026, Calendar.NOVEMBER, 25, 1, 30)
        assertEquals(Calendar.WEDNESDAY, wednesdayAfterMidnight.get(Calendar.DAY_OF_WEEK))

        val calcStatus = ShiftManager.calculateShiftStatus(listOf(overnightTuesday), wednesdayAfterMidnight)
        assertTrue("Overnight shift must remain active after midnight", calcStatus is ShiftStatus.Active)
        val activeShift = (calcStatus as ShiftStatus.Active).shift
        assertEquals(
            "Overnight shift key after midnight must preserve yesterday's start date, dayKey, and HHmm",
            "2026-11-24|tuesday|2200",
            repo.buildShiftInstanceKey(activeShift, wednesdayAfterMidnight)
        )

        // Verify reconcileServerPresenceFromDoc accepts exact overnight shiftKey
        val serverWorkingDoc = mapOf(
            "isOnline" to true,
            "workStatus" to "working",
            "readyConfirmed" to true,
            "readyShiftKey" to "2026-11-24|tuesday|2200",
            "workingShiftKey" to "2026-11-24|tuesday|2200"
        )
        val restored = repo.reconcileServerPresenceFromDoc(
            uid = "cap",
            docData = serverWorkingDoc,
            shifts = listOf(overnightTuesday),
            nowCal = wednesdayAfterMidnight
        )
        assertNotNull("Overnight server working session must reconcile cleanly", restored)
        assertTrue(restored!!.isConfirmedWorking)
        assertEquals("2026-11-24|tuesday|2200", restored.workingShiftKey)
    }

    // 2. تسجيل الدخول وتأكيد البريد لا يضبطان isOnline=true، وأول ضغطة بعد تسجيل الدخول تنفذ ready -> working -> تحقق الخادم -> GPS
    @Test
    fun loginAndFirstToggle_keepsCaptainOfflineOnLogin_andFirstTapRunsReadyThenWorkingThenServerVerifyThenGps() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val uid = "cap_login_offline"
        val backend = FakeBackend().apply {
            seedApprovedCaptain(uid, "cap.login@elbatal.com", "Secret123")
        }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        var listenerAttachments = 0
        repo.ordersListenerAttachmentOverride = { _, _ ->
            listenerAttachments++
            listOf(ListenerRegistration { })
        }

        // Login succeeds for approved captain, yet captain MUST start locally offline without asserting isOnline=true on server
        val loginOutcome = repo.login("cap.login@elbatal.com", "Secret123")
        assertTrue(loginOutcome is LoginOutcome.Success)
        val localAfterLogin = db.captainDao().getCaptain()
        assertNotNull(localAfterLogin)
        assertTrue("Captain must be logged in locally", localAfterLogin!!.isLoggedIn)
        assertFalse("Captain must start OFFLINE locally after login", localAfterLogin.isOnline)
        assertEquals("offline", localAfterLogin.availabilityStatus)
        assertNull("No server-confirmed working session after login alone", repo.serverConfirmedWorkingSession.value)
        assertEquals("Orders listener must NOT attach on login", 0, listenerAttachments)

        // Verify login updateCaptainDoc payload did NOT write isOnline=true or availabilityStatus=available
        val loginWrite = backend.updatedPayloads.last { it.first == uid }.second
        assertEquals(true, loginWrite["isLoggedIn"])
        assertFalse("Login must NOT write isOnline to server", loginWrite.containsKey("isOnline"))
        assertFalse("Login must NOT write availabilityStatus to server", loginWrite.containsKey("availabilityStatus"))
        assertFalse("Login must NOT write presenceStatus to server", loginWrite.containsKey("presenceStatus"))

        // Also check checkEmailVerification keeps captain offline locally and does not write isOnline=true
        backend.updatedPayloads.clear()
        val emailCheck = repo.checkEmailVerification()
        assertTrue(emailCheck.isSuccess)
        assertFalse("checkEmailVerification must keep captain locally offline", db.captainDao().getCaptain()?.isOnline == true)
        val verifyWrite = backend.updatedPayloads.last { it.first == uid }.second
        assertFalse("checkEmailVerification must NOT write isOnline to server", verifyWrite.containsKey("isOnline"))
        assertEquals("Orders listener must NOT attach on email verification", 0, listenerAttachments)

        // Now test first click on toggleOnlineStatus() inside an active shift via ViewModel
        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val callSequence = mutableListOf<String>()
        var syncCalls = 0
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "كابتن البطل", phoneNumber = "01012345678", isOnline = false, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = uid, exists = true, docData = buildAllDayCanonicalDoc())
        vm.setCaptainReadyStatusOverride = { ready ->
            callSequence += if (ready) "ready" else "offline"
            vm.repository.captainDao.updateOnlineStatus(ready)
        }
        vm.tickerSetOnlineStatusOverride = { online ->
            callSequence += if (online) "working" else "offline"
            vm.repository.captainDao.updateOnlineStatus(online)
        }
        vm.tickerLocationEligibilityOverride = {
            callSequence += "server_verify"
            LocationEligibilityResult.Eligible
        }
        vm.tickerSyncOrdersOverride = {
            callSequence += "orders_sync"
            syncCalls++
        }

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            val toast = awaitFirstToast(events)
            assertEquals(listOf("ready", "working", "server_verify", "orders_sync"), callSequence)
            assertEquals(1, syncCalls)
            assertNotNull("GPS service must start ONLY after ready -> working -> server_verify", shadowApp.nextStartedService)
            assertTrue(toast.message.contains("متصل"))
        } finally {
            eventJob.cancel()
        }
    }

    // 3. حتى في حالة وجود isOnline=true قديم محلياً دون تأكيد الخادم، لا يتخطى toggleOnlineStatus خطوة ready
    @Test
    fun toggleOnlineStatus_withStaleLocalOnlineAndNoServerReadiness_doesNotSkipReady() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_stale_online"
        val callSequence = mutableListOf<String>()

        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "كابتن قديم", phoneNumber = "01012345679", isOnline = true, isLoggedIn = true)
        )
        vm.applyCanonicalShiftSnapshot(uid = uid, exists = true, docData = buildAllDayCanonicalDoc())
        vm.tickerShiftReadinessOverride = { false }
        var serverConfirmedWorking = false
        vm.setCaptainReadyStatusOverride = { ready ->
            callSequence += if (ready) "ready" else "offline"
            vm.repository.captainDao.updateOnlineStatus(ready)
        }
        vm.tickerSetOnlineStatusOverride = { online ->
            callSequence += if (online) "working" else "offline"
            if (online) serverConfirmedWorking = true
            vm.repository.captainDao.updateOnlineStatus(online)
        }
        vm.tickerLocationEligibilityOverride = {
            if (serverConfirmedWorking) {
                callSequence += "server_verify_ok"
                LocationEligibilityResult.Eligible
            } else {
                LocationEligibilityResult.Ineligible("not_working_yet")
            }
        }
        vm.tickerSyncOrdersOverride = { callSequence += "orders_sync" }

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.toggleOnlineStatus()
            awaitFirstToast(events)
            assertEquals(listOf("ready", "working", "server_verify_ok", "orders_sync"), callSequence)
            assertNotNull(shadowApp.nextStartedService)
        } finally {
            eventJob.cancel()
        }
    }

    // 4. التفعيل قبل الشفت بـ30 دقيقة: يسجل ready ولا يشغّل GPS أو مستمع الطلبات
    @Test
    fun preShift30MinWindow_confirmsReadyOnly_blocksGpsAndOrdersUntilShiftStarts() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val uid = "cap_preshift"
        val backend = FakeBackend().apply {
            seedApprovedCaptain(uid, "preshift@elbatal.com", "Pass1234")
            currentUid = uid
        }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        var listenerAttachments = 0
        repo.ordersListenerAttachmentOverride = { _, _ ->
            listenerAttachments++
            listOf(ListenerRegistration { })
        }

        // Deterministic check at 13:45 Cairo for a 14:00-22:00 shift (15 minutes before shift)
        val tuesday1345 = cairoCal(2026, Calendar.NOVEMBER, 24, 13, 45)
        val shift1400 = ShiftEntity("s_1400", uid, Calendar.TUESDAY, "الثلاثاء", "14:00", "22:00")
        val statusBefore = ShiftManager.calculateShiftStatus(listOf(shift1400), tuesday1345)
        assertTrue(statusBefore is ShiftStatus.UpcomingSoon)
        assertEquals(15L * 60_000L, (statusBefore as ShiftStatus.UpcomingSoon).millisUntilStart)
        val expectedPreShiftKey = repo.buildShiftInstanceKey(shift1400, tuesday1345)
        assertEquals("2026-11-24|tuesday|1400", expectedPreShiftKey)

        val readyReconciled = repo.reconcileServerPresenceFromDoc(
            uid = uid,
            docData = mapOf(
                "isOnline" to true,
                "workStatus" to "ready",
                "readyConfirmed" to true,
                "readyShiftKey" to expectedPreShiftKey
            ),
            shifts = listOf(shift1400),
            nowCal = tuesday1345
        )
        assertNotNull("Server ready state within 30-min pre-shift window must reconcile", readyReconciled)
        assertEquals("ready", readyReconciled!!.workStatus)
        assertFalse("Pre-shift ready state must NOT be confirmedWorking", readyReconciled.isConfirmedWorking)

        // Attempting to start orders listener during ready window MUST be blocked
        repo.startRealtimeOrdersListener()
        assertEquals("Orders listener MUST NOT attach in pre-shift ready state", 0, listenerAttachments)
        assertNull("GPS must NOT start in pre-shift ready window", shadowApp.nextStartedService)

        // Also exercise live setCaptainReadyStatus when current Cairo time isn't within 15 min of midnight
        val nowCairo = ShiftManager.getCurrentCairoCalendar()
        val futureCal = (nowCairo.clone() as Calendar).apply { add(Calendar.MINUTE, 15) }
        if (futureCal.get(Calendar.DAY_OF_WEEK) == nowCairo.get(Calendar.DAY_OF_WEEK)) {
            val startStr = String.format("%02d:%02d", futureCal.get(Calendar.HOUR_OF_DAY), futureCal.get(Calendar.MINUTE))
            val endCal = (futureCal.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 4) }
            val endStr = String.format("%02d:%02d", endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE))
            val upcomingShift = ShiftEntity("s_up", uid, nowCairo.get(Calendar.DAY_OF_WEEK), "اليوم", startStr, endStr)
            db.captainDao().insertOrUpdate(CaptainEntity(id = 1, uid = uid, fullName = "كابتن", phoneNumber = "01000000001", isOnline = false, isLoggedIn = true))
            db.shiftDao().insertShifts(listOf(upcomingShift))

            repo.setCaptainReadyStatus(true)
            assertEquals("ready", backend.docs[uid]?.get("workStatus"))
            assertEquals(repo.buildShiftInstanceKey(upcomingShift, nowCairo), backend.docs[uid]?.get("readyShiftKey"))
            repo.startRealtimeOrdersListener()
            assertEquals("Orders listener MUST NOT attach in pre-shift ready state", 0, listenerAttachments)
            assertFalse("Captain must NOT be eligible for new orders in ready state", repo.isCaptainEligibleForNewOrders(db.captainDao().getCaptain()))
            assertNull("GPS must NOT start in pre-shift ready window", shadowApp.nextStartedService)
        }
    }

    // 5. الترقية التلقائية ready -> working عند بداية الشفت
    @Test
    fun automaticPromotion_fromReadyToWorkingAtShiftStart_startsGpsAndOrdersOnlyAfterServerConfirm() = runBlocking {
        grantLocationAndEnableGps(true)
        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_auto_promote"
        val shift = ShiftEntity("s_promo", uid, Calendar.TUESDAY, "الثلاثاء", "14:00", "22:00")
        vm.repository.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "ترقية تلقائية", phoneNumber = "01044445555", isOnline = true, isLoggedIn = true)
        )
        val steps = mutableListOf<String>()
        vm.tickerCaptainLoggedInOverride = { true }
        vm.tickerCaptainOnlineOverride = { true }
        vm.tickerShiftReadinessOverride = {
            steps += "check_ready_server"
            true
        }
        vm.tickerSetOnlineStatusOverride = { online ->
            if (online) steps += "promote_working"
        }
        vm.tickerLocationEligibilityOverride = {
            steps += "verify_working_server"
            LocationEligibilityResult.Eligible
        }
        vm.tickerSyncOrdersOverride = {
            steps += "sync_orders"
        }

        val tickerState = CaptainViewModel.TickerLoopState(
            previousLoadedShiftStatus = ShiftStatus.UpcomingSoon(shift, 5_000L, "5 ث", "02:00 م", "10:00 م")
        )
        vm.tickerShiftStatusOverride = {
            ShiftStatus.Active(shift, 8L * 3_600_000L, "8 س", "02:00 م", "10:00 م")
        }

        val events = CopyOnWriteArrayList<UiEvent>()
        val eventJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { events.add(it) } }
        try {
            vm.runSingleTickerCycle(tickerState)
            val toast = awaitFirstToast(events)
            assertEquals(listOf("check_ready_server", "promote_working", "verify_working_server", "sync_orders"), steps)
            assertNotNull("GPS service must start after auto-promotion confirmation", shadowApp.nextStartedService)
            assertEquals(CaptainLocationService::class.java.name, shadowApp.nextStartedService?.component?.className ?: CaptainLocationService::class.java.name)
            assertTrue(toast.message.contains("يعمل الآن"))
        } finally {
            eventJob.cancel()
        }
    }

    // 6. اختلاف shiftKey يرفض الجاهزية وأهلية GPS ومستمع الطلبات تماماً
    @Test
    fun shiftKeyMismatch_rejectsReadinessEligibilityOrdersListenerAndGps() = runBlocking {
        val uid = "cap_key_mismatch"
        val backend = FakeBackend().apply {
            seedApprovedCaptain(uid, "mismatch@elbatal.com", "Pass1234")
            currentUid = uid
        }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "اختلاف مفتاح", phoneNumber = "01055556666", isOnline = true, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(buildAllDayShifts(uid))

        var listenerAttachments = 0
        repo.ordersListenerAttachmentOverride = { _, _ ->
            listenerAttachments++
            listOf(ListenerRegistration { })
        }

        // Server doc has a mismatched shiftKey (e.g. legacy minutes-format or wrong time)
        backend.docs[uid]!!.putAll(
            mapOf(
                "isOnline" to true,
                "workStatus" to "ready",
                "readyConfirmed" to true,
                "readyShiftKey" to "2026-11-24|tuesday|9999"
            )
        )
        assertFalse("Mismatched readyShiftKey must fail hasConfirmedShiftReadiness", repo.hasConfirmedShiftReadiness())

        backend.docs[uid]!!.putAll(
            mapOf(
                "isOnline" to true,
                "workStatus" to "working",
                "readyConfirmed" to true,
                "readyShiftKey" to "2026-11-24|tuesday|9999",
                "workingShiftKey" to "2026-11-24|tuesday|9999"
            )
        )
        val eligibility = repo.checkLocationTrackingEligibility()
        assertTrue("Mismatched workingShiftKey must return Ineligible", eligibility is LocationEligibilityResult.Ineligible)
        assertNull("Confirmed working session must stay null on key mismatch", repo.serverConfirmedWorkingSession.value)

        repo.startRealtimeOrdersListener()
        assertEquals("Orders listener MUST NOT attach when shiftKey mismatches", 0, listenerAttachments)
        assertFalse(repo.isCaptainEligibleForNewOrders(db.captainDao().getCaptain()))
    }

    // 7. فشل Cloud Function يمنع GPS ومستمع الطلبات ويعيد الكابتن Offline
    @Test
    fun cloudFunctionFailure_rollsBackOfflineStopsOrdersListenerAndBlocksGps() = runBlocking {
        val uid = "cap_cf_fail"
        val backend = FakeBackend().apply {
            seedApprovedCaptain(uid, "cffail@elbatal.com", "Pass1234")
            currentUid = uid
        }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "فشل دالة", phoneNumber = "01066667777", isOnline = false, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(buildAllDayShifts(uid))

        repo.presenceCallableOverride = {
            throw buildFirebaseFunctionsException(FirebaseFunctionsException.Code.FAILED_PRECONDITION, "خارج وقت الشفت")
        }
        try {
            repo.setOnlineStatus(true)
            fail("Expected FirebaseFunctionsException to propagate")
        } catch (e: FirebaseFunctionsException) {
            assertEquals(FirebaseFunctionsException.Code.FAILED_PRECONDITION, e.code)
        }
        assertFalse(db.captainDao().getCaptain()?.isOnline == true)
        assertNull(repo.serverConfirmedWorkingSession.value)
        assertEquals(0, repo.activeOrdersListenerCount)
    }

    // 8. رفض حساب pending أو suspended
    @Test
    fun pendingOrSuspendedAccount_isBlockedFromPresenceOrdersListenerAndGps() = runBlocking {
        val uid = "cap_blocked_acc"
        val backend = FakeBackend().apply { currentUid = uid }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "معلق", phoneNumber = "01077778888", isOnline = true, isLoggedIn = true, status = "active")
        )
        db.shiftDao().insertShifts(buildAllDayShifts(uid))

        var listenerAttachments = 0
        repo.ordersListenerAttachmentOverride = { _, _ ->
            listenerAttachments++
            listOf(ListenerRegistration { })
        }

        for (blockedStatus in listOf(CaptainAccountStatus.PENDING, CaptainAccountStatus.SUSPENDED)) {
            repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = blockedStatus, isApproved = false))
            try {
                repo.setCaptainReadyStatus(true)
                fail("Expected PERMISSION_DENIED for $blockedStatus")
            } catch (e: CaptainPresenceException) {
                assertEquals(PresenceErrorReason.PERMISSION_DENIED, e.reason)
            }
            try {
                repo.setOnlineStatus(true)
                fail("Expected PERMISSION_DENIED for $blockedStatus")
            } catch (e: CaptainPresenceException) {
                assertEquals(PresenceErrorReason.PERMISSION_DENIED, e.reason)
            }
            val elig = repo.checkLocationTrackingEligibility()
            assertTrue("Eligibility must be Ineligible for $blockedStatus", elig is LocationEligibilityResult.Ineligible)
            repo.startRealtimeOrdersListener()
            assertEquals("Orders listener must NEVER attach for $blockedStatus", 0, listenerAttachments)
            assertFalse(repo.isCaptainEligibleForNewOrders(db.captainDao().getCaptain()))
        }
    }

    // 9 & 10. منع استقبال الطلبات قبل working، وإيقاف مستمع الطلبات عند offline / logout / فشل التحقق
    @Test
    fun ordersListenerGuard_blocksBeforeWorking_startsWhenConfirmedWorking_andStopsOnOfflineAndLogout() = runBlocking {
        val uid = "cap_orders_guard"
        val backend = FakeBackend().apply {
            seedApprovedCaptain(uid, "orders@elbatal.com", "Pass1234")
            currentUid = uid
        }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "كابتن طلبات", phoneNumber = "01088889999", isOnline = true, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(buildAllDayShifts(uid))

        val attachCount = AtomicInteger(0)
        val removeCount = AtomicInteger(0)
        repo.ordersListenerAttachmentOverride = { _, _ ->
            attachCount.incrementAndGet()
            listOf(ListenerRegistration { removeCount.incrementAndGet() })
        }

        // A) Local isOnline=true alone (NO server working confirmation) -> BLOCKED!
        repo.startRealtimeOrdersListener()
        assertEquals("Local isOnline alone MUST NOT attach orders listener", 0, attachCount.get())
        assertEquals(0, repo.activeOrdersListenerCount)
        assertFalse("Local isOnline alone MUST NOT pass isCaptainEligibleForNewOrders", repo.isCaptainEligibleForNewOrders(db.captainDao().getCaptain()))

        // B) Server confirms READY only -> STILL BLOCKED for orders!
        repo.setCaptainReadyStatus(true)
        assertTrue(repo.hasConfirmedShiftReadiness())
        repo.startRealtimeOrdersListener()
        assertEquals("Server READY state MUST NOT attach orders listener", 0, attachCount.get())
        assertFalse(repo.isCaptainEligibleForNewOrders(db.captainDao().getCaptain()))

        // C) Server confirms WORKING with matching shiftKey -> ALLOWED!
        repo.setOnlineStatus(true)
        assertEquals(LocationEligibilityResult.Eligible, repo.checkLocationTrackingEligibility())
        assertTrue("Working captain with confirmed shiftKey MUST be eligible for new orders", repo.isCaptainEligibleForNewOrders(db.captainDao().getCaptain()))

        repo.startRealtimeOrdersListener()
        assertEquals("Orders listener must attach once server confirms working", 1, attachCount.get())
        assertEquals(1, repo.activeOrdersListenerCount)

        // D) Seed a pending offer, then switch OFFLINE -> listener removed & pending orders cleared!
        db.orderDao().insertOrder(
            OrderEntity(
                id = 701L,
                orderNumber = "ORD-701",
                firestoreDocId = "ORD_701",
                customerName = "عميل",
                customerPhone = "01011112222",
                storeName = "متجر",
                pickupAddress = "أ",
                deliveryAddress = "ب",
                itemsSummary = "طلب",
                itemsCount = 1,
                orderTotalAmount = 80.0,
                deliveryFee = 20.0,
                distanceKm = 2.0,
                status = OrderStatus.PENDING
            )
        )
        repo.setOnlineStatus(false)
        assertEquals("Going offline must remove active orders listener", 1, removeCount.get())
        assertEquals(0, repo.activeOrdersListenerCount)
        assertNull(repo.serverConfirmedWorkingSession.value)
        assertTrue("Pending orders must be cleared on offline", db.orderDao().getAvailablePendingOrdersFlow().first().isEmpty())

        // E) Re-activate working, attach listener, then logout -> listener removed cleanly!
        repo.setCaptainReadyStatus(true)
        repo.setOnlineStatus(true)
        assertEquals(LocationEligibilityResult.Eligible, repo.checkLocationTrackingEligibility())
        repo.startRealtimeOrdersListener()
        assertEquals(2, attachCount.get())
        assertEquals(1, repo.activeOrdersListenerCount)

        repo.logout()
        assertEquals("Logout must remove active orders listener", 2, removeCount.get())
        assertEquals(0, repo.activeOrdersListenerCount)
        assertNull(repo.serverConfirmedWorkingSession.value)
    }

    // 11. إعادة فتح التطبيق واستعادة الحالة من الخادم (working / ready / offline)
    @Test
    fun appReopen_restoresWorkingOrReadyOrOfflineStrictlyFromServerDocAndShiftKey() = runBlocking {
        val uid = "cap_reopen"
        val backend = FakeBackend().apply {
            seedApprovedCaptain(uid, "reopen@elbatal.com", "Pass1234")
            currentUid = uid
        }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        val shifts = buildAllDayShifts(uid)
        db.shiftDao().insertShifts(shifts)
        val activeShift = (ShiftManager.calculateShiftStatus(shifts) as ShiftStatus.Active).shift
        val expectedKey = repo.buildShiftInstanceKey(activeShift)

        val attachCount = AtomicInteger(0)
        val removeCount = AtomicInteger(0)
        repo.ordersListenerAttachmentOverride = { _, _ ->
            attachCount.incrementAndGet()
            listOf(ListenerRegistration { removeCount.incrementAndGet() })
        }

        // Case 1: Reopen while server doc has NO working state (stale offline) -> local stays offline, no orders listener
        backend.docs[uid]!!["isOnline"] = true // stale flag without workStatus/shiftKey!
        assertTrue(repo.resolveStartupSessionState() is StartupSessionState.Authenticated)
        assertFalse("Stale isOnline without workStatus+shiftKey must restore as OFFLINE", db.captainDao().getCaptain()?.isOnline == true)
        assertEquals(0, attachCount.get())

        // Case 2: Reopen while server doc has confirmed READY only -> local isOnline=true, NO orders listener
        backend.docs[uid]!!.putAll(
            mapOf(
                "isOnline" to true,
                "workStatus" to "ready",
                "readyConfirmed" to true,
                "readyShiftKey" to expectedKey
            )
        )
        assertTrue(repo.resolveStartupSessionState() is StartupSessionState.Authenticated)
        assertTrue("Confirmed ready session restores online readiness flag", db.captainDao().getCaptain()?.isOnline == true)
        assertEquals("ready", repo.serverConfirmedWorkingSession.value?.workStatus)
        assertEquals("Ready state on reopen must NOT start orders listener", 0, attachCount.get())

        // Case 3: Reopen while server doc has confirmed WORKING with matching shiftKey -> restores working + starts orders listener!
        backend.docs[uid]!!.putAll(
            mapOf(
                "isOnline" to true,
                "workStatus" to "working",
                "readyConfirmed" to true,
                "readyShiftKey" to expectedKey,
                "workingShiftKey" to expectedKey
            )
        )
        assertTrue(repo.resolveStartupSessionState() is StartupSessionState.Authenticated)
        assertTrue("Confirmed working session must restore isOnline=true", db.captainDao().getCaptain()?.isOnline == true)
        assertTrue(repo.serverConfirmedWorkingSession.value?.isConfirmedWorking == true)
        assertEquals("Confirmed working reopen must attach orders listener", 1, attachCount.get())
        assertEquals(1, repo.activeOrdersListenerCount)
    }

    // 12. عدم تكرار مستمعي الطلبات عند إعادة استدعاء startRealtimeOrdersListener
    @Test
    fun startRealtimeOrdersListener_calledRepeatedlyForSameWorkingShift_doesNotCreateDuplicateListeners() = runBlocking {
        val uid = "cap_no_dup_listener"
        val backend = FakeBackend().apply {
            seedApprovedCaptain(uid, "nodup@elbatal.com", "Pass1234")
            currentUid = uid
        }
        val repo = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), appContext, authBackendOverride = backend)
        repo.setAccountApprovalSnapshot(CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true))
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = uid, fullName = "عدم تكرار", phoneNumber = "01099990000", isOnline = false, isLoggedIn = true)
        )
        db.shiftDao().insertShifts(buildAllDayShifts(uid))

        val attachCount = AtomicInteger(0)
        val removeCount = AtomicInteger(0)
        repo.ordersListenerAttachmentOverride = { _, _ ->
            attachCount.incrementAndGet()
            listOf(
                ListenerRegistration { removeCount.incrementAndGet() },
                ListenerRegistration { removeCount.incrementAndGet() }
            )
        }

        // Confirm working via server
        repo.setCaptainReadyStatus(true)
        repo.setOnlineStatus(true)
        assertEquals(LocationEligibilityResult.Eligible, repo.checkLocationTrackingEligibility())

        // Call startRealtimeOrdersListener 5 times consecutively
        repeat(5) { repo.startRealtimeOrdersListener() }

        assertEquals("Repeated start calls for the same working shift must attach only ONCE", 1, attachCount.get())
        assertEquals("No churn/removal on idempotent reuse", 0, removeCount.get())
        assertEquals("Active registrations count must equal single attachment size (2)", 2, repo.activeOrdersListenerCount)

        // Stop cleans up all registrations cleanly
        repo.stopRealtimeOrdersListener()
        assertEquals(2, removeCount.get())
        assertEquals(0, repo.activeOrdersListenerCount)
    }
}
