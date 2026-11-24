package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AuthUserSnapshot
import com.example.data.CaptainAccountApprovalResolver
import com.example.data.CaptainAccountStatus
import com.example.data.CaptainApprovalSnapshot
import com.example.data.CaptainAuthBackendPort
import com.example.data.CaptainCloudMetadataPort
import com.example.data.CaptainEntity
import com.example.data.CaptainRepository
import com.example.data.LoginOutcome
import com.example.data.OrderEntity
import com.example.data.OrderStatus
import com.example.data.StartupSessionState
import com.example.data.remote.CloudFunctionsOrderClient
import com.example.data.remote.OrderCallableGateway
import com.example.data.storage.AuthUidProvider
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.NetworkConnectivityChecker
import com.example.data.storage.StorageGateway
import com.example.ui.CaptainViewModel
import com.example.ui.navigation.ScreenRoute
import com.example.ui.screens.AccountApprovalScreen
import com.example.ui.theme.ElBatalTheme
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.flow.first
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h1400dp")
class CaptainAccountApprovalTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.getSharedPreferences("elbatal_phone_auth_registry", Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private data class FakeUser(
        val uid: String,
        val email: String,
        val password: String,
        var isEmailVerified: Boolean,
        var displayName: String = ""
    ) {
        fun toSnapshot() = AuthUserSnapshot(uid, email, isEmailVerified, displayName)
    }

    private class FakeApprovalBackend : CaptainAuthBackendPort {
        val usersByEmail = mutableMapOf<String, FakeUser>()
        val usersByUid = mutableMapOf<String, FakeUser>()
        val captains = mutableMapOf<String, MutableMap<String, Any?>>()
        var currentUid: String? = null
        var serverDocFailure: Throwable? = null
        var serverReadCount: Int = 0
        private var seq = 1

        fun seedUser(uid: String, email: String, password: String, verified: Boolean, name: String = "كابتن"): FakeUser {
            val u = FakeUser(uid, email.lowercase(), password, verified, name)
            usersByEmail[email.lowercase()] = u
            usersByUid[uid] = u
            return u
        }

        override fun currentUser(): AuthUserSnapshot? = currentUid?.let { usersByUid[it]?.toSnapshot() }

        override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val norm = email.trim().lowercase()
            val uid = "approval_uid_${seq++}"
            val u = FakeUser(uid, norm, password, false)
            usersByEmail[norm] = u
            usersByUid[uid] = u
            currentUid = uid
            return u.toSnapshot()
        }

        override suspend fun sendEmailVerification() {
            currentUser() ?: throw IllegalStateException("No signed in user")
        }

        override suspend fun reloadCurrentUser(): AuthUserSnapshot? = currentUser()

        override suspend fun updateDisplayName(displayName: String) {
            currentUid?.let { usersByUid[it]?.displayName = displayName }
        }

        override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val u = usersByEmail[email.trim().lowercase()] ?: throw IllegalArgumentException("User not found")
            if (u.password != password) throw IllegalArgumentException("Wrong password")
            currentUid = u.uid
            return u.toSnapshot()
        }

        override fun signOut() {
            currentUid = null
        }

        override suspend fun sendPasswordResetEmail(email: String) {}

        override suspend fun createCaptainDoc(uid: String, data: Map<String, Any>) {
            captains[uid] = data.toMutableMap()
        }

        override suspend fun getCaptainDoc(uid: String): Map<String, Any?>? {
            return getCaptainDocFromServer(uid)
        }

        override suspend fun getCaptainDocFromServer(uid: String): Map<String, Any?>? {
            serverReadCount += 1
            serverDocFailure?.let { throw it }
            return captains[uid]?.toMap()
        }

        override suspend fun updateCaptainDoc(uid: String, data: Map<String, Any>) {
            val doc = captains[uid] ?: throw IllegalStateException("Missing captains/$uid")
            data.forEach { (k, v) -> doc[k] = v }
        }

        override suspend fun queryCaptainEmailByPhoneVariants(
            variants: List<String>,
            numericVariants: List<Long>,
            normalizedLocal: String
        ): String? {
            for ((_, doc) in captains) {
                if (doc["accountType"]?.toString()?.lowercase() != "captain") continue
                val p = doc["phone"]?.toString().orEmpty()
                if (p in variants || p == normalizedLocal) return doc["email"]?.toString()
            }
            return null
        }
    }

    private fun buildRepo(backend: FakeApprovalBackend): CaptainRepository {
        val storageGateway = object : StorageGateway {
            override suspend fun putBytesWithProgress(
                storagePath: String,
                bytes: ByteArray,
                contentType: String,
                customMetadata: Map<String, String>,
                requesterUid: String,
                onProgress: (Int) -> Unit
            ) = onProgress(100)

            override suspend fun getBytes(storagePath: String, maxDownloadBytes: Long, requesterUid: String): ByteArray =
                ByteArray(8) { 1 }
        }
        val storageRepo = FirebaseStorageRepository(
            context = appContext,
            gateway = storageGateway,
            authUidProvider = AuthUidProvider { backend.currentUid },
            connectivityChecker = NetworkConnectivityChecker { true },
            maxAutoRetries = 1
        )
        val metaPort = object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String? = backend.currentUid
            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {
                backend.updateCaptainDoc(uid, fields)
            }
            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? {
                return backend.getCaptainDoc(uid)?.mapValues { it.value?.toString().orEmpty() }
            }
            override suspend fun submitOrderReceiptDetails(
                orderDocId: String,
                customerName: String,
                customerPhone: String,
                receiptImagePath: String,
                notes: String
            ): Result<String> = Result.success("ok")
        }
        val cfClient = CloudFunctionsOrderClient(
            firebaseAuth = null,
            callableGateway = object : OrderCallableGateway {
                override suspend fun callFunction(functionName: String, payload: Map<String, Any?>): Map<String, Any?> =
                    mapOf("success" to true, "message" to "ok")
            },
            authUidProvider = { backend.currentUid ?: "anon" }
        )
        return CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            storageRepository = storageRepo,
            cloudMetadataPortOverride = metaPort,
            cloudFunctionsClientOverride = cfClient,
            authBackendOverride = backend
        )
    }

    // Scenario 1 & 2: New captain signup creates pending/unapproved doc and blocks login & startup entry
    @Test
    fun scenario1_and_2_newSignupSetsPendingUnapproved_andPendingCaptainCannotEnterMain() = runBlocking {
        val backend = FakeApprovalBackend()
        val repo = buildRepo(backend)

        val regRes = repo.registerCaptainWithEmailVerification(
            fullName = "كابتن جديد",
            phone = "01055443322",
            email = "newcap@elbatal.com",
            pass = "Pass1234",
            address = "القاهرة - مدينة نصر"
        )
        assertTrue(regRes.isSuccess)
        val uid = backend.usersByEmail["newcap@elbatal.com"]!!.uid
        val doc = backend.captains[uid]!!

        // Verify exact server fields on creation
        assertEquals("pending", doc["accountStatus"])
        assertEquals(false, doc["isApproved"])
        assertEquals("pending", doc["status"])
        assertEquals(false, doc["isOnline"])
        assertEquals(false, doc["isLoggedIn"])
        assertTrue("createdAt must be FieldValue.serverTimestamp()", doc["createdAt"] is FieldValue)
        assertFalse("Local Room session must NOT be logged in", db.captainDao().getCaptain()?.isLoggedIn == true)

        // Verify email, then attempt login + startup check while still pending
        backend.usersByUid[uid]!!.isEmailVerified = true
        val verifyRes = repo.checkEmailVerification()
        assertTrue(verifyRes.isSuccess && verifyRes.getOrNull() == true)
        assertFalse("Pending captain must NOT be approved after email verification", repo.isCurrentSessionApproved)
        assertEquals(CaptainAccountStatus.PENDING, repo.accountApprovalState.value?.status)

        val startupPending = repo.resolveStartupSessionState()
        assertTrue("Startup must route pending captain to AwaitingApproval", startupPending is StartupSessionState.AwaitingApproval)
        assertEquals(CaptainAccountStatus.PENDING, (startupPending as StartupSessionState.AwaitingApproval).approval.status)

        // Attempt fresh login by email and by phone -> both blocked with AwaitingApproval
        val emailLogin = repo.login("newcap@elbatal.com", "Pass1234")
        assertTrue(emailLogin is LoginOutcome.AwaitingApproval)
        assertEquals(CaptainAccountStatus.PENDING, (emailLogin as LoginOutcome.AwaitingApproval).approval.status)

        val phoneLogin = repo.login("01055443322", "Pass1234")
        assertTrue(phoneLogin is LoginOutcome.AwaitingApproval)
        assertFalse("Pending captain cannot go online", repo.isCurrentSessionApproved)
    }

    // Scenario 3: Admin approves -> refresh & login unlock Main; legacy active captains remain compatible
    @Test
    fun scenario3_adminApprovalUnlocksAccessViaRefreshAndLogin_andPreservesLegacyCaptains() = runBlocking {
        val backend = FakeApprovalBackend()
        val repo = buildRepo(backend)

        val uid = "cap_approve_3"
        backend.seedUser(uid, "approve3@elbatal.com", "Pass1234", verified = true, name = "كابتن معتمد")
        backend.captains[uid] = mutableMapOf(
            "uid" to uid,
            "fullName" to "كابتن معتمد",
            "email" to "approve3@elbatal.com",
            "phone" to "01011112222",
            "accountType" to "captain",
            "role" to "captain",
            "accountStatus" to "pending",
            "isApproved" to false,
            "status" to "pending"
        )

        // Blocked while pending
        val beforeApproval = repo.login("approve3@elbatal.com", "Pass1234")
        assertTrue(beforeApproval is LoginOutcome.AwaitingApproval)

        // Admin approves in Firestore
        backend.captains[uid]!!["accountStatus"] = "approved"
        backend.captains[uid]!!["isApproved"] = true
        backend.captains[uid]!!["status"] = "active"

        // Manual refresh immediately unlocks
        val refreshed = repo.refreshLiveAccountApproval()
        assertTrue("Server refresh must unlock approved captain", refreshed.canEnterApp)
        assertEquals(CaptainAccountStatus.APPROVED, refreshed.status)
        assertTrue(repo.isCurrentSessionApproved)
        assertTrue(db.captainDao().getCaptain()?.isLoggedIn == true)

        // Startup session also resolves to Authenticated
        val startup = repo.resolveStartupSessionState()
        assertTrue(startup is StartupSessionState.Authenticated)

        // Legacy active captain (no accountStatus / isApproved fields) still logs in cleanly
        val legacyUid = "cap_legacy_3"
        backend.seedUser(legacyUid, "legacy3@elbatal.com", "Pass1234", verified = true)
        backend.captains[legacyUid] = mutableMapOf(
            "uid" to legacyUid,
            "fullName" to "كابتن قديم",
            "email" to "legacy3@elbatal.com",
            "accountType" to "captain",
            "status" to "active"
        )
        val legacyLogin = repo.login("legacy3@elbatal.com", "Pass1234")
        assertTrue("Legacy active captain must remain approved", legacyLogin is LoginOutcome.Success)
    }

    // Scenario 4 & 5: Admin rejects (with reason), suspends, or blocks -> blocked with exact status & reason
    @Test
    fun scenario4_and_5_rejectedWithReason_suspended_andBlockedAreDeniedEntry() = runBlocking {
        val backend = FakeApprovalBackend()
        val repo = buildRepo(backend)
        val uid = "cap_status_45"
        backend.seedUser(uid, "status45@elbatal.com", "Pass1234", verified = true)

        // Rejected with rejectionReason
        backend.captains[uid] = mutableMapOf(
            "uid" to uid,
            "fullName" to "كابتن مرفوض",
            "email" to "status45@elbatal.com",
            "accountType" to "captain",
            "accountStatus" to "rejected",
            "isApproved" to false,
            "status" to "rejected",
            "rejectionReason" to "صورة رخصة القيادة غير واضحة"
        )
        val rejOutcome = repo.login("status45@elbatal.com", "Pass1234")
        assertTrue(rejOutcome is LoginOutcome.AwaitingApproval)
        val rejSnap = (rejOutcome as LoginOutcome.AwaitingApproval).approval
        assertEquals(CaptainAccountStatus.REJECTED, rejSnap.status)
        assertFalse(rejSnap.canEnterApp)
        assertEquals("صورة رخصة القيادة غير واضحة", rejSnap.rejectionReason)

        // Suspended
        backend.captains[uid]!!["accountStatus"] = "suspended"
        backend.captains[uid]!!["status"] = "suspended"
        backend.captains[uid]!!["rejectionReason"] = "مراجعة مخالفات تشغيلية"
        val suspOutcome = repo.login("status45@elbatal.com", "Pass1234")
        assertTrue(suspOutcome is LoginOutcome.AwaitingApproval)
        assertEquals(CaptainAccountStatus.SUSPENDED, (suspOutcome as LoginOutcome.AwaitingApproval).approval.status)

        // Blocked
        backend.captains[uid]!!["accountStatus"] = "blocked"
        backend.captains[uid]!!["status"] = "blocked"
        val blockOutcome = repo.login("status45@elbatal.com", "Pass1234")
        assertTrue(blockOutcome is LoginOutcome.AwaitingApproval)
        assertEquals(CaptainAccountStatus.BLOCKED, (blockOutcome as LoginOutcome.AwaitingApproval).approval.status)
    }

    // Scenario 6: Client-side tampering / inconsistent approval flags fail closed
    @Test
    fun scenario6_resolverFailsClosedOnTamperedOrInconsistentFlags() {
        val mismatch = CaptainAccountApprovalResolver.resolve(
            mapOf("accountStatus" to "approved", "isApproved" to false)
        )
        assertFalse("accountStatus=approved with isApproved=false must fail closed", mismatch.canEnterApp)
        assertEquals(CaptainAccountStatus.PENDING, mismatch.status)

        val blockedFlag = CaptainAccountApprovalResolver.resolve(
            mapOf("accountStatus" to "approved", "isApproved" to true, "isBlocked" to true)
        )
        assertFalse("isBlocked=true must override approval", blockedFlag.canEnterApp)
        assertEquals(CaptainAccountStatus.BLOCKED, blockedFlag.status)

        val unknownStatus = CaptainAccountApprovalResolver.resolve(
            mapOf("accountStatus" to "hacked_status", "isApproved" to true)
        )
        assertFalse("Unknown accountStatus must fail closed", unknownStatus.canEnterApp)
        assertEquals(CaptainAccountStatus.PENDING, unknownStatus.status)
    }

    // Scenario 7: Network outage / Firestore failure MUST fail closed — NEVER trust local Room DB!
    @Test
    fun scenario7_networkFailureFailsClosed_evenWhenLocalRoomClaimsLoggedInActive() = runBlocking {
        val backend = FakeApprovalBackend()
        val repo = buildRepo(backend)
        val uid = "cap_offline_7"
        backend.seedUser(uid, "offline7@elbatal.com", "Pass1234", verified = true)
        backend.currentUid = uid

        // Seed stale/tampered local Room claiming captain is logged in & active
        db.captainDao().insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن محلي",
                phoneNumber = "01022223333",
                email = "offline7@elbatal.com",
                status = "active",
                isOnline = true,
                isLoggedIn = true
            )
        )

        // Simulate Firestore server unreachable
        backend.serverDocFailure = RuntimeException("UNAVAILABLE: Unable to resolve host firestore.googleapis.com")

        val startup = repo.resolveStartupSessionState()
        assertTrue(
            "Startup MUST fail closed with ApprovalCheckFailed on network error (never Authenticated!)",
            startup is StartupSessionState.ApprovalCheckFailed
        )
        assertFalse("Local approval flag must be false", repo.isCurrentSessionApproved)
        assertFalse("Stale Room session must be revoked to offline/logged-out on failure", db.captainDao().getCaptain()?.isLoggedIn == true)

        val loginAttempt = repo.login("offline7@elbatal.com", "Pass1234")
        assertTrue("Login MUST fail closed on Firestore server read error", loginAttempt is LoginOutcome.Error)
        assertEquals(
            "A temporary Firestore outage must not destroy the Firebase Auth session",
            uid,
            backend.currentUid
        )
        assertFalse(
            "A Firestore outage must never leave the local captain operational",
            db.captainDao().getCaptain()?.isLoggedIn == true
        )

        val refreshAttempt = repo.refreshLiveAccountApproval()
        assertFalse("Refresh MUST fail closed on network outage", refreshAttempt.canEnterApp)
        assertNotNull("networkError message must be surfaced", refreshAttempt.networkError)
    }

    // Scenario 8: Logout clears approval state & Room, relogin strictly re-queries Firestore server
    @Test
    fun scenario8_logoutClearsLocalApprovalState_andReloginReverifiesFromServer() = runBlocking {
        val backend = FakeApprovalBackend()
        val repo = buildRepo(backend)
        val uid = "cap_relogin_8"
        backend.seedUser(uid, "relogin8@elbatal.com", "Pass1234", verified = true)
        backend.captains[uid] = mutableMapOf(
            "uid" to uid,
            "fullName" to "كابتن خروج",
            "email" to "relogin8@elbatal.com",
            "accountType" to "captain",
            "accountStatus" to "approved",
            "isApproved" to true,
            "status" to "active"
        )

        assertTrue(repo.login("relogin8@elbatal.com", "Pass1234") is LoginOutcome.Success)
        assertTrue(repo.isCurrentSessionApproved)
        val readsBeforeLogout = backend.serverReadCount

        repo.logout()
        assertNull("accountApprovalState must be cleared on logout", repo.accountApprovalState.value)
        assertFalse(repo.isCurrentSessionApproved)
        assertNull("Room DB captain must be cleared on logout", db.captainDao().getCaptain())

        // Admin suspends captain while logged out
        backend.captains[uid]!!["accountStatus"] = "suspended"
        backend.captains[uid]!!["isApproved"] = false
        backend.captains[uid]!!["status"] = "suspended"

        val relogin = repo.login("relogin8@elbatal.com", "Pass1234")
        assertTrue("Relogin MUST re-query server", backend.serverReadCount > readsBeforeLogout)
        assertTrue("Suspended captain must be blocked upon relogin", relogin is LoginOutcome.AwaitingApproval)
        assertEquals(CaptainAccountStatus.SUSPENDED, (relogin as LoginOutcome.AwaitingApproval).approval.status)
    }

    // Scenario 9: Live revocation while Main session is open + cache-only snapshot cannot unlock Main
    @Test
    fun scenario9_liveApprovalUnlockIgnoresCache_andLiveSuspensionImmediatelyRevokesMainSession() = runBlocking {
        val vm = CaptainViewModel(appContext)
        val uid = "cap_live_9"

        // Start on AccountApproval screen as pending
        vm.navigateTo(ScreenRoute.AccountApproval.route)

        // Stale cache snapshot claiming approved MUST NOT unlock Main
        vm.applyLiveAccountApprovalSnapshot(
            uid = uid,
            docData = mapOf(
                "uid" to uid,
                "fullName" to "كابتن مباشر",
                "email" to "live9@elbatal.com",
                "accountType" to "captain",
                "accountStatus" to "approved",
                "isApproved" to true,
                "status" to "active"
            ),
            isFromCache = true
        )
        assertEquals(
            "Offline cache snapshot must NOT unlock AccountApproval -> Main",
            ScreenRoute.AccountApproval.route,
            vm.currentRoute.value
        )

        // Live server-confirmed approval snapshot automatically unlocks Main!
        vm.applyLiveAccountApprovalSnapshot(
            uid = uid,
            docData = mapOf(
                "uid" to uid,
                "fullName" to "كابتن مباشر",
                "email" to "live9@elbatal.com",
                "accountType" to "captain",
                "accountStatus" to "approved",
                "isApproved" to true,
                "status" to "active"
            ),
            isFromCache = false
        )
        assertEquals(ScreenRoute.Main.route, vm.currentRoute.value)
        assertTrue(vm.repository.isCurrentSessionApproved)

        // Seed pending order & online local state while on Main
        vm.repository.captainDao.insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن مباشر",
                phoneNumber = "01033334444",
                email = "live9@elbatal.com",
                status = "active",
                isOnline = true,
                isLoggedIn = true
            )
        )
        vm.repository.orderDao.insertOrder(
            OrderEntity(
                id = 909L,
                orderNumber = "EXP-909",
                firestoreDocId = "ORD_909",
                customerName = "عميل",
                customerPhone = "01000000000",
                storeName = "مطعم",
                pickupAddress = "مدينة نصر",
                deliveryAddress = "المعادي",
                itemsSummary = "وجبة",
                itemsCount = 1,
                orderTotalAmount = 100.0,
                deliveryFee = 25.0,
                distanceKm = 2.0,
                status = OrderStatus.PENDING
            )
        )

        // Admin suspends captain live while app is open on Main -> immediate revocation!
        val revoked = vm.applyLiveAccountApprovalSnapshot(
            uid = uid,
            docData = mapOf(
                "uid" to uid,
                "fullName" to "كابتن مباشر",
                "email" to "live9@elbatal.com",
                "accountType" to "captain",
                "accountStatus" to "suspended",
                "isApproved" to false,
                "status" to "suspended",
                "rejectionReason" to "تم الإيقاف لمراجعة المستندات"
            ),
            isFromCache = false
        )
        assertEquals(CaptainAccountStatus.SUSPENDED, revoked.status)
        assertFalse(vm.repository.isCurrentSessionApproved)
        assertEquals(
            "Open Main session MUST immediately route to AccountApproval on suspension",
            ScreenRoute.AccountApproval.route,
            vm.currentRoute.value
        )
        val localCap = vm.repository.captainDao.getCaptain()
        assertFalse("Local captain must be forced offline", localCap?.isOnline == true)
        assertFalse("Local captain must be logged out", localCap?.isLoggedIn == true)
        assertTrue(
            "Pending orders must be cleared immediately on revocation",
            vm.repository.orderDao.getAvailablePendingOrdersFlow().first().isEmpty()
        )
    }

    // Compose UI Test: AccountApprovalScreen renders Arabic copy, rejection reason, and buttons
    @Test
    fun uiScreen_accountApprovalScreen_rendersPendingAndRejectedStatesAndButtons() {
        var refreshClicked = false
        var logoutClicked = false
        val currentSnapshot = androidx.compose.runtime.mutableStateOf(
            CaptainApprovalSnapshot(
                status = CaptainAccountStatus.PENDING,
                isApproved = false,
                captainName = "كابتن عمر",
                email = "omar@elbatal.com"
            )
        )

        composeTestRule.setContent {
            ElBatalTheme {
                AccountApprovalScreen(
                    approval = currentSnapshot.value,
                    isRefreshing = false,
                    onRefreshStatus = { refreshClicked = true },
                    onLogout = { logoutClicked = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Pending copy matches specification
        composeTestRule.onNodeWithTag("account_approval_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("حسابك قيد المراجعة").assertIsDisplayed()
        composeTestRule.onNodeWithText("تم استلام بياناتك بنجاح، وسيتمكن فريق الإدارة من مراجعتها. ستتمكن من استخدام التطبيق بعد الموافقة على حسابك.").assertIsDisplayed()

        // Buttons work
        composeTestRule.onNodeWithTag("approval_refresh_button").performScrollTo().performClick()
        assertTrue("Refresh button must invoke callback", refreshClicked)
        composeTestRule.onNodeWithTag("approval_logout_button").performScrollTo().performClick()
        assertTrue("Logout button must invoke callback", logoutClicked)

        // Switch to Rejected with reason -> reason box displays clearly
        composeTestRule.runOnUiThread {
            currentSnapshot.value = CaptainApprovalSnapshot(
                status = CaptainAccountStatus.REJECTED,
                isApproved = false,
                rejectionReason = "صورة البطاقة الأمامية غير واضحة",
                captainName = "كابتن عمر",
                email = "omar@elbatal.com"
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("approval_rejection_reason_box").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("صورة البطاقة الأمامية غير واضحة").assertIsDisplayed()
    }
}
