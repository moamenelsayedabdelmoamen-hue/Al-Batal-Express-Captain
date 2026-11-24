package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AuthUserSnapshot
import com.example.data.CaptainAccountStatus
import com.example.data.CaptainApprovalSnapshot
import com.example.data.CaptainAuthBackendPort
import com.example.data.CaptainCloudMetadataPort
import com.example.data.CaptainEntity
import com.example.data.CaptainRepository
import com.example.data.LocationEligibilityResult
import com.example.data.OrderEntity
import com.example.data.OrderStatus
import com.example.data.ShiftEntity
import com.example.data.ShiftStatus
import com.example.data.location.CaptainLocationService
import com.example.data.location.CaptainLocationTracker
import com.example.data.location.GpsConfig
import com.example.data.remote.CloudFunctionsOrderClient
import com.example.data.remote.OrderCallableGateway
import com.example.data.storage.AuthUidProvider
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.NetworkConnectivityChecker
import com.example.data.storage.StorageGateway
import com.example.notifications.CaptainNotificationManager
import com.example.ui.CaptainViewModel
import com.example.ui.UiEvent
import com.example.ui.navigation.ScreenRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaptainPresenceAndLogoutTest {

    private lateinit var appContext: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        CaptainRepository.resetPresenceFallbackStateForTesting()
        CaptainNotificationManager.clearNotificationState(appContext)
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        CaptainRepository.resetPresenceFallbackStateForTesting()
        CaptainNotificationManager.clearNotificationState(appContext)
        db.close()
    }

    private class FakePresenceAuthBackend : CaptainAuthBackendPort {
        var currentUid: String? = null
        val docs = mutableMapOf<String, MutableMap<String, Any?>>()
        val events = mutableListOf<String>()
        var updateFailure: Throwable? = null
        var getDocFailure: Throwable? = null
        var failUpdateOnFcmClearOnly: Boolean = false

        override fun currentUser(): AuthUserSnapshot? = currentUid?.let {
            AuthUserSnapshot(uid = it, email = "$it@elbatal.test", isEmailVerified = true, displayName = "Captain $it")
        }

        override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot =
            throw UnsupportedOperationException()

        override suspend fun sendEmailVerification() {}

        override suspend fun reloadCurrentUser(): AuthUserSnapshot? = currentUser()

        override suspend fun updateDisplayName(displayName: String) {}

        override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot =
            throw UnsupportedOperationException()

        override fun signOut() {
            events += "signOut:$currentUid"
            currentUid = null
        }

        override suspend fun sendPasswordResetEmail(email: String) {}

        override suspend fun createCaptainDoc(uid: String, data: Map<String, Any>) {
            docs[uid] = data.toMutableMap()
        }

        override suspend fun getCaptainDoc(uid: String): Map<String, Any?>? {
            events += "getCaptainDoc:$uid"
            getDocFailure?.let { throw it }
            return docs[uid]?.toMap()
        }

        override suspend fun getCaptainDocFromServer(uid: String): Map<String, Any?>? = getCaptainDoc(uid)

        override suspend fun updateCaptainDoc(uid: String, data: Map<String, Any>) {
            events += "updateCaptainDoc:$uid:${data.keys.sorted().joinToString(",")}"
            if (failUpdateOnFcmClearOnly && data.containsKey("fcmToken")) {
                throw IOException("Simulated FCM token cleanup network failure")
            }
            updateFailure?.let { throw it }
            val target = docs.getOrPut(uid) { mutableMapOf() }
            data.forEach { (k, v) -> target[k] = v }
        }

        override suspend fun queryCaptainEmailByPhoneVariants(
            variants: List<String>,
            numericVariants: List<Long>,
            normalizedLocal: String
        ): String? = null
    }

    private fun buildStorageRepo(uidProvider: () -> String?): FirebaseStorageRepository {
        val gateway = object : StorageGateway {
            override suspend fun putBytesWithProgress(
                storagePath: String,
                bytes: ByteArray,
                contentType: String,
                customMetadata: Map<String, String>,
                requesterUid: String,
                onProgress: (Int) -> Unit
            ) = onProgress(100)

            override suspend fun getBytes(storagePath: String, maxDownloadBytes: Long, requesterUid: String): ByteArray =
                ByteArray(4)
        }
        return FirebaseStorageRepository(
            context = appContext,
            gateway = gateway,
            authUidProvider = AuthUidProvider { uidProvider() },
            connectivityChecker = NetworkConnectivityChecker { true },
            maxAutoRetries = 1
        )
    }

    private fun buildAllDayActiveShifts(uid: String): List<ShiftEntity> = (1..7).map { day ->
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

    private fun samplePendingOrder(): OrderEntity = OrderEntity(
        id = 701L,
        orderNumber = "PND-701",
        firestoreDocId = "ORD_701",
        customerName = "عميل",
        customerPhone = "01000000001",
        storeName = "متجر",
        pickupAddress = "شارع أ",
        deliveryAddress = "شارع ب",
        itemsSummary = "طلب",
        itemsCount = 1,
        orderTotalAmount = 100.0,
        deliveryFee = 20.0,
        distanceKm = 2.0,
        status = OrderStatus.PENDING,
        captainId = ""
    )

    private fun sampleActiveOrder(uid: String): OrderEntity = OrderEntity(
        id = 702L,
        orderNumber = "ACT-702",
        firestoreDocId = "ORD_702",
        customerName = "عميل 2",
        customerPhone = "01000000002",
        storeName = "متجر 2",
        pickupAddress = "شارع ج",
        deliveryAddress = "شارع د",
        itemsSummary = "طلب 2",
        itemsCount = 1,
        orderTotalAmount = 150.0,
        deliveryFee = 30.0,
        distanceKm = 3.0,
        status = OrderStatus.ACCEPTED,
        captainId = uid
    )

    @Test
    fun setOnlineStatus_withAuthBackendOverride_updatesServerAndPreservesStateOnTransientError() = runBlocking {
        val backend = FakePresenceAuthBackend().apply {
            currentUid = "cap_pres_1"
            docs["cap_pres_1"] = mutableMapOf("isOnline" to false, "presenceStatus" to "offline")
        }
        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            storageRepository = buildStorageRepo { backend.currentUid },
            authBackendOverride = backend
        )
        repo.setAccountApprovalSnapshot(
            CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true)
        )
        db.captainDao().insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = "cap_pres_1",
                fullName = "كابتن أحمد",
                phoneNumber = "01012345678",
                isOnline = false,
                isLoggedIn = true
            )
        )
        db.shiftDao().insertShifts(buildAllDayActiveShifts("cap_pres_1"))
        db.orderDao().insertOrder(samplePendingOrder())
        db.orderDao().insertOrder(sampleActiveOrder("cap_pres_1"))

        // 1. Go online during active shift -> updates server & Room
        repo.setOnlineStatus(true)
        assertTrue(db.captainDao().getCaptain()?.isOnline == true)
        assertEquals(true, backend.docs["cap_pres_1"]?.get("isOnline"))
        assertEquals("working", backend.docs["cap_pres_1"]?.get("presenceStatus"))

        // 2. Transient network failure during setOnlineStatus(true) MUST propagate error AND MUST NOT force an online captain offline
        backend.updateFailure = IOException("Temporary network drop")
        try {
            repo.setOnlineStatus(true)
            fail("Expected IOException to propagate from setOnlineStatus(true)")
        } catch (_: IOException) {
            // Expected per PR #14: never swallow errors silently
        }
        assertTrue("Transient network error must not flip already-online captain to offline", db.captainDao().getCaptain()?.isOnline == true)

        // 3. CancellationException must propagate instead of being swallowed
        backend.updateFailure = CancellationException("Coroutine cancelled")
        try {
            repo.setOnlineStatus(true)
            fail("Expected CancellationException to be re-thrown from setOnlineStatus(true)")
        } catch (_: CancellationException) {
            // Expected
        }
        assertTrue("Cancellation must not flip captain to offline", db.captainDao().getCaptain()?.isOnline == true)

        // 4. Going offline when server is temporarily unreachable fails closed locally and marks pending sync
        backend.updateFailure = IOException("Offline sync unreachable")
        repo.setOnlineStatus(false)
        assertFalse(db.captainDao().getCaptain()?.isOnline == true)
        val afterOfflineOrders = repo.allOrdersFlow.first()
        assertFalse("Pending orders must be cleared when going offline", afterOfflineOrders.any { it.id == 701L })
        assertTrue("Active orders must remain intact when going offline", afterOfflineOrders.any { it.id == 702L })

        // 5. Once network recovers, syncPendingOfflineStatusIfNeeded() flushes offline state to server
        backend.updateFailure = null
        val synced = repo.syncPendingOfflineStatusIfNeeded()
        assertTrue("Deferred offline sync should succeed once connectivity returns", synced)
        assertEquals(false, backend.docs["cap_pres_1"]?.get("isOnline"))
        assertEquals("offline", backend.docs["cap_pres_1"]?.get("presenceStatus"))
    }

    @Test
    fun setOnlineStatus_withCloudMetadataAndFunctionsOverridesOnly_neverDeadlocksOrCallsOrderCallable() = runBlocking {
        var orderCallableCalls = 0
        val metadataPort = object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String = "cap_override_only"
            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {}
            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? = null
            override suspend fun submitOrderReceiptDetails(
                orderDocId: String,
                customerName: String,
                customerPhone: String,
                receiptImagePath: String,
                notes: String
            ): Result<String> = Result.success("ok")
        }
        val orderClient = CloudFunctionsOrderClient(
            firebaseAuth = null,
            callableGateway = object : OrderCallableGateway {
                override suspend fun callFunction(functionName: String, payload: Map<String, Any?>): Map<String, Any?> {
                    orderCallableCalls++
                    return mapOf("ok" to true)
                }
            },
            authUidProvider = { "cap_override_only" }
        )
        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            storageRepository = buildStorageRepo { "cap_override_only" },
            cloudMetadataPortOverride = metadataPort,
            cloudFunctionsClientOverride = orderClient
        )
        db.captainDao().insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = "cap_override_only",
                fullName = "كابتن اختبار",
                phoneNumber = "01022223333",
                isOnline = false,
                isLoggedIn = true
            )
        )
        db.shiftDao().insertShifts(buildAllDayActiveShifts("cap_override_only"))
        db.orderDao().insertOrder(samplePendingOrder())

        repo.setOnlineStatus(true)
        assertTrue(db.captainDao().getCaptain()?.isOnline == true)
        assertEquals("Presence transition must not invoke order callable client", 0, orderCallableCalls)

        repo.setOnlineStatus(false)
        assertFalse(db.captainDao().getCaptain()?.isOnline == true)
        assertFalse(repo.allOrdersFlow.first().any { it.id == 701L })
        assertEquals("Offline transition must not invoke order callable client", 0, orderCallableCalls)
    }

    @Test
    fun logout_updatesOfflineAndClearsMatchingFcmTokenBeforeSignOut_withoutTouchingOtherCaptains() = runBlocking {
        val backend = FakePresenceAuthBackend().apply {
            currentUid = "cap_logout_a"
            docs["cap_logout_a"] = mutableMapOf(
                "isLoggedIn" to true,
                "isOnline" to true,
                "presenceStatus" to "working",
                "availabilityStatus" to "working",
                "fcmToken" to "device_token_AAA"
            )
            docs["cap_other_b"] = mutableMapOf(
                "isLoggedIn" to true,
                "isOnline" to true,
                "fcmToken" to "device_token_BBB"
            )
        }
        CaptainNotificationManager.saveFcmToken(appContext, "device_token_AAA")

        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            storageRepository = buildStorageRepo { backend.currentUid },
            authBackendOverride = backend
        )
        repo.setAccountApprovalSnapshot(
            CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true)
        )
        db.captainDao().insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = "cap_logout_a",
                fullName = "كابتن أ",
                phoneNumber = "01033334444",
                isOnline = true,
                isLoggedIn = true
            )
        )
        db.shiftDao().insertShifts(buildAllDayActiveShifts("cap_logout_a"))
        db.orderDao().insertOrder(samplePendingOrder())

        repo.logout()

        // Server updates MUST happen strictly before signOut()
        val signOutIdx = backend.events.indexOfFirst { it.startsWith("signOut:") }
        val offlineUpdateIdx = backend.events.indexOfFirst {
            it.startsWith("updateCaptainDoc:cap_logout_a:") && it.contains("isOnline")
        }
        val fcmClearIdx = backend.events.indexOfFirst {
            it == "updateCaptainDoc:cap_logout_a:fcmToken"
        }
        assertTrue("signOut must be called", signOutIdx >= 0)
        assertTrue("Offline update must precede signOut", offlineUpdateIdx in 0 until signOutIdx)
        assertTrue("Matching FCM token clear must precede signOut", fcmClearIdx in 0 until signOutIdx)

        // Current captain doc updated to offline and matching fcmToken cleared
        assertEquals(false, backend.docs["cap_logout_a"]?.get("isLoggedIn"))
        assertEquals(false, backend.docs["cap_logout_a"]?.get("isOnline"))
        assertEquals("offline", backend.docs["cap_logout_a"]?.get("presenceStatus"))
        assertEquals("offline", backend.docs["cap_logout_a"]?.get("availabilityStatus"))
        assertEquals("", backend.docs["cap_logout_a"]?.get("fcmToken"))

        // Other captain's document MUST remain untouched
        assertEquals("device_token_BBB", backend.docs["cap_other_b"]?.get("fcmToken"))
        assertEquals(true, backend.docs["cap_other_b"]?.get("isOnline"))

        // Local state completely cleaned up
        assertNull(backend.currentUid)
        assertNull(repo.accountApprovalState.value)
        assertNull(db.captainDao().getCaptain())
        assertTrue(db.shiftDao().getAllShiftsFlow().first().isEmpty())
        assertTrue(repo.allOrdersFlow.first().isEmpty())
        assertNull(CaptainNotificationManager.getSavedFcmToken(appContext))
    }

    @Test
    fun logout_doesNotClearServerFcmToken_whenServerHoldsDifferentDeviceToken() = runBlocking {
        val backend = FakePresenceAuthBackend().apply {
            currentUid = "cap_multi_dev"
            docs["cap_multi_dev"] = mutableMapOf(
                "isLoggedIn" to true,
                "isOnline" to true,
                "fcmToken" to "token_from_device_2"
            )
        }
        // This device only holds token_from_device_1
        CaptainNotificationManager.saveFcmToken(appContext, "token_from_device_1")

        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            storageRepository = buildStorageRepo { backend.currentUid },
            authBackendOverride = backend
        )
        db.captainDao().insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = "cap_multi_dev",
                fullName = "كابتن متعدد الأجهزة",
                phoneNumber = "01055556666",
                isOnline = true,
                isLoggedIn = true
            )
        )

        repo.logout()

        // Offline flags are updated, but device 2's fcmToken is NOT wiped!
        assertEquals(false, backend.docs["cap_multi_dev"]?.get("isOnline"))
        assertEquals(false, backend.docs["cap_multi_dev"]?.get("isLoggedIn"))
        assertEquals(
            "Server token belonging to another device must NOT be cleared on logout",
            "token_from_device_2",
            backend.docs["cap_multi_dev"]?.get("fcmToken")
        )
        assertNull(backend.currentUid)
        assertNull(db.captainDao().getCaptain())
    }

    @Test
    fun logout_completesLocalCleanupAndSignOut_evenWhenServerUpdateOrFcmCleanupFails() = runBlocking {
        val backend = FakePresenceAuthBackend().apply {
            currentUid = "cap_offline_logout"
            docs["cap_offline_logout"] = mutableMapOf(
                "isLoggedIn" to true,
                "isOnline" to true,
                "fcmToken" to "device_token_fail"
            )
            updateFailure = IOException("No internet connection")
        }
        CaptainNotificationManager.saveFcmToken(appContext, "device_token_fail")

        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            storageRepository = buildStorageRepo { backend.currentUid },
            authBackendOverride = backend
        )
        repo.setAccountApprovalSnapshot(
            CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true)
        )
        db.captainDao().insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = "cap_offline_logout",
                fullName = "كابتن بدون إنترنت",
                phoneNumber = "01077778888",
                isOnline = true,
                isLoggedIn = true
            )
        )
        db.orderDao().insertOrder(samplePendingOrder())

        // Must NOT throw despite server failure
        repo.logout()

        assertNull("signOut must still run when server update fails", backend.currentUid)
        assertNull("Approval state must be cleared", repo.accountApprovalState.value)
        assertNull("Room captain must be cleared", db.captainDao().getCaptain())
        assertTrue("Room orders must be cleared", repo.allOrdersFlow.first().isEmpty())
        assertNull("Local saved FCM token must be cleared", CaptainNotificationManager.getSavedFcmToken(appContext))
    }

    private fun buildAllDayCanonicalScheduleDoc(): Map<String, Any> {
        val days = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")
        val weeklySchedule = days.associateWith {
            mapOf(
                "startTime" to "00:00",
                "endTime" to "23:59",
                "isDayOff" to false
            )
        }
        return mapOf("weeklySchedule" to weeklySchedule)
    }

    private fun awaitFirstToastWhileIdlingMainLooper(
        events: List<UiEvent>,
        timeoutMs: Long = 3_000L
    ): UiEvent.ShowToast {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            events.filterIsInstance<UiEvent.ShowToast>().firstOrNull()?.let { return it }
            Thread.sleep(20L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return events.filterIsInstance<UiEvent.ShowToast>().firstOrNull()
            ?: throw AssertionError("Timed out waiting for UiEvent.ShowToast; collected events=$events")
    }

    @Test
    fun startWorkButton_whenReadinessServerCallThrows_neverCrashesAndShowsArabicErrorToast() = runBlocking {
        Shadows.shadowOf(appContext).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_start_work_crash_guard"
        var readyCalls = 0
        vm.setCaptainReadyStatusOverride = {
            readyCalls++
            throw IOException("Simulated Cloud Function failure on Start Work")
        }
        vm.repository.setAccountApprovalSnapshot(
            CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true)
        )
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن اختبار",
                phoneNumber = "01088889999",
                isOnline = false,
                isLoggedIn = true
            )
        )
        vm.applyCanonicalShiftSnapshot(
            uid = uid,
            exists = true,
            docData = buildAllDayCanonicalScheduleDoc()
        )
        assertTrue("Expected active shift before pressing Start Work", vm.shiftStatus.value is ShiftStatus.Active)

        val collectedEvents = CopyOnWriteArrayList<UiEvent>()
        val shiftsJob = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.shifts.collect { }
        }
        val collectorJob = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.eventFlow.collect { collectedEvents.add(it) }
        }
        try {
            // Triggers Start Work ("ابدأ العمل"); readiness call throws IOException which previously crashed the app.
            vm.toggleOnlineStatus()

            val toast = awaitFirstToastWhileIdlingMainLooper(collectedEvents)
            assertEquals("Expected readiness callable to be attempted once", 1, readyCalls)
            assertTrue(
                "Expected Start Work Arabic error toast, got: ${toast.message}",
                toast.message.contains("تعذر بدء العمل")
            )

            // Ensure lock flag was released in finally so retrying works cleanly
            collectedEvents.clear()
            vm.toggleOnlineStatus()
            val retryToast = awaitFirstToastWhileIdlingMainLooper(collectedEvents)
            assertEquals("Expected second Start Work tap to retry cleanly", 2, readyCalls)
            assertTrue(
                "Expected Start Work Arabic error toast on retry, got: ${retryToast.message}",
                retryToast.message.contains("تعذر بدء العمل")
            )
        } finally {
            collectorJob.cancel()
            shiftsJob.cancel()
        }
    }

    @Test
    fun onLocationPermissionGranted_whenReadinessServerCallThrows_neverCrashesAndShowsArabicErrorToast() = runBlocking {
        Shadows.shadowOf(appContext).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_perm_grant_crash_guard"
        vm.setCaptainReadyStatusOverride = {
            throw IllegalStateException("Simulated FirebaseFunctions failure after location permission grant")
        }
        vm.repository.setAccountApprovalSnapshot(
            CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true)
        )
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن إذن الموقع",
                phoneNumber = "01066667777",
                isOnline = false,
                isLoggedIn = true
            )
        )
        vm.applyCanonicalShiftSnapshot(
            uid = uid,
            exists = true,
            docData = buildAllDayCanonicalScheduleDoc()
        )
        assertTrue("Expected active shift", vm.shiftStatus.value is ShiftStatus.Active)

        val collectedEvents = CopyOnWriteArrayList<UiEvent>()
        val shiftsJob = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.shifts.collect { }
        }
        val collectorJob = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.eventFlow.collect { collectedEvents.add(it) }
        }
        try {
            vm.onLocationPermissionGranted()

            val toast = awaitFirstToastWhileIdlingMainLooper(collectedEvents)
            assertTrue(
                "Expected Start Work Arabic error toast, got: ${toast.message}",
                toast.message.contains("تعذر بدء العمل")
            )
        } finally {
            collectorJob.cancel()
            shiftsJob.cancel()
        }
    }

    @Test
    fun captainLocationService_usesValidVectorNotificationIcon_notLayerListLauncherIcon() {
        try {
            Shadows.shadowOf(appContext).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            CaptainLocationService.skipLocationClientForTesting = true
            CaptainLocationService.eligibilityCheckerOverride = { LocationEligibilityResult.Eligible }

            val controller = Robolectric.buildService(CaptainLocationService::class.java).create()
            val service = controller.get()
            val startIntent = Intent(appContext, CaptainLocationService::class.java).apply {
                action = GpsConfig.ACTION_START_LOCATION_SERVICE
            }
            service.onStartCommand(startIntent, 0, 1)

            val foregroundNotification = Shadows.shadowOf(service).lastForegroundNotification
            assertNotNull("Service must promote to foreground synchronously with a notification", foregroundNotification)
            assertEquals(
                "Foreground notification must use valid monochrome vector ic_notification_captain instead of layer-list ic_launcher_foreground",
                R.drawable.ic_notification_captain,
                foregroundNotification.smallIcon.resId
            )

            controller.destroy()
        } finally {
            CaptainLocationService.resetTestHooks()
        }
    }

    @Test
    fun onlineButtonInsideActiveShift_opensGpsSettingsWhenDisabled_andAutoStartsLocationAndOrdersOnReturn() = runBlocking {
        Shadows.shadowOf(appContext).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.clearStartedServices()
        CaptainLocationTracker.isAppInForegroundOverrideForTesting = { true }

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_active_shift_online_gps"
        var readyCalls = 0
        var onlineCalls = 0
        var syncOrdersCalls = 0
        vm.setCaptainReadyStatusOverride = { readyCalls++ }
        vm.tickerSetOnlineStatusOverride = { if (it) onlineCalls++ }
        vm.tickerLocationEligibilityOverride = { LocationEligibilityResult.Eligible }
        vm.tickerSyncOrdersOverride = { syncOrdersCalls++ }
        vm.repository.setAccountApprovalSnapshot(
            CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true)
        )
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن شفت نشط",
                phoneNumber = "01099990000",
                isOnline = false,
                isLoggedIn = true
            )
        )
        vm.navigateTo(ScreenRoute.Main.route)
        vm.applyCanonicalShiftSnapshot(
            uid = uid,
            exists = true,
            docData = buildAllDayCanonicalScheduleDoc()
        )
        assertTrue("Expected active shift", vm.shiftStatus.value is ShiftStatus.Active)

        val collectedEvents = CopyOnWriteArrayList<UiEvent>()
        val shiftsJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.shifts.collect { } }
        val collectorJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { collectedEvents.add(it) } }
        try {
            // 1. Tap Online ("متصل") while GPS is disabled -> opens Location Settings
            vm.toggleOnlineStatus()
            val gpsToast = awaitFirstToastWhileIdlingMainLooper(collectedEvents)
            assertTrue("Expected RequestGpsEnable event", collectedEvents.any { it is UiEvent.RequestGpsEnable })
            assertTrue("Expected GPS prompt toast, got: ${gpsToast.message}", gpsToast.message.contains("GPS"))
            assertEquals("Should wait for GPS before activating work", 0, onlineCalls)

            // 2. Captain enables GPS in settings and returns to the app -> auto-activates GPS & orders
            collectedEvents.clear()
            Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            vm.onAppForegrounded()

            val activeToast = awaitFirstToastWhileIdlingMainLooper(collectedEvents)
            assertEquals("Expected readiness confirmation", 1, readyCalls)
            assertEquals("Expected online working activation", 1, onlineCalls)
            assertEquals("Expected orders sync upon going online", 1, syncOrdersCalls)
            val startedService = shadowApp.nextStartedService
            assertNotNull("Expected CaptainLocationService to start", startedService)
            assertEquals(CaptainLocationService::class.java.name, startedService?.component?.className)
            assertTrue(
                "Expected Arabic connected + GPS confirmation toast, got: ${activeToast.message}",
                activeToast.message.contains("متصل") && activeToast.message.contains("GPS")
            )
        } finally {
            CaptainLocationTracker.isAppInForegroundOverrideForTesting = null
            collectorJob.cancel()
            shiftsJob.cancel()
        }
    }

    @Test
    fun onlineButtonInsideActiveShift_whenAlreadyOnlineWithGpsOff_opensGpsSettingsInsteadOfGoingOffline() = runBlocking {
        Shadows.shadowOf(appContext).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        val vm = CaptainViewModel(appContext)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val uid = "cap_online_gps_off"
        vm.repository.setAccountApprovalSnapshot(
            CaptainApprovalSnapshot(status = CaptainAccountStatus.APPROVED, isApproved = true)
        )
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن متصل بدون GPS",
                phoneNumber = "01011223344",
                isOnline = true,
                isLoggedIn = true
            )
        )
        vm.applyCanonicalShiftSnapshot(
            uid = uid,
            exists = true,
            docData = buildAllDayCanonicalScheduleDoc()
        )
        val captainJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.captainState.collect { } }
        val shiftsJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.shifts.collect { } }
        val collectedEvents = CopyOnWriteArrayList<UiEvent>()
        val collectorJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.eventFlow.collect { collectedEvents.add(it) } }
        try {
            val deadline = System.currentTimeMillis() + 2_000L
            while (vm.captainState.value?.isOnline != true && System.currentTimeMillis() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20L)
            }
            assertTrue("Captain must start online", vm.captainState.value?.isOnline == true)

            vm.toggleOnlineStatus()
            val toast = awaitFirstToastWhileIdlingMainLooper(collectedEvents)

            assertTrue("Expected RequestGpsEnable instead of going offline", collectedEvents.any { it is UiEvent.RequestGpsEnable })
            assertTrue("Expected GPS prompt toast, got: ${toast.message}", toast.message.contains("GPS"))
            assertTrue("Captain must stay online while enabling GPS", vm.repository.captainDao.getCaptain()?.isOnline == true)
        } finally {
            collectorJob.cancel()
            shiftsJob.cancel()
            captainJob.cancel()
        }
    }

    @Test
    fun fcmHardFailureClassifier_detectsTooManyRegistrationsAndIgnoresTransientNetworkErrors() {
        val backend = FakePresenceAuthBackend()
        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            authBackendOverride = backend
        )

        assertTrue(repo.isHardFcmRegistrationFailure(IOException("TOO_MANY_REGISTRATIONS")))
        assertTrue(
            repo.isHardFcmRegistrationFailure(
                RuntimeException("wrapped", IOException("java.io.IOException: TOO_MANY_REGISTRATIONS"))
            )
        )
        assertTrue(repo.isHardFcmRegistrationFailure(IOException("MISSING_INSTANCEID_SERVICE")))
        assertFalse(repo.isHardFcmRegistrationFailure(IOException("SERVICE_NOT_AVAILABLE")))
        assertFalse(repo.isHardFcmRegistrationFailure(IOException("Unable to resolve host")))
    }

    @Test
    fun syncFcmToken_afterHardFailure_usesSavedTokenWithoutThrowing() = runBlocking {
        val backend = FakePresenceAuthBackend().apply {
            currentUid = "cap_fcm_fallback"
            docs["cap_fcm_fallback"] = mutableMapOf("accountType" to "captain", "role" to "captain")
        }
        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            authBackendOverride = backend
        )
        CaptainNotificationManager.saveFcmToken(appContext, "cached-fcm-token-123")
        CaptainRepository.fcmTokenHardFailureDetected = true

        repo.syncFcmToken()

        assertEquals(
            "Expected cached token to sync to captain doc",
            "cached-fcm-token-123",
            backend.docs["cap_fcm_fallback"]?.get("fcmToken")
        )
    }
}
