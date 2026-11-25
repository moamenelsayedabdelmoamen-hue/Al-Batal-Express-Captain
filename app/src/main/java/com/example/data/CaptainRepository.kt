package com.example.data

import android.content.Context
import android.location.Location
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import com.example.data.location.GpsConfig
import com.example.data.remote.CloudFunctionsOrderClient
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.ImageUploadState
import com.example.notifications.CaptainNotificationManager
import com.example.notifications.ShiftNotificationScheduler
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class PresenceErrorReason {
    UNAUTHENTICATED,
    PERMISSION_DENIED,
    FAILED_PRECONDITION,
    NOT_FOUND,
    UNAVAILABLE,
    DEADLINE_EXCEEDED
}

class CaptainPresenceException(
    val reason: PresenceErrorReason,
    val userMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause)

data class ServerConfirmedWorkingSession(
    val uid: String,
    val workStatus: String,
    val readyConfirmed: Boolean,
    val readyShiftKey: String,
    val workingShiftKey: String
) {
    val isConfirmedWorking: Boolean
        get() = workStatus == "working" &&
            readyConfirmed &&
            readyShiftKey.isNotBlank() &&
            workingShiftKey == readyShiftKey
}

sealed class LoginOutcome {
    object Success : LoginOutcome()
    data class EmailNotVerified(val email: String) : LoginOutcome()
    data class AwaitingApproval(val approval: CaptainApprovalSnapshot) : LoginOutcome()
    data class Error(val message: String) : LoginOutcome()
}

data class SyncOrdersSummary(
    val totalSynced: Int,
    val pendingCount: Int,
    val activeCount: Int
)

/**
 * Startup session routing result evaluated right after the intro splash video completes or fails.
 */
sealed class StartupSessionState {
    /** Captain has a valid verified Firebase Auth session AND server-verified admin approval in captains/{uid}. */
    object Authenticated : StartupSessionState()

    /** Captain signed up in Firebase Auth but has not completed email verification / registration yet. */
    data class NeedsEmailVerification(val email: String) : StartupSessionState()

    /** Captain email is verified, but Firestore accountStatus is pending, rejected, suspended, or blocked. */
    data class AwaitingApproval(val approval: CaptainApprovalSnapshot) : StartupSessionState()

    /** Firestore approval check failed (e.g. offline/network error). Fail-closed: NEVER enter Main. */
    data class ApprovalCheckFailed(val approval: CaptainApprovalSnapshot) : StartupSessionState()

    /** No valid Firebase Auth session (signed out, deleted, or non-captain account). */
    object Unauthenticated : StartupSessionState()
}

sealed class LocationEligibilityResult {
    object Eligible : LocationEligibilityResult()
    data class Ineligible(val reason: String) : LocationEligibilityResult()
    data class NetworkError(val cause: Throwable) : LocationEligibilityResult()
}

sealed class LocationUploadResult {
    object Success : LocationUploadResult()
    data class TransientFailure(val cause: Throwable) : LocationUploadResult()
    data class ConfirmedIneligible(
        val reason: String,
        val shouldMarkOffline: Boolean
    ) : LocationUploadResult()
}

interface CaptainCloudMetadataPort {
    fun currentAuthUid(): String?
    suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>)
    suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>?
    suspend fun submitOrderReceiptDetails(
        orderDocId: String,
        customerName: String,
        customerPhone: String,
        receiptImagePath: String,
        notes: String
    ): Result<String>
}



data class AuthUserSnapshot(
    val uid: String,
    val email: String,
    val isEmailVerified: Boolean,
    val displayName: String = ""
)

interface CaptainAuthBackendPort {
    fun currentUser(): AuthUserSnapshot?
    suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot
    suspend fun sendEmailVerification()
    suspend fun reloadCurrentUser(): AuthUserSnapshot?
    suspend fun updateDisplayName(displayName: String)
    suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot
    fun signOut()
    suspend fun sendPasswordResetEmail(email: String)
    suspend fun createCaptainDoc(uid: String, data: Map<String, Any>)
    suspend fun getCaptainDoc(uid: String): Map<String, Any?>?
    /**
     * Reads `captains/{uid}` strictly from Firestore server (bypassing stale local cache)
     * for security-critical admin approval checks.
     */
    suspend fun getCaptainDocFromServer(uid: String): Map<String, Any?>? = getCaptainDoc(uid)
    suspend fun updateCaptainDoc(uid: String, data: Map<String, Any>)
    suspend fun queryCaptainEmailByPhoneVariants(
        variants: List<String>,
        numericVariants: List<Long>,
        normalizedLocal: String
    ): String?
    suspend fun deleteCurrentUser() {}
    suspend fun refreshIdToken() {}
}

internal data class ParsedOrderSnapshot(
    val docId: String,
    val order: OrderEntity,
    val docCaptainId: String,
    val offeredCaptainId: String,
    val isExcluded: Boolean,
    val hasMatchingSecondaryUid: Boolean,
    val hasMatchingPhone: Boolean
)

class CaptainRepository(
    val captainDao: CaptainDao,
    val orderDao: OrderDao,
    val shiftDao: ShiftDao? = null,
    context: Context? = null,
    val storageRepository: FirebaseStorageRepository = FirebaseStorageRepository.getInstance(context?.applicationContext),
    private val cloudMetadataPortOverride: CaptainCloudMetadataPort? = null,
    private val cloudFunctionsClientOverride: com.example.data.remote.CloudFunctionsOrderClient? = null,
    private val authBackendOverride: CaptainAuthBackendPort? = null
) {
    // Always retain Application Context only — never leak an Activity Context.
    internal val context: Context? = context?.applicationContext

    suspend fun saveShiftsLocally(uid: String, shifts: List<ShiftEntity>) {
        saveShiftsAndScheduleAlarms(uid, shifts)
        _isShiftSynced.value = true
        _isShiftNetworkConnected.value = true
    }
    constructor(captainDao: CaptainDao, orderDao: OrderDao, context: Context?) : this(
        captainDao,
        orderDao,
        context?.applicationContext?.let { AppDatabase.getDatabase(it).shiftDao() },
        context?.applicationContext
    )

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Strictly in-memory server-verified approval state (NEVER trusted from Room or SharedPreferences).
    private val _accountApprovalState = MutableStateFlow<CaptainApprovalSnapshot?>(null)
    val accountApprovalState: StateFlow<CaptainApprovalSnapshot?> = _accountApprovalState.asStateFlow()

    // Strictly in-memory server-confirmed shift presence state (NEVER trusted from local Room isOnline).
    private val _serverConfirmedWorkingSession = MutableStateFlow<ServerConfirmedWorkingSession?>(null)
    val serverConfirmedWorkingSession: StateFlow<ServerConfirmedWorkingSession?> =
        _serverConfirmedWorkingSession.asStateFlow()

    private val ordersListenerLock = Any()
    private val ordersListenerConsumers = mutableSetOf<String>()
    private val listenerMatchedDocIds = mutableMapOf<String, MutableSet<String>>()
    private val orderSnapshotMutex = Mutex()
    private val shiftSyncMutex = Mutex()
    private val historySyncMutex = Mutex()
    private val walletReconcileLock = Any()
    private var walletReconcileJob: Job? = null

    @Volatile
    private var lastShiftSyncUid: String? = null
    @Volatile
    private var lastShiftSyncAtMs: Long = 0L

    @Volatile
    private var lastFullHistorySyncUid: String? = null
    @Volatile
    private var lastFullHistorySyncAtMs: Long = 0L
    @Volatile
    private var lastOperationalHistorySyncUid: String? = null
    @Volatile
    private var lastOperationalHistorySyncAtMs: Long = 0L
    private val syncedDateKeysCache = ConcurrentHashMap<String, Long>()

    @Volatile
    private var activeOrdersListenerUid: String? = null
    @Volatile
    private var activeOrdersListenerShiftKey: String? = null

    internal var ordersListenerAttachmentOverride:
        ((uid: String, shiftKey: String) -> List<ListenerRegistration>)? = null
    internal var archivedOrdersDateSyncOverride:
        (suspend (uid: String, dateKey: String, forceRefresh: Boolean) -> Result<Int>)? = null
    internal var walletReconcileDebounceMs: Long = WALLET_RECONCILE_DEBOUNCE_MS
    internal var onWalletReconciledForTesting: (() -> Unit)? = null

    internal val activeOrdersListenerCount: Int
        get() = synchronized(ordersListenerLock) { ordersListenerRegistrations.size }

    internal val activeOrdersListenerConsumerCount: Int
        get() = synchronized(ordersListenerLock) { ordersListenerConsumers.size }

    val isCurrentSessionApproved: Boolean
        get() = _accountApprovalState.value?.canEnterApp == true

    private val hasTestBackendOverride: Boolean
        get() = authBackendOverride != null || cloudMetadataPortOverride != null || cloudFunctionsClientOverride != null

    internal fun setAccountApprovalSnapshot(snapshot: CaptainApprovalSnapshot?) {
        _accountApprovalState.value = snapshot
    }

    internal fun clearServerConfirmedWorkingSession() {
        _serverConfirmedWorkingSession.value = null
    }

    private fun resolveCurrentCaptainUid(): String? {
        authBackendOverride?.let { override ->
            return runCatching { override.currentUser()?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        cloudMetadataPortOverride?.let { override ->
            return runCatching { override.currentAuthUid() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return runCatching { authBackend.currentUser()?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { cloudMetadataPort.currentAuthUid() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    internal var presenceCallableOverride: (suspend (action: String) -> Unit)? = null

    private fun presenceFunctions(): FirebaseFunctions =
        FirebaseFunctions.getInstance(
            FirebaseApp.getInstance(),
            CloudFunctionsOrderClient.FUNCTIONS_REGION
        )

    private suspend fun invokeShiftPresenceCallable(action: String) {
        val override = presenceCallableOverride
        if (override != null) {
            withContext(Dispatchers.IO) {
                withTimeout(PRESENCE_CALLABLE_TIMEOUT_MS) {
                    override(action)
                }
            }
            return
        }
        withContext(Dispatchers.IO) {
            withTimeout(PRESENCE_CALLABLE_TIMEOUT_MS) {
                presenceFunctions()
                    .getHttpsCallable("updateCaptainShiftPresence")
                    .call(mapOf("action" to action))
                    .await()
            }
        }
    }

    internal fun isConfirmedPresenceRejection(error: Throwable): Boolean {
        if (error is CaptainPresenceException) {
            return error.reason in setOf(
                PresenceErrorReason.UNAUTHENTICATED,
                PresenceErrorReason.PERMISSION_DENIED,
                PresenceErrorReason.FAILED_PRECONDITION,
                PresenceErrorReason.NOT_FOUND
            )
        }
        val ffe = error as? FirebaseFunctionsException ?: return false
        return ffe.code in setOf(
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.UNIMPLEMENTED
        )
    }

    internal fun isPresenceCallableNotDeployed(error: Throwable): Boolean {
        val ffe = error as? FirebaseFunctionsException ?: return false
        return ffe.code == FirebaseFunctionsException.Code.NOT_FOUND ||
            ffe.code == FirebaseFunctionsException.Code.UNIMPLEMENTED
    }

    internal fun isHardFcmRegistrationFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val msg = current.message.orEmpty().uppercase(Locale.US)
            if (msg.contains("TOO_MANY_REGISTRATIONS") ||
                msg.contains("MISSING_INSTANCEID_SERVICE") ||
                msg.contains("INVALID_SENDER")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    suspend fun revertLocalOnlineStatusToOffline() {
        _serverConfirmedWorkingSession.value = null
        stopRealtimeOrdersListener()
        captainDao.updateOnlineStatus(false)
        orderDao.deleteAllPendingOrders()
        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
    }

    private val authBackend: CaptainAuthBackendPort by lazy {
        authBackendOverride ?: object : CaptainAuthBackendPort {
            private fun com.google.firebase.auth.FirebaseUser.toSnapshot(): AuthUserSnapshot =
                AuthUserSnapshot(
                    uid = uid,
                    email = email.orEmpty(),
                    isEmailVerified = isEmailVerified,
                    displayName = displayName.orEmpty()
                )

            override fun currentUser(): AuthUserSnapshot? = firebaseAuth.currentUser?.toSnapshot()

            override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
                val res = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
                val u = res.user ?: throw IllegalStateException("تعذر إنشاء حساب الكابتن في Firebase Auth")
                return u.toSnapshot()
            }

            override suspend fun sendEmailVerification() {
                val u = firebaseAuth.currentUser
                    ?: throw IllegalStateException("تعذر العثور على جلسة مستخدم نشطة لإرسال رابط التأكيد.")
                u.sendEmailVerification().await()
            }

            override suspend fun reloadCurrentUser(): AuthUserSnapshot? {
                val u = firebaseAuth.currentUser ?: return null
                u.reload().await()
                val refreshed = firebaseAuth.currentUser ?: u
                // reload() updates isEmailVerified locally, but Firestore keeps using the cached ID
                // token (email_verified=false) for up to an hour. Force a new token once verified so
                // the rules accept the follow-up emailVerified / isOnline write.
                if (refreshed.isEmailVerified) {
                    runCatching { refreshed.getIdToken(true).await() }
                }
                return refreshed.toSnapshot()
            }

            override suspend fun updateDisplayName(displayName: String) {
                val u = firebaseAuth.currentUser ?: return
                val updates = UserProfileChangeRequest.Builder()
                    .setDisplayName(displayName)
                    .build()
                u.updateProfile(updates).await()
            }

            override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
                val res = firebaseAuth.signInWithEmailAndPassword(email, password).await()
                val u = res.user ?: throw IllegalStateException("فشل استعادة بيانات المستخدم من خادم Firebase.")
                return u.toSnapshot()
            }

            override fun signOut() {
                firebaseAuth.signOut()
            }

            override suspend fun deleteCurrentUser() {
                firebaseAuth.currentUser?.delete()?.await()
            }

            override suspend fun refreshIdToken() {
                firebaseAuth.currentUser?.getIdToken(true)?.await()
            }

            override suspend fun sendPasswordResetEmail(email: String) {
                firebaseAuth.sendPasswordResetEmail(email).await()
            }

            override suspend fun createCaptainDoc(uid: String, data: Map<String, Any>) {
                require(uid.isNotBlank()) { "Captain UID must not be blank" }
                val currentUid = firebaseAuth.currentUser?.uid
                require(!currentUid.isNullOrBlank() && currentUid == uid) {
                    "Authenticated UID mismatch while creating captains/$uid"
                }
                require(data["accountType"]?.toString()?.trim()?.lowercase() == CAPTAIN_ACCOUNT_TYPE) {
                    "New captain document in captains/$uid MUST define accountType = '$CAPTAIN_ACCOUNT_TYPE'"
                }
                require(data["role"]?.toString()?.trim()?.lowercase() == CAPTAIN_ROLE) {
                    "New captain document in captains/$uid MUST define role = '$CAPTAIN_ROLE'"
                }
                firestore.collection("captains").document(uid).set(data).await()
            }

            override suspend fun getCaptainDoc(uid: String): Map<String, Any?>? {
                val docRef = firestore.collection("captains").document(uid)
                val snap = withTimeout(20_000L) { docRef.get(Source.SERVER).await() }
                return if (snap.exists()) (snap.data ?: emptyMap()) else null
            }

            override suspend fun getCaptainDocFromServer(uid: String): Map<String, Any?>? {
                val docRef = firestore.collection("captains").document(uid)
                val snap = withTimeout(20_000L) { docRef.get(Source.SERVER).await() }
                return if (snap.exists()) (snap.data ?: emptyMap()) else null
            }

            override suspend fun updateCaptainDoc(uid: String, data: Map<String, Any>) {
                firestore.collection("captains").document(uid).update(data).await()
            }

            override suspend fun queryCaptainEmailByPhoneVariants(
                variants: List<String>,
                numericVariants: List<Long>,
                normalizedLocal: String
            ): String? {
                val fieldNames = listOf("phone", "phoneNumber", "phone_number", "mobile", "driverPhone")
                var permissionDenied = false

                if (variants.isNotEmpty()) {
                    for (field in fieldNames) {
                        try {
                            val querySnap = firestore.collection("captains")
                                .whereIn(field, variants)
                                .limit(1)
                                .get()
                                .await()

                            if (!querySnap.isEmpty) {
                                val doc = querySnap.documents[0]
                                if (isEligibleCaptainIdentity(doc.safeString("accountType"), doc.safeString("role"))) {
                                    val em = doc.safeString("email")
                                    if (em.isNotBlank()) return em
                                }
                            }
                        } catch (e: Exception) {
                            val fsEx = e as? FirebaseFirestoreException
                            if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                permissionDenied = true
                                break
                            }
                        }
                    }
                }

                if (!permissionDenied && numericVariants.isNotEmpty()) {
                    for (field in listOf("phone", "phoneNumber")) {
                        try {
                            val numSnap = firestore.collection("captains")
                                .whereIn(field, numericVariants)
                                .limit(1)
                                .get()
                                .await()
                            if (!numSnap.isEmpty) {
                                val doc = numSnap.documents[0]
                                if (isEligibleCaptainIdentity(doc.safeString("accountType"), doc.safeString("role"))) {
                                    val em = doc.safeString("email")
                                    if (em.isNotBlank()) return em
                                }
                            }
                        } catch (e: Exception) {
                            val fsEx = e as? FirebaseFirestoreException
                            if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                permissionDenied = true
                                break
                            }
                        }
                    }
                }

                if (!permissionDenied) {
                    try {
                        val scanSnap = firestore.collection("captains").limit(100).get().await()
                        for (doc in scanSnap.documents) {
                            if (!isEligibleCaptainIdentity(doc.safeString("accountType"), doc.safeString("role"))) continue
                            val p = doc.safeString("phone", "phoneNumber", "phone_number", "mobile", "driverPhone")
                                .ifBlank { doc.get("phone")?.toString() ?: doc.get("phoneNumber")?.toString() ?: "" }
                            if (isSamePhone(p, normalizedLocal)) {
                                val em = doc.safeString("email")
                                if (em.isNotBlank()) return em
                            }
                        }
                    } catch (_: Exception) {}
                }

                return null
            }
        }
    }

    private val cloudMetadataPort: CaptainCloudMetadataPort by lazy {
        cloudMetadataPortOverride ?: object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String? =
                if (authBackendOverride != null) {
                    runCatching { authBackendOverride.currentUser()?.uid }.getOrNull()
                } else {
                    runCatching { firebaseAuth.currentUser?.uid }.getOrNull()
                }

            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {
                val payload = HashMap<String, Any>(fields).apply {
                    put("updatedAt", FieldValue.serverTimestamp())
                    (get("profileImagePath") as? String)?.let { put("personalImage", it) }
                    (get("idFrontImagePath") as? String)?.let { put("idCardFront", it) }
                    (get("idBackImagePath") as? String)?.let { put("idCardBack", it) }
                }
                // Strictly update existing captains/{uid} document ONLY; NEVER auto-create missing captain docs
                firestore.collection("captains").document(uid).update(payload).await()
            }

            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? {
                val snap = firestore.collection("captains").document(uid).get().await()
                if (!snap.exists()) return null
                return mapOf(
                    "profileImagePath" to snap.safeString("profileImagePath", "personalImage"),
                    "idFrontImagePath" to snap.safeString("idFrontImagePath", "idCardFront"),
                    "idBackImagePath" to snap.safeString("idBackImagePath", "idCardBack"),
                    "licenseImagePath" to snap.safeString("licenseImagePath"),
                    "profileImageUrl" to snap.safeString("profileImageUrl", "photoUrl", "avatarUri"),
                    "idCardImageUrl" to snap.safeString("idCardImageUrl", "nationalIdUrl"),
                    "licenseImageUrl" to snap.safeString("licenseImageUrl", "licenseUrl")
                )
            }

            override suspend fun submitOrderReceiptDetails(
                orderDocId: String,
                customerName: String,
                customerPhone: String,
                receiptImagePath: String,
                notes: String
            ): Result<String> {
                return cloudFunctionsOrderClient.submitCustomerDeliveryDetails(
                    orderDocId = orderDocId,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    receiptImagePath = receiptImagePath,
                    notes = notes
                )
            }
        }
    }

    /**
     * Merchant account documents (merchants/, users/, and the retired restaurants/) are readable
     * only by the merchant and admins, so a captain cannot look them up: every attempt was a
     * PERMISSION_DENIED round trip. The server copies the store location onto the order itself
     * (storeLocationUrl / pickup coordinates), which is what callers fall back to.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun lookupMerchantLocationFromFirestore(
        merchantId: String,
        storeName: String,
        forceRefresh: Boolean
    ): Triple<String, Double?, Double?> = Triple("", null, null)

    suspend fun resolveMerchantLocationForOrder(
        order: OrderEntity,
        forceRefresh: Boolean = false
    ): OrderEntity = withContext(Dispatchers.IO) {
        var bestUrl = order.effectiveStoreLocationUrl
        var bestMerchantId = order.merchantId.trim()
        var bestLat = order.restaurantLatitude
        var bestLng = order.restaurantLongitude

        if (forceRefresh && order.firestoreDocId.isNotBlank()) {
            try {
                val orderSnap = firestore.collection("orders").document(order.firestoreDocId).get().await()
                if (orderSnap.exists()) {
                    val freshOrderUrl = extractMerchantLocationUrlFromFieldsMap(orderSnap.data)
                    if (freshOrderUrl.isNotBlank()) {
                        bestUrl = freshOrderUrl
                    }
                    val freshMerchantId = orderSnap.safeString(
                        "merchantId", "merchant_id", "restaurantId", "restaurant_id",
                        "storeId", "store_id", "vendorId", "vendor_id", "ownerId",
                        "owner_id", "merchantUid", "restaurantUid"
                    )
                    if (freshMerchantId.isNotBlank()) {
                        bestMerchantId = freshMerchantId
                    }
                }
            } catch (e: Exception) {
                Log.d("CaptainRepository", "[MerchantLocation] order refresh notice: ${e.message}")
            }
        }

        if (bestUrl.isBlank() || forceRefresh) {
            val (accountUrl, accountLat, accountLng) = lookupMerchantLocationFromFirestore(
                merchantId = bestMerchantId,
                storeName = order.storeName,
                forceRefresh = forceRefresh
            )
            if (accountUrl.isNotBlank()) {
                bestUrl = accountUrl
            }
            if (bestLat == null && accountLat != null) bestLat = accountLat
            if (bestLng == null && accountLng != null) bestLng = accountLng
        }

        if (bestUrl != order.storeLocationUrl ||
            bestMerchantId != order.merchantId ||
            bestLat != order.restaurantLatitude ||
            bestLng != order.restaurantLongitude
        ) {
            orderDao.updateMerchantLocationInfo(
                orderId = order.id,
                locationUrl = bestUrl,
                merchantId = bestMerchantId,
                lat = bestLat,
                lng = bestLng
            )
            order.copy(
                merchantId = bestMerchantId,
                storeLocationUrl = bestUrl,
                restaurantLatitude = bestLat,
                restaurantLongitude = bestLng
            )
        } else {
            order
        }
    }

    private val ordersListenerRegistrations = mutableListOf<ListenerRegistration>()
    private var shiftSyncJob: Job? = null
    private var shiftSyncUid: String? = null
    private val shiftSyncIntervalMs = 30L * 60L * 1000L
    private var captainScheduleDocListenerRegistration: ListenerRegistration? = null
    private var captainScheduleSubcollectionListenerRegistration: ListenerRegistration? = null
    private var captainScheduleQueryListenerRegistration: ListenerRegistration? = null
    private var captainScheduleUidQueryListenerRegistration: ListenerRegistration? = null
    private var captainScheduleDriverQueryListenerRegistration: ListenerRegistration? = null
    private var captainSubcollectionShiftListenerRegistration: ListenerRegistration? = null
    private var shiftListenerRegistration: ListenerRegistration? = null
    private var captainDocShiftListenerRegistration: ListenerRegistration? = null

    private val _isShiftSynced = MutableStateFlow(false)
    val isShiftSynced: StateFlow<Boolean> = _isShiftSynced.asStateFlow()

    private val _isShiftNetworkConnected = MutableStateFlow(true)
    val isShiftNetworkConnected: StateFlow<Boolean> = _isShiftNetworkConnected.asStateFlow()

    val captainFlow: Flow<CaptainEntity?> = captainDao.getCaptainFlow()
    val allOrdersFlow: Flow<List<OrderEntity>> = orderDao.getAllOrdersFlow()
    val activeOrderFlow: Flow<OrderEntity?> = orderDao.getActiveOrderFlow()
    val availableOrdersFlow: Flow<List<OrderEntity>> = orderDao.getAvailablePendingOrdersFlow()
    val pendingOrderFlow: Flow<OrderEntity?> = orderDao.getPendingIncomingOrderFlow()
    val completedOrdersFlow: Flow<List<OrderEntity>> = orderDao.getCompletedOrdersFlow()
    val completedCountFlow: Flow<Int> = orderDao.getCompletedCountFlow()
    val totalEarningsFlow: Flow<Double?> = orderDao.getTotalEarningsFlow()
    val shiftsFlow: Flow<List<ShiftEntity>> = shiftDao?.getAllShiftsFlow() ?: emptyFlow()
    val dailyWalletDao: DailyWalletDao? = context?.let { AppDatabase.getDatabase(it).dailyWalletDao() }
    val dailyWalletsFlow: Flow<List<DailyWalletEntity>> = dailyWalletDao?.getAllDailyWalletsFlow() ?: emptyFlow()
    private val cloudFunctionsOrderClient: com.example.data.remote.CloudFunctionsOrderClient by lazy {
        cloudFunctionsClientOverride ?: com.example.data.remote.CloudFunctionsOrderClient(firebaseAuth)
    }

    init {
        if (!hasTestBackendOverride) {
            try {
                // 1. Only start listeners if already authenticated AND email is verified
                val existingUser = firebaseAuth.currentUser
                if (existingUser != null && existingUser.isEmailVerified) {
                    // Keep only the schedule listener active; order listeners start when working.
                    startRealtimeShiftListener()
                }
                // 2. Listen to Auth State changes and require verified Firebase user
                firebaseAuth.addAuthStateListener { auth ->
                    val user = auth.currentUser
                    if (user != null && user.isEmailVerified) {
                        startRealtimeShiftListener()
                    } else {
                        stopRealtimeOrdersListener()
                        stopRealtimeShiftListener()
                    }
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not initialize Firebase Auth listeners: ${e.message}")
            }
        }
    }

    suspend fun clearPendingOrdersLocally() {
        orderDao.deleteAllPendingOrders()
        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
    }

    suspend fun syncFcmToken() {
        // Best-effort: never throw if Firebase is not initialized (no google-services.json,
        // or a live approval update arriving before startup finished).
        if (hasTestBackendOverride && authBackendOverride == null) return
        val uid = resolveCurrentCaptainUid() ?: return
        fcmTokenSyncMutex.withLock {
            val savedToken = context?.let { CaptainNotificationManager.getSavedFcmToken(it) }?.takeIf { it.isNotBlank() }

            // Avoid redundant FCM token requests if we already synced this exact uid + token in this session.
            if (!hasTestBackendOverride && savedToken != null && lastSyncedFcmUid == uid && lastSyncedFcmToken == savedToken) {
                return@withLock
            }

            val resolvedToken: String? = if (hasTestBackendOverride) {
                savedToken
            } else if (fcmTokenHardFailureDetected) {
                // Do not keep hammering Play Services after a hard TOO_MANY_REGISTRATIONS failure.
                savedToken
            } else {
                try {
                    FirebaseMessaging.getInstance().token.await()?.takeIf { it.isNotBlank() } ?: savedToken
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (isHardFcmRegistrationFailure(e)) {
                        fcmTokenHardFailureDetected = true
                        Log.w("CaptainRepository", "FCM token retrieval hit hard Play Services limit (${e.message}); suppressing further retries this session")
                    } else {
                        Log.w("CaptainRepository", "FCM token retrieval failed: ${e.message}")
                    }
                    savedToken
                }
            }
            val token: String = resolvedToken ?: return@withLock

            if (!hasTestBackendOverride && lastSyncedFcmUid == uid && lastSyncedFcmToken == token) {
                return@withLock
            }

            context?.let { CaptainNotificationManager.saveFcmToken(it, token) }
            val payload = mapOf<String, Any>(
                "fcmToken" to token,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            runCatching {
                authBackend.updateCaptainDoc(uid, payload)
                lastSyncedFcmUid = uid
                lastSyncedFcmToken = token
            }
        }
    }

    private suspend fun saveShiftsAndScheduleAlarms(uid: String, shifts: List<ShiftEntity>) {
        shiftDao?.atomicReplaceCaptainShifts(uid, shifts)
        context?.let { ShiftNotificationScheduler.scheduleShiftNotifications(it, shifts, uid) }
    }

    /**
     * Checks whether the captain is eligible to be assigned or receive new/pending orders.
     * Enforces:
     * 1. Captain exists and local isOnline == true.
     * 2. Account is approved (never pending/suspended/blocked/rejected).
     * 3. Current Cairo time falls inside an active scheduled shift.
     * 4. Server has explicitly confirmed workStatus == "working" with matching readyShiftKey & workingShiftKey.
     */
    internal suspend fun isCaptainEligibleForNewOrders(captain: CaptainEntity?): Boolean {
        if (captain == null || !captain.isOnline) {
            return false
        }
        if (_accountApprovalState.value != null && !isCurrentSessionApproved) {
            return false
        }
        val normalizedStatus = captain.status.trim().lowercase()
        if (normalizedStatus in listOf("banned", "suspended", "disabled", "blocked", "pending", "inactive", "rejected")) {
            return false
        }
        val currentShifts = getShifts()
        val activeShift = ShiftManager.calculateShiftStatus(currentShifts) as? ShiftStatus.Active
            ?: return false
        val expectedShiftKey = buildShiftInstanceKey(activeShift.shift)
        val confirmed = _serverConfirmedWorkingSession.value ?: return false
        val resolvedUid = resolveCurrentCaptainUid()?.takeIf { it.isNotBlank() } ?: captain.uid
        return confirmed.isConfirmedWorking &&
            confirmed.uid == resolvedUid &&
            confirmed.readyShiftKey == expectedShiftKey &&
            confirmed.workingShiftKey == expectedShiftKey
    }

    private fun DocumentSnapshot.toParsedOrderSnapshot(myUid: String, myPhone: String): ParsedOrderSnapshot? {
        val order = toOrderEntity() ?: return null
        val docCaptainId = safeString(
            "captainId", "captain_id", "driverId", "driver_id",
            "assignedCaptainId", "assignedDriverId", "captainUid",
            "driverUid", "assignedCaptainPhone", "driverPhone", "captain_phone"
        )
        val offeredCaptainId = safeString("offeredCaptainId", "offered_captain_id")
        val isExcluded = isExcludedCaptain(myUid)
        val hasMatchingSecondaryUid = myUid.isNotBlank() &&
            safeString("captainUid", "driverUid", "offeredCaptainId") == myUid
        val hasMatchingPhone = myPhone.isNotBlank() && (
            isSamePhone(docCaptainId, myPhone) ||
                isSamePhone(safeString("driverPhone", "assignedCaptainPhone"), myPhone)
            )
        return ParsedOrderSnapshot(
            docId = id,
            order = order,
            docCaptainId = docCaptainId,
            offeredCaptainId = offeredCaptainId,
            isExcluded = isExcluded,
            hasMatchingSecondaryUid = hasMatchingSecondaryUid,
            hasMatchingPhone = hasMatchingPhone
        )
    }

    internal fun scheduleDailyWalletReconciliation() {
        synchronized(walletReconcileLock) {
            walletReconcileJob?.cancel()
            val delayMs = walletReconcileDebounceMs
            walletReconcileJob = repositoryScope.launch {
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                reconcileDailyWallets()
                onWalletReconciledForTesting?.invoke()
            }
        }
    }

    internal suspend fun applyOrderSnapshotChanges(
        listenerKey: String,
        authUid: String,
        changedOrders: List<ParsedOrderSnapshot>,
        removedDocIds: List<String> = emptyList()
    ) = orderSnapshotMutex.withLock {
        if (changedOrders.isEmpty() && removedDocIds.isEmpty()) return@withLock

        val currentCaptain = captainDao.getCaptain()
        val myPhone = currentCaptain?.phoneNumber ?: ""
        val myUid = authUid.ifBlank { currentCaptain?.uid.orEmpty() }
        val eligibleForNewOrders = isCaptainEligibleForNewOrders(currentCaptain)

        if (!eligibleForNewOrders) {
            orderDao.deleteAllPendingOrders()
            context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
        }

        // Update tracked matched doc IDs for this listener
        val stillMatchedElsewhere = mutableSetOf<String>()
        synchronized(ordersListenerLock) {
            val bucket = listenerMatchedDocIds.getOrPut(listenerKey) { mutableSetOf() }
            for (item in changedOrders) {
                if (item.docId.isNotBlank()) bucket.add(item.docId)
            }
            for (removedId in removedDocIds) {
                bucket.remove(removedId)
                val inOtherListener = listenerMatchedDocIds.any { (key, ids) ->
                    key != listenerKey && ids.contains(removedId)
                }
                if (inOtherListener) {
                    stillMatchedElsewhere.add(removedId)
                }
            }
        }

        val allDocIdsToLookup = buildList(changedOrders.size + removedDocIds.size) {
            for (item in changedOrders) {
                if (item.order.firestoreDocId.isNotBlank()) add(item.order.firestoreDocId)
            }
            for (removedId in removedDocIds) {
                if (removedId.isNotBlank()) add(removedId)
            }
        }.distinct()

        val existingByDocId = if (allDocIdsToLookup.isNotEmpty()) {
            orderDao.findByFirestoreDocIds(allDocIdsToLookup).associateBy { it.firestoreDocId }
        } else {
            emptyMap()
        }

        val idsToDelete = mutableListOf<Long>()
        val ordersToUpsert = mutableListOf<OrderEntity>()
        val pendingNotifications = mutableListOf<Pair<OrderEntity, Boolean>>() // order + bypassDedupe
        val bellsToStop = mutableListOf<String>()
        var shouldStopAllBells = false
        var shouldReconcileWallets = false

        // 1. Handle REMOVED documents safely without ever deleting historical terminal orders
        // or orders still matched by another active listener.
        for (removedDocId in removedDocIds) {
            if (removedDocId.isBlank() || removedDocId in stillMatchedElsewhere) continue
            val localExisting = existingByDocId[removedDocId] ?: continue
            val isTerminalHistorical = localExisting.status == OrderStatus.DELIVERED ||
                localExisting.status == OrderStatus.CANCELLED ||
                localExisting.status == OrderStatus.EXPIRED
            if (isTerminalHistorical) continue

            if (listenerKey == LISTENER_KEY_PENDING_OFFER) {
                // Pending offer expired, cancelled, or claimed by another captain.
                // Never delete if locally already accepted/active.
                if (localExisting.status == OrderStatus.PENDING) {
                    idsToDelete.add(localExisting.id)
                    bellsToStop.add(localExisting.firestoreDocId.ifBlank { removedDocId })
                }
            } else {
                // Assignment listener removed this non-terminal order and no other listener owns it
                // (e.g. reassigned/transferred to another captain or deleted on server).
                idsToDelete.add(localExisting.id)
                bellsToStop.add(localExisting.firestoreDocId.ifBlank { removedDocId })
            }
        }

        val preservedActiveStatuses = setOf(
            OrderStatus.AT_STORE,
            OrderStatus.PICKED_UP,
            OrderStatus.ORDER_DATA_ENTERED,
            OrderStatus.AWAITING_MERCHANT_CONFIRMATION,
            OrderStatus.MERCHANT_REJECTED,
            OrderStatus.EN_ROUTE
        )

        // 2. Process ADDED / MODIFIED documents
        for (item in changedOrders) {
            val order = item.order
            val docCaptainId = item.docCaptainId
            val offeredCaptainId = item.offeredCaptainId
            val isExcluded = item.isExcluded
            val hasNoCaptain = isPlaceholderOrUnassigned(docCaptainId)
            val isAssignedToMe = !hasNoCaptain && (
                (myUid.isNotBlank() && (docCaptainId == myUid || item.hasMatchingSecondaryUid)) ||
                    (myPhone.isNotBlank() && (isSamePhone(docCaptainId, myPhone) || item.hasMatchingPhone))
                )
            val offeredToOtherCaptain = offeredCaptainId.isNotBlank() &&
                offeredCaptainId != myUid &&
                !isAssignedToMe
            val isAvailable = (order.status == OrderStatus.PENDING) &&
                hasNoCaptain &&
                !isExcluded &&
                !offeredToOtherCaptain
            val isAcceptedByOther = !isAvailable && !hasNoCaptain && !isAssignedToMe
            val existingBefore = existingByDocId[order.firestoreDocId]

            if (isAcceptedByOther || isExcluded || offeredToOtherCaptain) {
                if (existingBefore != null) {
                    if (existingBefore.status == OrderStatus.DELIVERED) {
                        shouldReconcileWallets = true
                    }
                    idsToDelete.add(existingBefore.id)
                } else {
                    idsToDelete.add(order.id)
                }
                bellsToStop.add(order.firestoreDocId.ifBlank { item.docId })
            } else if (order.status == OrderStatus.PENDING && !eligibleForNewOrders) {
                if (existingBefore != null) {
                    idsToDelete.add(existingBefore.id)
                } else {
                    idsToDelete.add(order.id)
                }
                shouldStopAllBells = true
            } else {
                val resolvedExpiresAt = when {
                    order.offerExpiresAt > 0L -> order.offerExpiresAt
                    existingBefore != null && existingBefore.offerExpiresAt > 0L -> existingBefore.offerExpiresAt
                    order.status == OrderStatus.PENDING -> System.currentTimeMillis() + 60_000L
                    else -> 0L
                }
                val finalOrderBase = if (isAvailable || (order.status == OrderStatus.PENDING && offeredCaptainId == myUid)) {
                    order.copy(status = OrderStatus.PENDING, offerExpiresAt = resolvedExpiresAt)
                } else {
                    order.copy(offerExpiresAt = resolvedExpiresAt)
                }
                val finalOrder = if (
                    finalOrderBase.status == OrderStatus.PENDING &&
                    isAssignedToMe &&
                    existingBefore != null &&
                    existingBefore.status in preservedActiveStatuses
                ) {
                    if (finalOrderBase.merchantConfirmed || existingBefore.merchantConfirmed) {
                        finalOrderBase.copy(status = OrderStatus.EN_ROUTE, merchantConfirmed = true)
                    } else {
                        finalOrderBase.copy(status = existingBefore.status)
                    }
                } else {
                    finalOrderBase
                }
                val enrichedOrder = if (finalOrder.storeLocationUrl.isBlank() && !existingBefore?.storeLocationUrl.isNullOrBlank()) {
                    finalOrder.copy(storeLocationUrl = existingBefore!!.storeLocationUrl)
                } else {
                    finalOrder
                }

                ordersToUpsert.add(enrichedOrder)

                if (enrichedOrder.status == OrderStatus.DELIVERED ||
                    existingBefore?.status == OrderStatus.DELIVERED
                ) {
                    shouldReconcileWallets = true
                }

                if (enrichedOrder.status == OrderStatus.PENDING &&
                    eligibleForNewOrders &&
                    (existingBefore == null || (offeredCaptainId == myUid && enrichedOrder.offerExpiresAt > existingBefore.offerExpiresAt))
                ) {
                    val bypassDedupe = existingBefore != null && offeredCaptainId == myUid
                    pendingNotifications.add(enrichedOrder to bypassDedupe)
                } else if (enrichedOrder.status != OrderStatus.PENDING) {
                    bellsToStop.add(enrichedOrder.firestoreDocId.ifBlank { item.docId })
                }
            }
        }

        // 3. Apply batched Room mutations (single bulk transaction for upserts + bulk delete)
        if (idsToDelete.isNotEmpty()) {
            orderDao.deleteOrdersByIds(idsToDelete.distinct())
        }
        val savedIdsByDocId = if (ordersToUpsert.isNotEmpty()) {
            orderDao.upsertAllByFirestoreIds(ordersToUpsert)
        } else {
            emptyMap()
        }

        // 4. Side effects (notifications & debounced wallet reconciliation)
        context?.let { ctx ->
            if (shouldStopAllBells) {
                CaptainNotificationManager.stopIncomingOrderBell(ctx)
            }
            for (bellKey in bellsToStop.distinct()) {
                CaptainNotificationManager.stopIncomingOrderBell(ctx, bellKey)
            }
            for ((pendingOrder, bypassDedupe) in pendingNotifications) {
                val savedOrderId = savedIdsByDocId[pendingOrder.firestoreDocId] ?: pendingOrder.id
                CaptainNotificationManager.showNewOrderNotification(
                    context = ctx,
                    orderKey = pendingOrder.firestoreDocId.ifBlank { savedOrderId.toString() },
                    storeName = pendingOrder.storeName,
                    pickupAddress = pendingOrder.pickupAddress,
                    deliveryAddress = pendingOrder.deliveryAddress,
                    deliveryFee = pendingOrder.deliveryFee,
                    orderCode = pendingOrder.cleanOrderCode,
                    storeLocationUrl = pendingOrder.effectiveStoreLocationUrl,
                    bypassDedupe = bypassDedupe,
                    orderId = savedOrderId,
                    offerExpiresAtMs = pendingOrder.offerExpiresAt,
                    distanceKm = pendingOrder.distanceKm,
                    startContinuousBell = true
                )
            }
        }

        if (shouldReconcileWallets) {
            scheduleDailyWalletReconciliation()
        }
    }

    fun startRealtimeOrdersListener(consumerId: String = DEFAULT_ORDERS_LISTENER_CONSUMER) {
        if (_accountApprovalState.value != null && !isCurrentSessionApproved) {
            stopRealtimeOrdersListener()
            Log.d("CaptainRepository", "[FirestoreOrdersListener] Blocked: captain account is not approved")
            return
        }
        val authUid = if (hasTestBackendOverride || ordersListenerAttachmentOverride != null) {
            resolveCurrentCaptainUid()
        } else {
            runCatching { firebaseAuth.currentUser?.uid }.getOrNull()
        }
        if (authUid.isNullOrBlank()) {
            stopRealtimeOrdersListener()
            Log.d("CaptainRepository", "[FirestoreOrdersListener] Waiting for captain authentication before starting orders listener")
            return
        }
        val confirmed = _serverConfirmedWorkingSession.value
        if (confirmed == null || !confirmed.isConfirmedWorking || confirmed.uid != authUid) {
            stopRealtimeOrdersListener()
            Log.d("CaptainRepository", "[FirestoreOrdersListener] Blocked: server has not confirmed working shift state for captain $authUid")
            return
        }
        val confirmedShiftKey = confirmed.workingShiftKey
        synchronized(ordersListenerLock) {
            ordersListenerConsumers.add(consumerId)
            if (ordersListenerRegistrations.isNotEmpty() &&
                activeOrdersListenerUid == authUid &&
                activeOrdersListenerShiftKey == confirmedShiftKey
            ) {
                Log.d("CaptainRepository", "[FirestoreOrdersListener] Reusing active orders listeners for $authUid ($confirmedShiftKey), consumers=${ordersListenerConsumers.size}")
                return
            }
            ordersListenerRegistrations.forEach { runCatching { it.remove() } }
            ordersListenerRegistrations.clear()
            listenerMatchedDocIds.clear()
            activeOrdersListenerUid = null
            activeOrdersListenerShiftKey = null

            val overrideFactory = ordersListenerAttachmentOverride
            if (overrideFactory != null) {
                val attached = overrideFactory(authUid, confirmedShiftKey)
                ordersListenerRegistrations.addAll(attached)
                if (attached.isNotEmpty()) {
                    activeOrdersListenerUid = authUid
                    activeOrdersListenerShiftKey = confirmedShiftKey
                }
                return
            }
            if (hasTestBackendOverride) return
        }

        try {
            Log.i("CaptainRepository", "[FirestoreOrdersListener] Registering 5 scoped listeners (documentChanges) on 'orders' for working captain $authUid ($confirmedShiftKey)...")

            fun dispatchSnapshotChanges(
                listenerKey: String,
                changes: List<DocumentChange>
            ) {
                if (changes.isEmpty()) return
                repositoryScope.launch {
                    try {
                        val currentCaptain = captainDao.getCaptain()
                        val myPhone = currentCaptain?.phoneNumber ?: ""
                        val myUid = authUid.ifBlank { currentCaptain?.uid.orEmpty() }

                        val changedSnapshots = mutableListOf<ParsedOrderSnapshot>()
                        val removedIds = mutableListOf<String>()
                        for (change in changes) {
                            when (change.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    try {
                                        change.document.toParsedOrderSnapshot(myUid, myPhone)?.let {
                                            changedSnapshots.add(it)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CaptainRepository", "[FirestoreOrdersListener] Error parsing changed order doc ${change.document.id}", e)
                                    }
                                }
                                DocumentChange.Type.REMOVED -> {
                                    removedIds.add(change.document.id)
                                }
                            }
                        }
                        applyOrderSnapshotChanges(
                            listenerKey = listenerKey,
                            authUid = myUid,
                            changedOrders = changedSnapshots,
                            removedDocIds = removedIds
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.e("CaptainRepository", "[FirestoreOrdersListener] Error applying changes for $listenerKey", e)
                    }
                }
            }

            val newRegistrations = mutableListOf<ListenerRegistration>()
            // 1. Available pending orders scoped to this captain (matches firestore.rules isAvailablePendingOrder)
            newRegistrations += firestore.collection("orders")
                .whereEqualTo("status", "pending")
                .whereEqualTo("offeredCaptainId", authUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("CaptainRepository", "[FirestoreOrdersListener] pending query notice: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        dispatchSnapshotChanges(LISTENER_KEY_PENDING_OFFER, snapshot.documentChanges)
                    }
                }

            // 2-5. Captain's own assigned orders across all legacy ownership fields (no duplicate offeredCaptainId listener)
            for (field in ORDER_ASSIGNMENT_QUERY_FIELDS) {
                newRegistrations += firestore.collection("orders")
                    .whereEqualTo(field, authUid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w("CaptainRepository", "[FirestoreOrdersListener] $field query notice: ${error.message}")
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            dispatchSnapshotChanges(field, snapshot.documentChanges)
                        }
                    }
            }
            synchronized(ordersListenerLock) {
                val stillConfirmed = _serverConfirmedWorkingSession.value
                if (stillConfirmed != null &&
                    stillConfirmed.isConfirmedWorking &&
                    stillConfirmed.uid == authUid &&
                    stillConfirmed.workingShiftKey == confirmedShiftKey &&
                    ordersListenerConsumers.isNotEmpty()
                ) {
                    ordersListenerRegistrations.forEach { runCatching { it.remove() } }
                    ordersListenerRegistrations.clear()
                    ordersListenerRegistrations.addAll(newRegistrations)
                    activeOrdersListenerUid = authUid
                    activeOrdersListenerShiftKey = confirmedShiftKey
                } else {
                    newRegistrations.forEach { runCatching { it.remove() } }
                }
            }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[FirestoreOrdersListener] Error starting Firestore listener: ${e.message}")
        }
    }

    /**
     * Releases a specific consumer's hold on the shared realtime orders listeners.
     * Listeners remain active if another component (e.g., ViewModel or LocationService) still needs them.
     */
    fun releaseOrdersListener(consumerId: String) {
        synchronized(ordersListenerLock) {
            ordersListenerConsumers.remove(consumerId)
            if (ordersListenerConsumers.isEmpty()) {
                ordersListenerRegistrations.forEach { runCatching { it.remove() } }
                ordersListenerRegistrations.clear()
                listenerMatchedDocIds.clear()
                activeOrdersListenerUid = null
                activeOrdersListenerShiftKey = null
            }
        }
    }

    /**
     * Dedicated query for pending offers addressed specifically to this captain UID.
     * Logs query execution and all matched documents for verification.
     */
    suspend fun queryPendingOrdersFromFirestore(): Result<List<OrderEntity>> {
        return try {
            val currentCaptain = captainDao.getCaptain()
            if (!isCaptainEligibleForNewOrders(currentCaptain)) {
                Log.i("CaptainRepository", "[FirestoreQuery] Captain is offline or outside active shift; skipping pending order assignment and clearing pending cache.")
                orderDao.deleteAllPendingOrders()
                return Result.success(emptyList())
            }

            val myUid = currentCaptain?.uid?.takeIf { it.isNotBlank() }
                ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull().orEmpty()
            if (myUid.isBlank()) {
                return Result.success(emptyList())
            }
            Log.i("CaptainRepository", "[FirestoreQuery] Executing scoped query on 'orders' where status == 'pending' and offeredCaptainId equals captain...")
            val querySnapshot = firestore.collection("orders")
                .whereEqualTo("status", "pending")
                .whereEqualTo("offeredCaptainId", myUid)
                .get()
                .await()

            val docs = querySnapshot.documents
            Log.i("CaptainRepository", "[FirestoreQuery] Query 'status == pending AND offeredCaptainId == current captain' returned ${docs.size} documents (source=${if (querySnapshot.metadata.isFromCache) "cache" else "server"})")

            val pendingList = mutableListOf<OrderEntity>()
            for (doc in docs) {
                val order = doc.toOrderEntity()
                if (order != null) {
                    val finalOrder = order.copy(status = OrderStatus.PENDING)
                    pendingList.add(finalOrder)
                }
            }
            if (pendingList.isNotEmpty()) {
                orderDao.upsertAllByFirestoreIds(pendingList)
            }
            Result.success(pendingList)
        } catch (e: Exception) {
            Log.e("CaptainRepository", "[FirestoreQuery] Query for status=='pending' failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * One-time sync of orders from Firestore, executing and logging private offeredCaptainId queries
     * as well as syncing captain's active orders into Room database.
     */
    suspend fun syncOrdersFromFirestore(): Result<Int> {
        return syncOrdersDetailedFromFirestore().map { it.pendingCount }
    }

    /**
     * One-time sync of orders from Firestore, executing parallel scoped queries (without duplicate
     * offeredCaptainId) and saving results in a single Room bulk transaction.
     */
    suspend fun syncOrdersDetailedFromFirestore(): Result<SyncOrdersSummary> {
        return try {
            val currentCaptain = captainDao.getCaptain()
            val myPhone = currentCaptain?.phoneNumber ?: ""
            val myUid = firebaseAuth.currentUser?.uid
                ?: currentCaptain?.uid.orEmpty()
            if (myUid.isBlank()) {
                return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً لمزامنة الطلبات"))
            }

            val eligibleForNewOrders = isCaptainEligibleForNewOrders(currentCaptain)
            if (!eligibleForNewOrders) {
                Log.i("CaptainRepository", "[FirestoreSync] Captain is offline or outside active shift; clearing local PENDING offers and syncing only active/archived captain orders.")
                orderDao.deleteAllPendingOrders()
                context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
            }

            Log.i("CaptainRepository", "[FirestoreSync] Syncing scoped orders in parallel for captain UID: $myUid (eligibleForNewOrders=$eligibleForNewOrders)...")
            val matchedDocs = linkedMapOf<String, DocumentSnapshot>()

            val queryResults: List<List<DocumentSnapshot>> = coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<DocumentSnapshot>>>()
                if (eligibleForNewOrders) {
                    jobs += async(Dispatchers.IO) {
                        try {
                            firestore.collection("orders")
                                .whereEqualTo("status", "pending")
                                .whereEqualTo("offeredCaptainId", myUid)
                                .get()
                                .await()
                                .documents
                        } catch (e: Exception) {
                            Log.w("CaptainRepository", "[FirestoreSync] scoped pending query notice: ${e.message}")
                            emptyList()
                        }
                    }
                }
                for (field in ORDER_ASSIGNMENT_QUERY_FIELDS) {
                    jobs += async(Dispatchers.IO) {
                        try {
                            firestore.collection("orders")
                                .whereEqualTo(field, myUid)
                                .get()
                                .await()
                                .documents
                        } catch (e: Exception) {
                            Log.w("CaptainRepository", "[FirestoreSync] $field == uid query notice: ${e.message}")
                            emptyList()
                        }
                    }
                }
                jobs.awaitAll()
            }

            for (docs in queryResults) {
                for (doc in docs) {
                    matchedDocs[doc.id] = doc
                }
            }

            val existingByDocId = if (matchedDocs.isNotEmpty()) {
                orderDao.findByFirestoreDocIds(matchedDocs.keys.toList()).associateBy { it.firestoreDocId }
            } else {
                emptyMap()
            }

            val preservedActiveStatuses = setOf(
                OrderStatus.AT_STORE,
                OrderStatus.PICKED_UP,
                OrderStatus.ORDER_DATA_ENTERED,
                OrderStatus.AWAITING_MERCHANT_CONFIRMATION,
                OrderStatus.MERCHANT_REJECTED,
                OrderStatus.EN_ROUTE
            )

            var count = 0
            var pendingCount = 0
            var activeCount = 0
            val idsToDelete = mutableListOf<Long>()
            val ordersToUpsert = mutableListOf<OrderEntity>()
            var shouldReconcileWallets = false

            for (doc in matchedDocs.values) {
                val parsed = doc.toParsedOrderSnapshot(myUid, myPhone) ?: continue
                val order = parsed.order
                val docCaptainId = parsed.docCaptainId
                val offeredCaptainId = parsed.offeredCaptainId
                val isExcluded = parsed.isExcluded

                val hasNoCaptain = isPlaceholderOrUnassigned(docCaptainId)
                val isAssignedToMe = !hasNoCaptain && (
                    docCaptainId == myUid ||
                        parsed.hasMatchingSecondaryUid ||
                        (myPhone.isNotBlank() && isSamePhone(docCaptainId, myPhone))
                    )
                val offeredToOtherCaptain = offeredCaptainId.isNotBlank() &&
                    offeredCaptainId != myUid &&
                    !isAssignedToMe

                val isAvailable = (order.status == OrderStatus.PENDING) &&
                    hasNoCaptain &&
                    !isExcluded &&
                    !offeredToOtherCaptain
                val isAcceptedByOther = !isAvailable && !hasNoCaptain && !isAssignedToMe
                val existingLocal = existingByDocId[order.firestoreDocId]

                if (isAcceptedByOther || isExcluded || offeredToOtherCaptain) {
                    idsToDelete.add(existingLocal?.id ?: order.id)
                    context?.let {
                        CaptainNotificationManager.stopIncomingOrderBell(
                            it,
                            order.firestoreDocId.ifBlank { doc.id }
                        )
                    }
                } else if (order.status == OrderStatus.PENDING && !eligibleForNewOrders) {
                    idsToDelete.add(existingLocal?.id ?: order.id)
                    context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
                } else {
                    val resolvedExpiresAt = when {
                        order.offerExpiresAt > 0L -> order.offerExpiresAt
                        existingLocal != null && existingLocal.offerExpiresAt > 0L -> existingLocal.offerExpiresAt
                        order.status == OrderStatus.PENDING -> System.currentTimeMillis() + 60_000L
                        else -> 0L
                    }
                    val resolvedOrder = if (
                        order.status == OrderStatus.PENDING &&
                        isAssignedToMe &&
                        existingLocal != null &&
                        existingLocal.status in preservedActiveStatuses
                    ) {
                        if (order.merchantConfirmed || existingLocal.merchantConfirmed) {
                            order.copy(status = OrderStatus.EN_ROUTE, merchantConfirmed = true)
                        } else {
                            order.copy(status = existingLocal.status)
                        }
                    } else {
                        order.copy(offerExpiresAt = resolvedExpiresAt)
                    }
                    val finalOrder = if (isAvailable || (resolvedOrder.status == OrderStatus.PENDING && (isAssignedToMe || offeredCaptainId == myUid))) {
                        pendingCount++
                        resolvedOrder.copy(status = OrderStatus.PENDING, offerExpiresAt = resolvedExpiresAt)
                    } else {
                        if (resolvedOrder.status != OrderStatus.DELIVERED && resolvedOrder.status != OrderStatus.CANCELLED && resolvedOrder.status != OrderStatus.EXPIRED) {
                            activeCount++
                        }
                        resolvedOrder
                    }
                    if (finalOrder.status == OrderStatus.DELIVERED || existingLocal?.status == OrderStatus.DELIVERED) {
                        shouldReconcileWallets = true
                    }
                    ordersToUpsert.add(finalOrder)
                    count++
                }
            }

            if (idsToDelete.isNotEmpty()) {
                orderDao.deleteOrdersByIds(idsToDelete.distinct())
            }
            if (ordersToUpsert.isNotEmpty()) {
                orderDao.upsertAllByFirestoreIds(ordersToUpsert)
            }

            if (shouldReconcileWallets) {
                reconcileDailyWallets()
            }
            Log.i("CaptainRepository", "[FirestoreSync] Sync complete: $count orders saved ($pendingCount PENDING, $activeCount active)")
            Result.success(SyncOrdersSummary(totalSynced = count, pendingCount = pendingCount, activeCount = activeCount))
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[FirestoreSync] syncOrdersFromFirestore error: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun syncDailyWalletsSubcollection(myUid: String) {
        if (myUid.isBlank()) return
        try {
            val dailySnap = firestore.collection("captains")
                .document(myUid)
                .collection("daily_wallets")
                .get()
                .await()
            val remoteRecords = dailySnap.documents.mapNotNull { d ->
                val dayKey = d.safeString("dayKey", default = d.id)
                if (!DATE_KEY_REGEX.matches(dayKey)) return@mapNotNull null
                val (startMs, endMs) = DailyWalletManager.getOperationalDayBounds(dayKey)
                DailyWalletEntity(
                    dayKey = dayKey,
                    captainUid = myUid,
                    periodStartMs = d.safeLong("periodStartMs", default = startMs),
                    periodEndMs = d.safeLong("periodEndMs", default = endMs),
                    walletBalance = d.safeDouble("walletBalance", default = 0.0),
                    deliveredOrdersCount = d.safeLong("deliveredOrdersCount", default = 0L).toInt(),
                    totalOrdersCount = d.safeLong("totalOrdersCount", default = 0L).toInt(),
                    isClosed = (d.get("isClosed") as? Boolean) ?: (dayKey < DailyWalletManager.getOperationalDayKey()),
                    closedAt = d.safeLong("closedAt", default = 0L),
                    updatedAt = d.safeLong("updatedAt", default = System.currentTimeMillis())
                )
            }
            if (remoteRecords.isNotEmpty()) {
                dailyWalletDao?.upsertAll(remoteRecords)
            }
        } catch (_: Exception) {
            // Subcollection might be restricted by rules; local Room daily_wallet_records handles persistence seamlessly
        }
    }

    private suspend fun persistOwnedHistoryDocuments(
        docs: Collection<DocumentSnapshot>,
        myUid: String,
        myPhone: String
    ): Int {
        val toSave = mutableListOf<OrderEntity>()
        for (doc in docs) {
            val parsed = doc.toOrderEntity() ?: continue
            val docCapt = doc.safeString(
                "captainId", "captain_id", "driverId", "driver_id",
                "assignedCaptainId", "assignedDriverId", "captainUid",
                "driverUid", "assignedCaptainPhone", "driverPhone", "captain_phone"
            )
            val isOwnedByMe = docCapt == myUid ||
                doc.safeString("captainUid", "driverUid") == myUid ||
                (myPhone.isNotBlank() && isSamePhone(docCapt, myPhone))
            if (!isOwnedByMe) continue

            val ensuredCaptainId = if (isPlaceholderOrUnassigned(parsed.captainId)) {
                parsed.copy(captainId = myUid)
            } else {
                parsed
            }
            toSave.add(ensuredCaptainId)
        }
        if (toSave.isNotEmpty()) {
            orderDao.upsertAllByFirestoreIds(toSave)
        }
        return toSave.size
    }

    /**
     * Attempts a date-bounded parallel Firestore sync for [windowStartMs, windowEndMs] across the 4
     * legacy ownership fields. Returns null if the Firestore backend lacks the required composite
     * index (FAILED_PRECONDITION), allowing the caller to fall back seamlessly without breaking.
     */
    private suspend fun trySyncDateBoundedOrders(
        myUid: String,
        myPhone: String,
        windowStartMs: Long,
        windowEndMs: Long
    ): Result<Int> {
        val startTs = Timestamp(Date(windowStartMs))
        val endTs = Timestamp(Date(windowEndMs))
        return try {
            // One Firestore OR query replaces four parallel queries; duplicate matches are returned once.
            val ownershipFilter = Filter.or(
                *ORDER_ASSIGNMENT_QUERY_FIELDS.map { Filter.equalTo(it, myUid) }.toTypedArray()
            )
            val snapshot = firestore.collection("orders")
                .where(ownershipFilter)
                .whereGreaterThanOrEqualTo("createdAt", startTs)
                .whereLessThanOrEqualTo("createdAt", endTs)
                .get()
                .await()
            Result.success(persistOwnedHistoryDocuments(snapshot.documents, myUid, myPhone))
        } catch (e: Exception) {
            val fsEx = e as? FirebaseFirestoreException
            if (fsEx?.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                Log.e("CaptainRepository", "[HistorySync] Missing composite index; skipping remote sync instead of reading the entire archive.", e)
            } else {
                Log.w("CaptainRepository", "[HistorySync] Bounded order history query failed", e)
            }
            Result.failure(e)
        }
    }

    /**
     * Lightweight startup & operational-day sync: downloads only the current Cairo operational cycle
     * (+ 48h overnight buffer) and daily wallet summaries instead of pulling the entire lifetime
     * order archive on cold start. Protected by a session TTL cache.
     */
    suspend fun syncRecentOperationalOrdersFromFirestore(forceRefresh: Boolean = false): Result<Int> {
        val currentCaptain = captainDao.getCaptain()
        val myUid = runCatching { firebaseAuth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: currentCaptain?.uid.orEmpty()
        if (myUid.isBlank()) {
            return Result.failure(IllegalStateException("لا يوجد كابتن مسجل حالياً"))
        }

        return historySyncMutex.withLock {
            val nowMs = System.currentTimeMillis()
            if (!forceRefresh &&
                ((lastOperationalHistorySyncUid == myUid && (nowMs - lastOperationalHistorySyncAtMs) < HISTORY_SYNC_TTL_MS) ||
                    (lastFullHistorySyncUid == myUid && (nowMs - lastFullHistorySyncAtMs) < HISTORY_SYNC_TTL_MS))
            ) {
                return@withLock Result.success(0)
            }

            val myPhone = currentCaptain?.phoneNumber.orEmpty()
            val currentDayKey = DailyWalletManager.getOperationalDayKey(nowMs)
            val (opStartMs, opEndMs) = DailyWalletManager.getOperationalDayBounds(currentDayKey)
            val windowStartMs = opStartMs - OVERNIGHT_QUERY_BUFFER_MS
            val windowEndMs = maxOf(opEndMs, nowMs) + 60L * 60L * 1000L

            val boundedResult = trySyncDateBoundedOrders(
                myUid = myUid,
                myPhone = myPhone,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs
            )

            syncDailyWalletsSubcollection(myUid)
            reconcileDailyWallets(nowMs)
            if (boundedResult.isSuccess) {
                lastOperationalHistorySyncUid = myUid
                lastOperationalHistorySyncAtMs = nowMs
                syncedDateKeysCache["$myUid:$currentDayKey"] = nowMs
            }
            boundedResult
        }
    }

    /**
     * Syncs all historical orders assigned to this captain from Firestore (strictly by authenticated UID)
     * in parallel, upserts in a single Room transaction, and reconciles daily wallet balances rolled over
     * at 1:00 PM Cairo time. Respects TTL unless [forceRefresh] is true.
     */
    suspend fun syncCaptainOrdersHistoryFromFirestore(forceRefresh: Boolean = false): Result<Int> {
        val currentCaptain = captainDao.getCaptain()
        val myUid = runCatching { firebaseAuth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: currentCaptain?.uid.orEmpty()
        if (myUid.isBlank()) {
            return Result.failure(IllegalStateException("لا يوجد كابتن مسجل حالياً"))
        }
        return historySyncMutex.withLock {
            syncCaptainOrdersHistoryInternal(
                myUid = myUid,
                myPhone = currentCaptain?.phoneNumber.orEmpty(),
                forceRefresh = forceRefresh
            )
        }
    }

    private suspend fun syncCaptainOrdersHistoryInternal(
        myUid: String,
        myPhone: String,
        forceRefresh: Boolean
    ): Result<Int> {
        val nowMs = System.currentTimeMillis()
        if (!forceRefresh &&
            lastFullHistorySyncUid == myUid &&
            (nowMs - lastFullHistorySyncAtMs) < HISTORY_SYNC_TTL_MS
        ) {
            return Result.success(0)
        }

        return try {
            val ownershipFilter = Filter.or(
                *ORDER_ASSIGNMENT_QUERY_FIELDS.map { Filter.equalTo(it, myUid) }.toTypedArray()
            )
            val (snapshot, _) = coroutineScope {
                val ordersJob = async(Dispatchers.IO) {
                    firestore.collection("orders").where(ownershipFilter).get().await()
                }
                val walletJob = async(Dispatchers.IO) { syncDailyWalletsSubcollection(myUid) }
                ordersJob.await() to walletJob.await()
            }
            val savedCount = persistOwnedHistoryDocuments(snapshot.documents, myUid, myPhone)
            reconcileDailyWallets()
            lastFullHistorySyncUid = myUid
            lastFullHistorySyncAtMs = System.currentTimeMillis()
            Result.success(savedCount)
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[HistorySync] syncCaptainOrdersHistoryFromFirestore notice: ${e.message}")
            reconcileDailyWallets()
            Result.failure(e)
        }
    }

    /**
     * Fetches and syncs archived finished orders (DELIVERED and CANCELLED) strictly for the
     * logged-in captain on the requested calendar [dateKey] ("yyyy-MM-dd").
     * - Serves local Room immediately via reactive Flows; uses per-date TTL cache to avoid
     *   redundant Firestore queries when switching dates or tabs.
     * - Bypasses cache when [forceRefresh] is true (manual refresh button).
     * - Never deletes historical records from Firestore or Room.
     */
    suspend fun syncCaptainArchivedOrdersForDate(
        dateKey: String,
        forceRefresh: Boolean = false
    ): Result<Int> {
        val currentCaptain = captainDao.getCaptain()
        val myUid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: currentCaptain?.uid.orEmpty()
        if (myUid.isBlank()) {
            return Result.failure(IllegalStateException("يجب تسجيل الدخول لعرض سجل الطلبات"))
        }

        val myPhone = currentCaptain?.phoneNumber.orEmpty()
        val cacheKey = "$myUid:$dateKey"
        val nowMs = System.currentTimeMillis()
        val isCacheFresh = !forceRefresh && (
            (syncedDateKeysCache[cacheKey]?.let { (nowMs - it) < HISTORY_SYNC_TTL_MS } == true) ||
                (lastFullHistorySyncUid == myUid && (nowMs - lastFullHistorySyncAtMs) < HISTORY_SYNC_TTL_MS)
            )

        if (!isCacheFresh) {
            val override = archivedOrdersDateSyncOverride
            val syncResult = if (override != null) {
                override(myUid, dateKey, forceRefresh).also { res ->
                    if (res.isSuccess) {
                        syncedDateKeysCache[cacheKey] = System.currentTimeMillis()
                    }
                }
            } else {
                historySyncMutex.withLock {
                    val freshInsideLock = !forceRefresh && (
                        (syncedDateKeysCache[cacheKey]?.let { (System.currentTimeMillis() - it) < HISTORY_SYNC_TTL_MS } == true) ||
                            (lastFullHistorySyncUid == myUid && (System.currentTimeMillis() - lastFullHistorySyncAtMs) < HISTORY_SYNC_TTL_MS)
                        )
                    if (freshInsideLock) {
                        Result.success(0)
                    } else {
                        val (dayStartMs, dayEndMs) = OrderHistoryHelper.getCalendarDayBoundsMs(dateKey)
                        val bounded = trySyncDateBoundedOrders(
                            myUid = myUid,
                            myPhone = myPhone,
                            windowStartMs = dayStartMs - OVERNIGHT_QUERY_BUFFER_MS,
                            windowEndMs = dayEndMs + OVERNIGHT_QUERY_BUFFER_MS
                        )
                        if (bounded.isSuccess) {
                            syncedDateKeysCache[cacheKey] = System.currentTimeMillis()
                            reconcileDailyWallets()
                        }
                        bounded
                    }
                }
            }
            if (syncResult.isFailure) {
                return syncResult
            }
        }

        val allLocal = orderDao.getAllOrdersSnapshot()
        val matchingForDate = OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = allLocal,
            captainUid = myUid,
            captainPhone = myPhone,
            selectedDateKey = dateKey
        )
        return Result.success(matchingForDate.size)
    }

    internal fun clearHistorySyncCaches() {
        lastFullHistorySyncUid = null
        lastFullHistorySyncAtMs = 0L
        lastOperationalHistorySyncUid = null
        lastOperationalHistorySyncAtMs = 0L
        syncedDateKeysCache.clear()
        lastShiftSyncUid = null
        lastShiftSyncAtMs = 0L
    }

    /**
     * Reconciles daily wallet records using the 1:00 PM (13:00 Cairo) rollover boundary:
     * - Archives completed cycles (< currentOperationalDayKey) with their recorded wallet balance.
     * - Ensures the new operational day starting at 1:00 PM begins at 0.0 EGP and accumulates only
     *   orders delivered during the new cycle.
     */
    suspend fun reconcileDailyWallets(nowMs: Long = System.currentTimeMillis()) {
        val dao = dailyWalletDao ?: return
        try {
            val currentCaptain = captainDao.getCaptain()
            val myUid = runCatching { firebaseAuth.currentUser?.uid }.getOrNull() ?: currentCaptain?.uid ?: ""
            val myPhone = currentCaptain?.phoneNumber ?: ""
            val allOrders = orderDao.getAllOrdersSnapshot()
            val ownOrders = allOrders.filter { DailyWalletManager.isCaptainOwnOrder(it, myUid, myPhone) }
            val existing = dao.getAllDailyWallets()

            val updatedRecords = DailyWalletManager.buildDailyRecords(
                captainUid = myUid,
                captainOrders = ownOrders,
                existingRecords = existing,
                nowMs = nowMs
            )
            dao.upsertAll(updatedRecords)
            // Financial daily_wallets subcollection in Firestore is server/admin-authoritative (read-only for clients).
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[DailyWallet] reconcileDailyWallets notice: ${e.message}")
        }
    }

    /**
     * Stops all active realtime listeners for shifts.
     */
    fun stopRealtimeShiftListener() {
        shiftSyncJob?.cancel()
        shiftSyncJob = null
        shiftSyncUid = null
        captainScheduleDocListenerRegistration?.remove()
        captainScheduleDocListenerRegistration = null
        captainScheduleSubcollectionListenerRegistration?.remove()
        captainScheduleSubcollectionListenerRegistration = null
        captainScheduleQueryListenerRegistration?.remove()
        captainScheduleQueryListenerRegistration = null
        captainScheduleUidQueryListenerRegistration?.remove()
        captainScheduleUidQueryListenerRegistration = null
        captainScheduleDriverQueryListenerRegistration?.remove()
        captainScheduleDriverQueryListenerRegistration = null
        captainSubcollectionShiftListenerRegistration?.remove()
        captainSubcollectionShiftListenerRegistration = null
        shiftListenerRegistration?.remove()
        shiftListenerRegistration = null
        captainDocShiftListenerRegistration?.remove()
        captainDocShiftListenerRegistration = null
    }

    /**
     * Force-stops all realtime orders listeners and clears every registered consumer.
     * Used when captain goes offline, logs out, loses approval, or leaves active shift.
     */
    fun stopRealtimeOrdersListener() {
        synchronized(ordersListenerLock) {
            ordersListenerConsumers.clear()
            ordersListenerRegistrations.forEach { runCatching { it.remove() } }
            ordersListenerRegistrations.clear()
            listenerMatchedDocIds.clear()
            activeOrdersListenerUid = null
            activeOrdersListenerShiftKey = null
        }
    }

    /**
     * Starts a 30-minute schedule poller for the captain's work shifts.
     * Deduplicated per UID so multiple consumers never spawn duplicate polling jobs.
     */
    fun startRealtimeShiftListener() {
        if (hasTestBackendOverride) return
        val user = runCatching { firebaseAuth.currentUser }.getOrNull()
        if (user == null) {
            Log.d("CaptainRepository", "[ShiftListener] Waiting for authenticated captain")
            return
        }
        val uid = user.uid
        if (shiftSyncJob?.isActive == true && shiftSyncUid == uid) return

        stopRealtimeShiftListener()
        shiftSyncUid = uid
        _isShiftNetworkConnected.value = true
        Log.i("CaptainRepository", "[ShiftPolling] Starting 30-minute schedule polling for captain $uid")

        shiftSyncJob = repositoryScope.launch {
            while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                if (runCatching { firebaseAuth.currentUser?.uid }.getOrNull() != uid) {
                    Log.i("CaptainRepository", "[ShiftPolling] Auth session changed; stopping schedule polling")
                    break
                }
                val result = syncShiftsFromFirestore(forceRefresh = false)
                _isShiftNetworkConnected.value = result.isSuccess
                if (result.isSuccess) {
                    Log.i("CaptainRepository", "[ShiftPolling] Schedule refreshed; next database read in 30 minutes")
                } else {
                    Log.w("CaptainRepository", "[ShiftPolling] Schedule refresh failed: ${result.exceptionOrNull()?.message}")
                }
                delay(shiftSyncIntervalMs)
            }
        }
    }
    internal suspend fun syncShiftsUsingResolvedSources(
        uid: String,
        canonicalDocExists: Boolean,
        canonicalDocData: Map<String, Any?>?,
        legacyCandidateLists: List<List<ShiftEntity>> = emptyList()
    ): List<ShiftEntity> {
        if (canonicalDocExists) {
            val canonicalShifts = if (canonicalDocData != null) {
                extractShiftsFromMap(canonicalDocData, uid, uid)
            } else {
                emptyList()
            }
            saveShiftsAndScheduleAlarms(uid, canonicalShifts)
            _isShiftSynced.value = true
            _isShiftNetworkConnected.value = true
            Log.i("CaptainRepository", "[ShiftSync] Applied ${canonicalShifts.size} shifts from canonical schedule")
            return canonicalShifts
        }

        // Choose the most recently updated valid legacy fallback, or clear Room if none exist.
        val chosenList = legacyCandidateLists
            .filter { it.isNotEmpty() }
            .maxByOrNull { list -> list.maxOfOrNull { it.updatedAt } ?: 0L }
            ?: emptyList()

        saveShiftsAndScheduleAlarms(uid, chosenList)
        _isShiftSynced.value = true
        _isShiftNetworkConnected.value = true
        if (chosenList.isNotEmpty()) {
            Log.i("CaptainRepository", "[ShiftSync] Synced and atomically saved ${chosenList.size} legacy fallback shifts in Room")
        } else {
            Log.i("CaptainRepository", "[ShiftSync] No canonical schedule or valid legacy fallback found; cleared local Room shifts")
        }
        return chosenList
    }

    /**
     * One-time sync of shifts from Firestore for manual refresh or initial load.
     * Deduplicated via [shiftSyncMutex] + short TTL (30s) when [forceRefresh] is false
     * so concurrent startup callers never duplicate Firestore reads.
     */
    suspend fun syncShiftsFromFirestore(forceRefresh: Boolean = false): Result<List<ShiftEntity>> {
        val user = firebaseAuth.currentUser ?: return Result.failure(IllegalStateException("يرجى تسجيل الدخول أولاً"))
        val uid = user.uid
        return shiftSyncMutex.withLock {
            val nowMs = System.currentTimeMillis()
            if (!forceRefresh &&
                lastShiftSyncUid == uid &&
                (nowMs - lastShiftSyncAtMs) < SHIFT_SYNC_DEDUPE_TTL_MS
            ) {
                val cached = getShifts()
                return@withLock Result.success(cached)
            }
            try {
                Log.i("CaptainRepository", "[ShiftSync] Reading canonical captain_schedules/$uid first")
                val canonicalDoc = firestore.collection("captain_schedules").document(uid).get().await()
                if (canonicalDoc.exists()) {
                    val applied = syncShiftsUsingResolvedSources(
                        uid = uid,
                        canonicalDocExists = true,
                        canonicalDocData = canonicalDoc.data
                    )
                    lastShiftSyncUid = uid
                    lastShiftSyncAtMs = System.currentTimeMillis()
                    return@withLock Result.success(applied)
                }

                // Legacy fallback is used only when the canonical admin schedule document is absent.
                // Run fallback reads in parallel for faster cold start.
                val candidateLists = mutableListOf<List<ShiftEntity>>()
                val fallbackResults = coroutineScope {
                    listOf(
                        async(Dispatchers.IO) {
                            runCatching {
                                val subSnap = firestore.collection("captain_schedules").document(uid).collection("shifts").get().await()
                                subSnap.documents.flatMap { it.extractAllShifts(uid) }
                            }.getOrDefault(emptyList())
                        },
                        async(Dispatchers.IO) {
                            runCatching {
                                firestore.collection("captain_schedules").whereEqualTo("captainId", uid).get().await()
                                    .documents.flatMap { it.extractAllShifts(uid) }
                            }.getOrDefault(emptyList())
                        },
                        async(Dispatchers.IO) {
                            runCatching {
                                firestore.collection("captain_schedules").whereEqualTo("captainUid", uid).get().await()
                                    .documents.flatMap { it.extractAllShifts(uid) }
                            }.getOrDefault(emptyList())
                        },
                        async(Dispatchers.IO) {
                            runCatching {
                                firestore.collection("captain_schedules").whereEqualTo("driverId", uid).get().await()
                                    .documents.flatMap { it.extractAllShifts(uid) }
                            }.getOrDefault(emptyList())
                        },
                        async(Dispatchers.IO) {
                            runCatching {
                                firestore.collection("captains").document(uid).collection("shifts").get().await()
                                    .documents.flatMap { it.extractAllShifts(uid) }
                            }.getOrDefault(emptyList())
                        },
                        async(Dispatchers.IO) {
                            runCatching {
                                val captainDoc = firestore.collection("captains").document(uid).get().await()
                                if (captainDoc.exists()) captainDoc.extractAllShifts(uid) else emptyList()
                            }.getOrDefault(emptyList())
                        }
                    ).awaitAll()
                }
                fallbackResults.filter { it.isNotEmpty() }.forEach { candidateLists.add(it) }

                // Legacy fetch from 'shifts' collection only if no candidates found above
                if (candidateLists.isEmpty()) {
                    try {
                        val legacySnap = firestore.collection("shifts").whereEqualTo("captainId", uid).get().await()
                        val legacyList = legacySnap.documents.mapNotNull { it.toShiftEntity(uid) }
                        if (legacyList.isNotEmpty()) candidateLists.add(legacyList)
                    } catch (e: Exception) {
                        Log.w("CaptainRepository", "[ShiftSync] Query 'shifts' notice: ${e.message}")
                    }
                }

                val applied = syncShiftsUsingResolvedSources(
                    uid = uid,
                    canonicalDocExists = false,
                    canonicalDocData = null,
                    legacyCandidateLists = candidateLists
                )
                lastShiftSyncUid = uid
                lastShiftSyncAtMs = System.currentTimeMillis()
                Result.success(applied)
            } catch (e: Exception) {
                Log.w("CaptainRepository", "[ShiftSync] syncShiftsFromFirestore error: ${e.message}")
                Result.failure(e)
            }
        }
    }

    suspend fun getShifts(): List<ShiftEntity> {
        val uid = resolveCurrentCaptainUid()
            ?: if (hasTestBackendOverride) null else runCatching { firebaseAuth.currentUser?.uid }.getOrNull()
        return if (!uid.isNullOrBlank() && shiftDao != null) {
            shiftDao.getShiftsForCaptainFlow(uid).firstOrNull()
                ?: shiftDao.getAllShiftsFlow().firstOrNull()
                ?: emptyList()
        } else {
            emptyList()
        }
    }

    suspend fun getCaptain(): CaptainEntity? = captainDao.getCaptain()

    /**
     * Resolves the registered captain email corresponding to a phone number.
     * Checks strictly local captain cache and 'captains' collection (NEVER drivers, merchants, or restaurants).
     */
    suspend fun lookupEmailByPhone(inputPhone: String): String? {
        val cleaned = PhoneAuthRegistry.cleanInvisibleChars(inputPhone)
        val normalizedLocal = PhoneAuthRegistry.normalizeEgyptianPhone(cleaned)
        val plainDigits = cleaned.replace(Regex("[^0-9]"), "")
        if (plainDigits.isBlank()) return null

        // 1. Check persistent PhoneAuthRegistry first (0ms, works offline & across logouts)
        PhoneAuthRegistry.getEmailForPhone(context, cleaned)?.let { cachedEmail ->
            Log.i("CaptainRepository", "[PhoneLookup] Resolved email from PhoneAuthRegistry: $cachedEmail")
            return cachedEmail
        }

        // 2. Check local Room database cache
        try {
            val local = captainDao.getCaptainByPhone(normalizedLocal)
                ?: captainDao.getCaptainByPhone(cleaned)
                ?: captainDao.getCaptainByPhone(plainDigits)
                ?: captainDao.getCaptain()?.takeIf { isSamePhone(it.phoneNumber, normalizedLocal) }
            if (local != null && local.email.isNotBlank() && local.accountType.lowercase() == "captain") {
                PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, local.email)
                Log.d("CaptainRepository", "[PhoneLookup] Found email in local Room cache: ${local.email}")
                return local.email
            }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[PhoneLookup] Local check notice: ${e.message}")
        }

        val variantsList = PhoneAuthRegistry.generatePhoneQueryVariants(cleaned)

        // 3. Server-side Cloud Function resolution (if available)
        try {
            val cfEmail = cloudFunctionsOrderClient.resolveEmailByPhone(normalizedLocal, variantsList)
            if (!cfEmail.isNullOrBlank()) {
                PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, cfEmail)
                Log.i("CaptainRepository", "[PhoneLookup] Resolved email via Cloud Function: $cfEmail")
                return cfEmail
            }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[PhoneLookup] Cloud Function lookup notice: ${e.message}")
        }

        // 4. Query strictly 'captains' collection where accountType == 'captain'
        val numericVariants = listOfNotNull(
            plainDigits.toLongOrNull(),
            plainDigits.removePrefix("0").toLongOrNull(),
            plainDigits.removePrefix("20").toLongOrNull()
        ).distinct()

        val foundEmail = runCatching {
            authBackend.queryCaptainEmailByPhoneVariants(variantsList, numericVariants, normalizedLocal)
        }.getOrNull()

        if (!foundEmail.isNullOrBlank()) {
            PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, foundEmail)
        }
        return foundEmail
    }

    /**
     * Sends password reset email for an entered email or phone number.
     */
    suspend fun sendPasswordReset(phoneOrEmail: String): Result<String> {
        val trimmed = PhoneAuthRegistry.cleanInvisibleChars(phoneOrEmail)
        val isEmail = trimmed.contains("@")
        val emailToSend = if (isEmail) {
            trimmed
        } else {
            lookupEmailByPhone(trimmed)
                ?: return Result.failure(Exception("لم يتم العثور على بريد إلكتروني مسجل لرقم الهاتف ($trimmed). يرجى إدخال البريد الإلكتروني المسجل مباشرة لإرسال رابط الاستعادة."))
        }

        return try {
            authBackend.sendPasswordResetEmail(emailToSend)
            val maskedEmail = if (emailToSend.contains("@")) {
                val namePart = emailToSend.substringBefore("@")
                val domain = emailToSend.substringAfter("@")
                val prefix = namePart.take(2)
                "$prefix***@$domain"
            } else emailToSend
            Result.success("تم إرسال رابط استعادة كلمة السر إلى البريد الإلكتروني المسجل ($maskedEmail) بنجاح.")
        } catch (e: Exception) {
            Result.failure(Exception("تعذر إرسال رابط استعادة كلمة السر: ${e.localizedMessage ?: e.message}"))
        }
    }

    private fun Map<String, Any?>.mapString(vararg keys: String, default: String = ""): String {
        for (key in keys) {
            val v = this[key]?.toString()?.trim()
            if (!v.isNullOrBlank() && v != "null") return v
        }
        return default
    }

    private fun Map<String, Any?>.mapDouble(vararg keys: String, default: Double = 0.0): Double {
        for (key in keys) {
            val raw = this[key] ?: continue
            when (raw) {
                is Number -> return raw.toDouble()
                is String -> raw.toDoubleOrNull()?.let { return it }
            }
        }
        return default
    }

    private fun Map<String, Any?>.mapInt(vararg keys: String, default: Int = 0): Int {
        for (key in keys) {
            val raw = this[key] ?: continue
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return default
    }

    /**
     * Validates that a Firestore document retrieved from 'captains/{uid}' is a genuine Captain account.
     * - If 'accountType' is present: it MUST equal "captain" (rejects merchants even if role='captain').
     * - If 'accountType' is missing/blank on a legacy captains/{uid} document: accepts ONLY if 'role' == "captain",
     *   preserving existing legacy fields without performing any unapproved Firestore mutation.
     * - Strictly rejects missing documents, UID mismatches, merchant accounts, and suspended/banned accounts.
     */
    private fun validateCaptainDoc(
        docData: Map<String, Any?>?,
        uid: String
    ): Result<Map<String, Any?>> {
        if (docData == null) {
            return Result.failure(
                IllegalStateException("هذا الحساب غير مسجل ككابتن في قاعدة البيانات. تطبيق الكابتن يقبل حسابات الكباتن فقط.")
            )
        }
        val docUid = docData.mapString("uid")
        if (docUid.isNotBlank() && docUid != uid) {
            return Result.failure(
                IllegalStateException("عدم تطابق معرف الحساب (UID) مع مستند الكابتن.")
            )
        }
        val accountType = docData.mapString("accountType").lowercase()
        val role = docData.mapString("role").lowercase()
        if (!isEligibleCaptainIdentity(accountType, role)) {
            val conflictingValue = accountType.ifBlank { role }
            val msg = if (conflictingValue in listOf("merchant", "restaurant", "vendor", "store")) {
                "هذا الحساب مسجل كحساب تاجر وليس كابتن. ممنوع دخول حسابات التجار إلى تطبيق الكابتن."
            } else {
                "هذا الحساب غير مسجل ككابتن (يجب أن يساوي accountType أو role القيمة captain)."
            }
            return Result.failure(IllegalStateException(msg))
        }
        if (accountType.isBlank() && role == CAPTAIN_ROLE) {
            Log.w(
                "CaptainRepository",
                "Legacy captain document captains/$uid uses role='captain' without accountType; allowing identity check without altering Firestore schema."
            )
        }
        return Result.success(docData)
    }

    private fun buildCaptainEntityFromDoc(
        uid: String,
        authEmail: String,
        authDisplayName: String,
        docData: Map<String, Any?>,
        fallbackPhone: String = "",
        isLoggedIn: Boolean = true,
        isOnline: Boolean = false
    ): CaptainEntity {
        val fullName = docData.mapString("name", "fullName", default = authDisplayName.ifBlank { "كابتن البطل" })
        val rawPhone = docData.mapString("phone", "phoneNumber", default = fallbackPhone)
        val normalizedPhone = PhoneAuthRegistry.normalizeEgyptianPhone(rawPhone).ifBlank { fallbackPhone }
        val resolvedEmail = docData.mapString("email", default = authEmail)
        val profilePath = docData.mapString("personalImage", "profileImagePath")
        val idFrontPath = docData.mapString("idCardFront", "idFrontImagePath")
        val idBackPath = docData.mapString("idCardBack", "idBackImagePath")
        val licensePath = docData.mapString("licenseImagePath", "licenseImage")
        val avatarUri = profilePath.ifBlank {
            docData.mapString("profileImageUrl", "photoUrl", "avatarUri")
        }
        val resolvedRole = docData.mapString("role", default = CAPTAIN_ROLE).lowercase()
        val resolvedAccountType = docData.mapString("accountType", default = resolvedRole).lowercase()
        return CaptainEntity(
            id = 1,
            uid = uid,
            fullName = fullName,
            phoneNumber = normalizedPhone,
            email = resolvedEmail,
            password = "", // NEVER store password
            avatarUri = avatarUri,
            avatarPresetIndex = docData.mapInt("avatarPresetIndex", default = 0),
            nationalIdUrl = docData.mapString("idCardImageUrl", "nationalIdUrl"),
            licenseUrl = docData.mapString("licenseImageUrl", "licenseUrl"),
            profileImagePath = profilePath,
            idFrontImagePath = idFrontPath,
            idBackImagePath = idBackPath,
            licenseImagePath = licensePath,
            address = docData.mapString("address"),
            accountType = resolvedAccountType.ifBlank { CAPTAIN_ACCOUNT_TYPE },
            role = resolvedRole.ifBlank { CAPTAIN_ROLE },
            status = docData.mapString("status", default = "active").lowercase(),
            availabilityStatus = if (isOnline) {
                docData.mapString("availabilityStatus", default = "working")
            } else {
                "offline"
            },
            shiftStatus = docData.mapString("shiftStatus", default = "outside_shift"),
            vehicleType = docData.mapString("vehicleType", "vehicle", default = "سكوتر / دراجة نارية"),
            licensePlate = docData.mapString("licensePlate", default = "أ ب ج 1 2 3"),
            rating = docData.mapDouble("rating", default = 5.0).toFloat(),
            totalOrdersCount = docData.mapInt("totalOrdersCount", default = 0),
            walletBalance = docData.mapDouble("walletBalance", default = 0.0),
            isOnline = isOnline,
            isLoggedIn = isLoggedIn
        )
    }

    suspend fun login(phoneOrEmail: String, pass: String): LoginOutcome {
        val cleanedInput = PhoneAuthRegistry.cleanInvisibleChars(phoneOrEmail)
        val isEmail = cleanedInput.contains("@")
        if (cleanedInput.isBlank()) {
            return LoginOutcome.Error("يرجى إدخال البريد الإلكتروني أو رقم الهاتف.")
        }

        var authEmail = cleanedInput
        var preSignedUser: AuthUserSnapshot? = null

        // 1. Resolve registered captain email if phone number entered
        if (!isEmail) {
            val digitsOnly = cleanedInput.replace(Regex("[^0-9]"), "")
            if (digitsOnly.length < 8) {
                return LoginOutcome.Error("يرجى إدخال رقم هاتف صحيح مكون من 11 رقماً (مثال: 01012345678).")
            }

            val foundEmail = lookupEmailByPhone(cleanedInput)
            if (foundEmail != null) {
                authEmail = foundEmail
                try {
                    preSignedUser = authBackend.signInWithEmailAndPassword(authEmail, pass)
                } catch (firstErr: Exception) {
                    return mapAuthExceptionToLoginError(firstErr, isEmail = false)
                }
            } else {
                var lastCandidateException: Exception? = null
                for (cand in PhoneAuthRegistry.generateSyntheticPhoneEmails(cleanedInput)) {
                    try {
                        val res = authBackend.signInWithEmailAndPassword(cand, pass)
                        authEmail = cand
                        preSignedUser = res
                        break
                    } catch (e: Exception) {
                        lastCandidateException = e
                        val msg = e.localizedMessage?.lowercase() ?: ""
                        if (msg.contains("too-many-requests") || msg.contains("network")) {
                            break
                        }
                    }
                }
                if (preSignedUser == null) {
                    val lastMsg = lastCandidateException?.localizedMessage?.lowercase() ?: ""
                    return when {
                        lastMsg.contains("too-many-requests") ->
                            LoginOutcome.Error("تم حظر المحاولات مؤقتاً لتكرار المحاولات الخاطئة. يرجى الانتظار دقيقة والمحاولة مجدداً.")
                        lastMsg.contains("network") ->
                            LoginOutcome.Error("يرجى التحقق من اتصال الإنترنت والمحاولة مرة أخرى.")
                        else ->
                            LoginOutcome.Error("لم يتم العثور على حساب كابتن مسجل برقم الهاتف ($cleanedInput) أو كلمة المرور غير صحيحة.")
                    }
                }
            }
        }

        // 2. Authenticate with Firebase Authentication
        val initialUser = preSignedUser ?: try {
            authBackend.signInWithEmailAndPassword(authEmail, pass)
        } catch (e: Exception) {
            return mapAuthExceptionToLoginError(e, isEmail = isEmail)
        }

        // 3. Reload Firebase Auth user & enforce Email Verification unconditionally (NEVER bypass!)
        val verifiedUser = try {
            authBackend.reloadCurrentUser() ?: initialUser
        } catch (e: Exception) {
            Log.w("CaptainRepository", "User reload warning: ${e.message}")
            initialUser
        }

        if (!verifiedUser.isEmailVerified) {
            captainDao.updateLoginState(false)
            return LoginOutcome.EmailNotVerified(verifiedUser.email.ifBlank { authEmail })
        }

        val uid = verifiedUser.uid

        // 4. Fetch Captain profile EXCLUSIVELY from Firestore server captains/{uid} — fail-closed on any network/permission error!
        val rawCaptainDoc = try {
            authBackend.getCaptainDocFromServer(uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Failed reading captains/$uid during login: ${e.message}", e)
            // Keep the Firebase Auth identity so the captain can retry after a transient
            // Firestore outage. Operational access remains closed until server approval succeeds.
            captainDao.updateLoginState(false)
            captainDao.updateOnlineStatus(false)
            _accountApprovalState.value = null
            stopRealtimeOrdersListener()
            stopRealtimeShiftListener()
            val fsEx = e as? FirebaseFirestoreException
            val errMessage = if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "تم رفض قراءة بيانات الكابتن من قواعد الأمان (PERMISSION_DENIED) للمسار captains/$uid."
            } else {
                "تعذر التحقق من حالة اعتماد حساب الكابتن في قاعدة البيانات. يرجى التحقق من اتصال الإنترنت والمحاولة مجدداً."
            }
            return LoginOutcome.Error(errMessage)
        }

        val validDoc = validateCaptainDoc(rawCaptainDoc, uid).getOrElse { err ->
            authBackend.signOut()
            captainDao.updateLoginState(false)
            _accountApprovalState.value = null
            return LoginOutcome.Error(err.message ?: "هذا الحساب غير مصرح له بالدخول ككابتن.")
        }

        val resolvedEmail = verifiedUser.email.ifBlank { authEmail }
        val approvalSnapshot = CaptainAccountApprovalResolver.resolve(
            docData = validDoc,
            fallbackEmail = resolvedEmail,
            fallbackName = verifiedUser.displayName
        )
        _accountApprovalState.value = approvalSnapshot

        // 5. Mandatory server-side Admin Approval gate — NEVER grant Main access unless approved!
        if (!approvalSnapshot.canEnterApp) {
            stopRealtimeOrdersListener()
            stopRealtimeShiftListener()
            orderDao.deleteAllPendingOrders()
            context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }

            val pendingEntity = buildCaptainEntityFromDoc(
                uid = uid,
                authEmail = resolvedEmail,
                authDisplayName = verifiedUser.displayName,
                docData = validDoc,
                fallbackPhone = if (!isEmail) PhoneAuthRegistry.normalizeEgyptianPhone(cleanedInput) else "",
                isLoggedIn = false,
                isOnline = false
            )
            if (pendingEntity.phoneNumber.isNotBlank()) {
                PhoneAuthRegistry.savePhoneEmailMapping(context, pendingEntity.phoneNumber, resolvedEmail)
            }
            captainDao.insertOrUpdate(pendingEntity)

            // Sync verified email & offline flags ONLY (allowed by firestore.rules for unapproved accounts)
            runCatching {
                authBackend.updateCaptainDoc(
                    uid,
                    mapOf(
                        "emailVerified" to true,
                        "isEmailVerified" to true,
                        "isOnline" to false,
                        "isLoggedIn" to false,
                        "availabilityStatus" to "offline",
                        "presenceStatus" to "offline",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            return LoginOutcome.AwaitingApproval(approvalSnapshot)
        }

        // Authentication is strictly decoupled from shift presence:
        // always start locally offline after login until the server confirms shift activation.
        _serverConfirmedWorkingSession.value = null
        stopRealtimeOrdersListener()

        val captainEntity = buildCaptainEntityFromDoc(
            uid = uid,
            authEmail = resolvedEmail,
            authDisplayName = verifiedUser.displayName,
            docData = validDoc,
            fallbackPhone = if (!isEmail) PhoneAuthRegistry.normalizeEgyptianPhone(cleanedInput) else "",
            isLoggedIn = true,
            isOnline = false
        )

        if (captainEntity.phoneNumber.isNotBlank()) {
            PhoneAuthRegistry.savePhoneEmailMapping(context, captainEntity.phoneNumber, resolvedEmail)
        }
        captainDao.insertOrUpdate(captainEntity)

        // 6. Sync verified authentication fields ONLY on captains/{uid} (never assert isOnline=true on login)
        runCatching {
            authBackend.updateCaptainDoc(
                uid,
                mapOf(
                    "emailVerified" to true,
                    "isEmailVerified" to true,
                    "isLoggedIn" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        // 7. Start shift schedule listener ONLY; orders listener starts only after server confirms working state.
        if (!hasTestBackendOverride) {
            startRealtimeShiftListener()
            repositoryScope.launch {
                syncRecentOperationalOrdersFromFirestore(forceRefresh = false)
            }
        }

        return LoginOutcome.Success
    }

    private fun mapAuthExceptionToLoginError(e: Exception, isEmail: Boolean): LoginOutcome.Error {
        if (e is FirebaseAuthInvalidCredentialsException) {
            val fieldType = if (isEmail) "البريد الإلكتروني" else "رقم الهاتف"
            return LoginOutcome.Error("كلمة المرور أو $fieldType غير صحيح. يرجى التأكد من كتابة البيانات بدقة.")
        }
        Log.w("CaptainRepository", "Firebase Auth sign in notice: ${e.message}")
        val msg = e.localizedMessage?.lowercase() ?: ""
        val userFriendlyMsg = when {
            msg.contains("incorrect") || msg.contains("malformed") || msg.contains("expired") ||
                msg.contains("invalid-credential") || msg.contains("invalid credential") ||
                msg.contains("invalid_login_credentials") ->
                "كلمة المرور أو بيانات الدخول غير صحيحة. يرجى التأكد من البيانات وكلمة المرور."
            msg.contains("user-not-found") || msg.contains("no user record") ->
                "لم يتم العثور على حساب مسجل بهذه البيانات. يرجى التأكد من البيانات أو إنشاء حساب جديد."
            msg.contains("too-many-requests") || msg.contains("blocked") ->
                "تم حظر المحاولات مؤقتاً لتكرار المحاولات الخاطئة. يرجى الانتظار دقيقة والمحاولة مجدداً."
            else ->
                "خطأ في تسجيل الدخول: ${e.localizedMessage ?: "يرجى التحقق من صحة البيانات والمحاولة مجدداً"}"
        }
        return LoginOutcome.Error(userFriendlyMsg)
    }

    /**
     * Strict session validation on app resume / splash:
     * 1. Requires non-null Firebase Auth currentUser.
     * 2. Reloads Firebase Auth user and strictly requires isEmailVerified == true.
     * 3. Fetches exclusively captains/{uid} and requires existence + accountType == "captain" + active/pending status.
     */
    suspend fun validateCurrentSession(): Boolean {
        return resolveStartupSessionState() is StartupSessionState.Authenticated
    }

    /**
     * Evaluates Firebase Authentication and server-side Admin Approval state on startup / session restore:
     * - Authenticated: valid verified Firebase session + live Firestore server check confirms approved.
     * - NeedsEmailVerification: Firebase user exists but email verification is incomplete.
     * - AwaitingApproval: Email verified, but Firestore status is pending, rejected, suspended, or blocked.
     * - ApprovalCheckFailed: Firestore server read failed (e.g. no internet). Fail-closed: NEVER enters Main.
     * - Unauthenticated: signed out, invalid user, or non-captain account.
     */
    suspend fun resolveStartupSessionState(): StartupSessionState {
        val currentAuth = authBackend.currentUser()
        if (currentAuth == null) {
            _accountApprovalState.value = null
            captainDao.updateLoginState(false)
            stopRealtimeOrdersListener()
            stopRealtimeShiftListener()
            return StartupSessionState.Unauthenticated
        }

        val reloadedUser = try {
            authBackend.reloadCurrentUser() ?: currentAuth
        } catch (reloadErr: Exception) {
            Log.w("CaptainRepository", "Session reload notice: ${reloadErr.message}")
            if (reloadErr is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                authBackend.signOut()
                _accountApprovalState.value = null
                captainDao.updateLoginState(false)
                stopRealtimeOrdersListener()
                stopRealtimeShiftListener()
                return StartupSessionState.Unauthenticated
            }
            currentAuth
        }

        if (!reloadedUser.isEmailVerified) {
            _accountApprovalState.value = null
            captainDao.updateLoginState(false)
            stopRealtimeOrdersListener()
            stopRealtimeShiftListener()
            val pendingEmail = reloadedUser.email.ifBlank { captainDao.getCaptain()?.email.orEmpty() }
            return StartupSessionState.NeedsEmailVerification(pendingEmail)
        }

        val uid = reloadedUser.uid
        return try {
            val rawDoc = authBackend.getCaptainDocFromServer(uid)
            val validDoc = validateCaptainDoc(rawDoc, uid).getOrElse {
                authBackend.signOut()
                _accountApprovalState.value = null
                captainDao.updateLoginState(false)
                stopRealtimeOrdersListener()
                stopRealtimeShiftListener()
                return StartupSessionState.Unauthenticated
            }

            val approvalSnapshot = CaptainAccountApprovalResolver.resolve(
                docData = validDoc,
                fallbackEmail = reloadedUser.email,
                fallbackName = reloadedUser.displayName
            )
            _accountApprovalState.value = approvalSnapshot

            if (!approvalSnapshot.canEnterApp) {
                revokeLocalOperationalSession(uid = uid, docData = validDoc, authUser = reloadedUser)
                return StartupSessionState.AwaitingApproval(approvalSnapshot)
            }

            val current = captainDao.getCaptain()
            val restoredSession = reconcileServerPresenceFromDoc(
                uid = uid,
                docData = validDoc,
                shifts = getShifts()
            )
            val restoredIsOnline = restoredSession != null
            val refreshed = buildCaptainEntityFromDoc(
                uid = uid,
                authEmail = reloadedUser.email,
                authDisplayName = reloadedUser.displayName,
                docData = validDoc,
                fallbackPhone = current?.phoneNumber.orEmpty(),
                isLoggedIn = true,
                isOnline = restoredIsOnline
            )
            if (refreshed.phoneNumber.isNotBlank() && refreshed.email.isNotBlank()) {
                PhoneAuthRegistry.savePhoneEmailMapping(context, refreshed.phoneNumber, refreshed.email)
            }
            captainDao.insertOrUpdate(refreshed)

            if (restoredSession?.isConfirmedWorking == true) {
                startRealtimeOrdersListener()
            } else {
                stopRealtimeOrdersListener()
            }

            if (!hasTestBackendOverride) {
                startRealtimeShiftListener()
                CoroutineScope(Dispatchers.IO).launch {
                    reconcileDailyWallets()
                }
            }
            StartupSessionState.Authenticated
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // FAIL-CLOSED: Never trust Room or local cache as an approval source when Firestore check fails!
            Log.w("CaptainRepository", "Fail-closed startup approval verification error: ${e.message}")
            _serverConfirmedWorkingSession.value = null
            captainDao.updateLoginState(false)
            captainDao.updateOnlineStatus(false)
            orderDao.deleteAllPendingOrders()
            stopRealtimeOrdersListener()
            stopRealtimeShiftListener()
            context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }

            val current = captainDao.getCaptain()
            val failureSnapshot = CaptainAccountApprovalResolver.networkFailure(
                message = "تعذر التحقق من موافقة الإدارة على حسابك بسبب انقطاع الاتصال. يرجى التأكد من الإنترنت والمحاولة مجدداً.",
                fallbackEmail = reloadedUser.email.ifBlank { current?.email.orEmpty() },
                fallbackName = reloadedUser.displayName.ifBlank { current?.fullName.orEmpty() }
            )
            _accountApprovalState.value = failureSnapshot
            StartupSessionState.ApprovalCheckFailed(failureSnapshot)
        }
    }

    /**
     * Forces a fresh Firestore server read of `captains/{uid}` to verify current admin approval.
     * Used on manual "تحديث حالة الحساب" taps and app foreground resume (`ON_RESUME`).
     * Fail-closed: network or Firestore failures NEVER grant entry.
     */
    suspend fun refreshLiveAccountApproval(): CaptainApprovalSnapshot {
        val currentAuth = authBackend.currentUser()
            ?: return CaptainAccountApprovalResolver.networkFailure(
                message = "انتهت جلسة تسجيل الدخول. يرجى تسجيل الدخول مجدداً."
            ).also { _accountApprovalState.value = null }

        return try {
            val rawDoc = authBackend.getCaptainDocFromServer(currentAuth.uid)
            val validDoc = validateCaptainDoc(rawDoc, currentAuth.uid).getOrElse { err ->
                revokeLocalOperationalSession(uid = currentAuth.uid, docData = null, authUser = currentAuth)
                val blocked = CaptainApprovalSnapshot(
                    status = CaptainAccountStatus.BLOCKED,
                    isApproved = false,
                    rejectionReason = err.message.orEmpty(),
                    captainName = currentAuth.displayName,
                    email = currentAuth.email
                )
                _accountApprovalState.value = blocked
                return blocked
            }
            handleLiveApprovalDocUpdate(
                uid = currentAuth.uid,
                docData = validDoc,
                authEmail = currentAuth.email,
                authDisplayName = currentAuth.displayName
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("CaptainRepository", "Server approval refresh failed (fail-closed): ${e.message}")
            val current = captainDao.getCaptain()
            val failure = CaptainAccountApprovalResolver.networkFailure(
                message = "تعذر الاتصال بالخادم للتحقق من حالة الحساب. يرجى التأكد من الإنترنت والمحاولة مرة أخرى.",
                fallbackEmail = currentAuth.email.ifBlank { current?.email.orEmpty() },
                fallbackName = currentAuth.displayName.ifBlank { current?.fullName.orEmpty() }
            )
            _accountApprovalState.value = failure
            failure
        }
    }

    /**
     * Processes a live Firestore `captains/{uid}` snapshot update (from SnapshotListener or refresh).
     * Immediately activates listeners when transitioning to APPROVED, or revokes all local
     * operational access immediately when transitioning to PENDING / REJECTED / SUSPENDED / BLOCKED.
     */
    suspend fun handleLiveApprovalDocUpdate(
        uid: String,
        docData: Map<String, Any?>?,
        authEmail: String = "",
        authDisplayName: String = "",
        isServerConfirmedDoc: Boolean = true
    ): CaptainApprovalSnapshot {
        val current = captainDao.getCaptain()
        val resolvedEmail = authEmail.ifBlank { current?.email.orEmpty() }
        val resolvedName = authDisplayName.ifBlank { current?.fullName.orEmpty() }
        val snapshot = CaptainAccountApprovalResolver.resolve(
            docData = docData,
            fallbackEmail = resolvedEmail,
            fallbackName = resolvedName
        )
        _accountApprovalState.value = snapshot

        if (snapshot.canEnterApp && docData != null) {
            val restoredSession = if (isServerConfirmedDoc) {
                reconcileServerPresenceFromDoc(
                    uid = uid,
                    docData = docData,
                    shifts = getShifts()
                )
            } else {
                _serverConfirmedWorkingSession.value?.takeIf { it.uid == uid }
            }
            val refreshed = buildCaptainEntityFromDoc(
                uid = uid,
                authEmail = resolvedEmail,
                authDisplayName = resolvedName,
                docData = docData,
                fallbackPhone = current?.phoneNumber.orEmpty(),
                isLoggedIn = true,
                isOnline = restoredSession != null
            )
            captainDao.insertOrUpdate(refreshed)
            if (restoredSession?.isConfirmedWorking == true) {
                startRealtimeOrdersListener()
            } else {
                stopRealtimeOrdersListener()
            }
            if (!hasTestBackendOverride) {
                syncFcmToken()
                startRealtimeShiftListener()
            }
        } else {
            revokeLocalOperationalSession(
                uid = uid,
                docData = docData,
                authUser = AuthUserSnapshot(
                    uid = uid,
                    email = resolvedEmail,
                    isEmailVerified = true,
                    displayName = resolvedName
                )
            )
        }
        return snapshot
    }

    private suspend fun revokeLocalOperationalSession(
        uid: String,
        docData: Map<String, Any?>?,
        authUser: AuthUserSnapshot
    ) {
        _serverConfirmedWorkingSession.value = null
        stopRealtimeOrdersListener()
        stopRealtimeShiftListener()
        orderDao.deleteAllPendingOrders()
        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
        if (docData != null) {
            val current = captainDao.getCaptain()
            val lockedEntity = buildCaptainEntityFromDoc(
                uid = uid,
                authEmail = authUser.email,
                authDisplayName = authUser.displayName,
                docData = docData,
                fallbackPhone = current?.phoneNumber.orEmpty(),
                isLoggedIn = false,
                isOnline = false
            )
            captainDao.insertOrUpdate(lockedEntity)
        } else {
            captainDao.updateLoginState(false)
            captainDao.updateOnlineStatus(false)
        }
    }

    /**
     * Uploads Captain Personal Photo to Firebase Storage ("captains/{captainId}/profile/profile.jpg"),
     * saves "profileImagePath" in Firestore captains/{captainId}, and updates Room ONLY after both succeed.
     */
    suspend fun uploadAndSaveProfilePhoto(
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val current = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: current?.uid?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع الصورة الشخصية"))

        val uploadResult = storageRepository.uploadCaptainProfileImage(
            captainId = uid,
            imageUri = imageUri,
            onStateChange = onStateChange
        )
        val storagePath = uploadResult.getOrElse { return Result.failure(it) }

        return try {
            cloudMetadataPort.saveCaptainImageMetadata(
                uid = uid,
                fields = mapOf(
                    "profileImagePath" to storagePath,
                    "profileImageUpdatedAt" to FieldValue.serverTimestamp()
                )
            )
            val updated = (current ?: CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن البطل",
                phoneNumber = "",
                isLoggedIn = true
            )).copy(
                uid = uid,
                profileImagePath = storagePath,
                avatarUri = storagePath
            )
            captainDao.insertOrUpdate(updated)
            Result.success(storagePath)
        } catch (e: Exception) {
            val errMsg = "تم رفع الصورة لكن تعذر حفظ المسار في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            onStateChange(ImageUploadState.Failed(errMsg))
            Result.failure(IllegalStateException(errMsg, e))
        }
    }

    /**
     * Uploads National ID Front ("captains/{captainId}/documents/id_front.jpg") privately to Firebase Storage,
     * saves "idFrontImagePath" in Firestore captains/{captainId}, and NEVER marks ID complete until BOTH sides succeed.
     */
    suspend fun uploadAndSaveIdFront(
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val current = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: current?.uid?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع البطاقة الشخصية"))
        if (current?.effectiveIdFrontImage?.isNotBlank() == true) {
            return Result.failure(
                IllegalStateException("تم قفل الوجه الأمامي للبطاقة بعد التوثيق ولا يمكن تعديله. لتحديث بيانات البطاقة الشخصية، يرجى التواصل مع الدعم الفني.")
            )
        }

        val uploadResult = storageRepository.uploadCaptainIdFrontImage(
            captainId = uid,
            imageUri = imageUri,
            onStateChange = onStateChange
        )
        val storagePath = uploadResult.getOrElse { return Result.failure(it) }

        return try {
            val hasBack = current?.effectiveIdBackImage?.isNotBlank() == true
            cloudMetadataPort.saveCaptainImageMetadata(
                uid = uid,
                fields = mapOf(
                    "idFrontImagePath" to storagePath,
                    "idFrontUploadedAt" to FieldValue.serverTimestamp(),
                    "idDocumentsComplete" to hasBack
                )
            )
            val updated = (current ?: CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن البطل",
                phoneNumber = "",
                isLoggedIn = true
            )).copy(
                uid = uid,
                idFrontImagePath = storagePath
            )
            captainDao.insertOrUpdate(updated)
            Result.success(storagePath)
        } catch (e: Exception) {
            val errMsg = "تعذر حفظ بيانات الوجه الأمامي للبطاقة في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            onStateChange(ImageUploadState.Failed(errMsg))
            Result.failure(IllegalStateException(errMsg, e))
        }
    }

    /**
     * Uploads National ID Back ("captains/{captainId}/documents/id_back.jpg") privately to Firebase Storage,
     * saves "idBackImagePath" in Firestore captains/{captainId}, and verifies completion of both front & back.
     */
    suspend fun uploadAndSaveIdBack(
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val current = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: current?.uid?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع البطاقة الشخصية"))
        if (current?.effectiveIdBackImage?.isNotBlank() == true) {
            return Result.failure(
                IllegalStateException("تم قفل الوجه الخلفي للبطاقة بعد التوثيق ولا يمكن تعديله. لتحديث بيانات البطاقة الشخصية، يرجى التواصل مع الدعم الفني.")
            )
        }

        val uploadResult = storageRepository.uploadCaptainIdBackImage(
            captainId = uid,
            imageUri = imageUri,
            onStateChange = onStateChange
        )
        val storagePath = uploadResult.getOrElse { return Result.failure(it) }

        return try {
            val hasFront = current?.effectiveIdFrontImage?.isNotBlank() == true
            cloudMetadataPort.saveCaptainImageMetadata(
                uid = uid,
                fields = mapOf(
                    "idBackImagePath" to storagePath,
                    "idBackUploadedAt" to FieldValue.serverTimestamp(),
                    "idDocumentsComplete" to hasFront
                )
            )
            val updated = (current ?: CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن البطل",
                phoneNumber = "",
                isLoggedIn = true
            )).copy(
                uid = uid,
                idBackImagePath = storagePath
            )
            captainDao.insertOrUpdate(updated)
            Result.success(storagePath)
        } catch (e: Exception) {
            val errMsg = "تعذر حفظ بيانات الوجه الخلفي للبطاقة في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            onStateChange(ImageUploadState.Failed(errMsg))
            Result.failure(IllegalStateException(errMsg, e))
        }
    }

    /**
     * Uploads Driving/Vehicle License ("captains/{captainId}/documents/license.jpg") privately to Firebase Storage
     * and saves "licenseImagePath" in Firestore captains/{captainId} and Room.
     */
    suspend fun uploadAndSaveLicensePhoto(
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val current = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: current?.uid?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع صورة الرخصة"))

        val uploadResult = storageRepository.uploadCaptainLicenseImage(
            captainId = uid,
            imageUri = imageUri,
            onStateChange = onStateChange
        )
        val storagePath = uploadResult.getOrElse { return Result.failure(it) }

        return try {
            cloudMetadataPort.saveCaptainImageMetadata(
                uid = uid,
                fields = mapOf(
                    "licenseImagePath" to storagePath,
                    "licenseImageUpdatedAt" to FieldValue.serverTimestamp()
                )
            )
            val updated = (current ?: CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن البطل",
                phoneNumber = "",
                isLoggedIn = true
            )).copy(
                uid = uid,
                licenseImagePath = storagePath
            )
            captainDao.insertOrUpdate(updated)
            Result.success(storagePath)
        } catch (e: Exception) {
            val errMsg = "تم رفع الرخصة لكن تعذر حفظ المسار في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            onStateChange(ImageUploadState.Failed(errMsg))
            Result.failure(IllegalStateException(errMsg, e))
        }
    }

    /**
     * Rehydrates Captain image paths from Firestore into Room (used after re-login or session restore).
     * Preserves legacy ImgBB URLs untouched while prioritizing Firebase Storage paths.
     */
    suspend fun rehydrateCaptainImagesFromCloud(
        uid: String,
        fullNameFallback: String = "كابتن البطل",
        phoneFallback: String = "",
        emailFallback: String = ""
    ): Result<CaptainEntity> {
        val meta = cloudMetadataPort.fetchCaptainImageMetadata(uid)
            ?: return Result.failure(IllegalStateException("لم يتم العثور على مستند الكابتن في السحابة"))

        val current = captainDao.getCaptain()
        val profilePath = meta["profileImagePath"].orEmpty()
        val idFrontPath = meta["idFrontImagePath"].orEmpty()
        val idBackPath = meta["idBackImagePath"].orEmpty()
        val licensePath = meta["licenseImagePath"].orEmpty()
        val legacyAvatar = meta["profileImageUrl"].orEmpty()
        val legacyId = meta["idCardImageUrl"].orEmpty()
        val legacyLicense = meta["licenseImageUrl"].orEmpty()

        val entity = (current ?: CaptainEntity(
            id = 1,
            uid = uid,
            fullName = fullNameFallback,
            phoneNumber = phoneFallback,
            email = emailFallback,
            isLoggedIn = true,
            isOnline = false
        )).copy(
            uid = uid,
            isLoggedIn = true,
            profileImagePath = profilePath,
            idFrontImagePath = idFrontPath,
            idBackImagePath = idBackPath,
            licenseImagePath = licensePath,
            avatarUri = profilePath.ifBlank { legacyAvatar },
            nationalIdUrl = legacyId,
            licenseUrl = legacyLicense
        )
        captainDao.insertOrUpdate(entity)
        return Result.success(entity)
    }

    // In-memory recovery state ONLY when Auth creation succeeded in this session
    // and rollback deleteCurrentUser() failed (e.g. network drop), enabling safe retry without duplicate accounts.
    @Volatile
    private var pendingIncompleteRegistrationUid: String? = null
    @Volatile
    private var pendingIncompleteRegistrationEmail: String? = null
    private val pendingRegistrationUploadedPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun clearPendingIncompleteRegistration() {
        pendingIncompleteRegistrationUid = null
        pendingIncompleteRegistrationEmail = null
        pendingRegistrationUploadedPaths.clear()
    }

    private suspend fun uploadRegistrationImageIfPresent(
        expectedUid: String,
        slotKey: String,
        uriString: String,
        uploader: suspend (Uri) -> Result<String>
    ): String {
        val trimmed = uriString.trim()
        if (trimmed.isBlank()) return ""

        val expectedPrefix = "captains/$expectedUid/"
        // Reuse already-confirmed Storage upload from an interrupted attempt of the SAME UID + source URI
        val cachedKey = "$expectedUid|$slotKey|$trimmed"
        val cachedPath = pendingRegistrationUploadedPaths[cachedKey]
        if (!cachedPath.isNullOrBlank() && cachedPath.startsWith(expectedPrefix)) {
            return cachedPath
        }

        if (!trimmed.startsWith("content://") && !trimmed.startsWith("file://")) {
            if (trimmed.startsWith(expectedPrefix)) {
                return trimmed
            }
            throw IllegalArgumentException("مسار الصورة غير صالح ولا يمكن حفظه قبل رفعه بنجاح إلى Firebase Storage")
        }

        val parsed = Uri.parse(trimmed)
        val uploadedPath = uploader(parsed).getOrThrow().trim()
        require(uploadedPath.startsWith(expectedPrefix)) {
            "فشل التحقق من مسار الصورة المرفوعة داخل مجلد الكابتن"
        }
        pendingRegistrationUploadedPaths[cachedKey] = uploadedPath
        return uploadedPath
    }

    /**
     * Register new captain with ordered, atomic-safe flow:
     * 1. Validate inputs & lengths against Firestore rules bounds
     * 2. Create Firebase Auth user (or safely resume an unrolled-back incomplete session attempt) & refresh ID token
     * 3. Verify real authenticated UID
     * 4. Upload captain documents/photos strictly to Firebase Storage under captains/{uid}/...
     * 5. Create ONE document ONLY in Cloud Firestore: captains/{uid} with real Storage paths & accountType = "captain"
     *    (NEVER creates or edits documents in merchants, restaurants, drivers, or users)
     * 6. Send Firebase Email Verification & persist local Room cache (isLoggedIn = false until verified)
     */
    suspend fun registerCaptainWithEmailVerification(
        fullName: String,
        phone: String,
        email: String,
        pass: String,
        address: String = "",
        avatarPresetIndex: Int = 0,
        avatarUri: String = "",
        vehicleType: String = "سكوتر / دراجة نارية",
        nationalIdUri: String = "",
        idBackUri: String = "",
        licenseUri: String = ""
    ): Result<String> {
        val trimmedEmail = PhoneAuthRegistry.cleanInvisibleChars(email)
        val trimmedPhone = PhoneAuthRegistry.normalizeEgyptianPhone(phone)
        val trimmedName = fullName.trim()
        val trimmedAddress = address.trim()
        val trimmedVehicle = vehicleType.trim().ifBlank { "سكوتر / دراجة نارية" }

        // Step 1: Defensive validation aligned 1:1 with firestore.rules bounds
        if (trimmedName.isBlank() || trimmedName.length > 120) {
            return Result.failure(IllegalArgumentException("يرجى إدخال اسم كابتن صحيح (بحد أقصى 120 حرفاً)"))
        }
        if (trimmedEmail.isBlank() || trimmedEmail.length > 160) {
            return Result.failure(IllegalArgumentException("يرجى إدخال بريد إلكتروني صحيح"))
        }
        if (trimmedPhone.length < 8 || trimmedPhone.length > 32) {
            return Result.failure(IllegalArgumentException("يرجى إدخال رقم هاتف صحيح"))
        }
        if (trimmedAddress.length > 300) {
            return Result.failure(IllegalArgumentException("العنوان طويل جداً (الحد الأقصى 300 حرف)"))
        }
        if (trimmedVehicle.length > 80) {
            return Result.failure(IllegalArgumentException("نوع المركبة غير صالح"))
        }
        if ((nationalIdUri.isNotBlank() && idBackUri.isBlank()) || (nationalIdUri.isBlank() && idBackUri.isNotBlank())) {
            return Result.failure(IllegalArgumentException("يرجى رفع الوجه الأمامي والوجه الخلفي للبطاقة الشخصية معاً لاكتمال المستند"))
        }

        var createdAuthUser = false
        var activeUid: String? = null

        suspend fun rollbackOrMarkResumable(uid: String) {
            if (!createdAuthUser && uid != pendingIncompleteRegistrationUid) return
            // Remove this attempt's uploads first: Storage rules allow it only while the Auth user
            // still exists and has no captains doc. Forget only the files really deleted, so a resumed
            // attempt re-uploads those and still reuses any upload that could not be removed.
            val uploadKeyPrefix = "$uid|"
            val uploadedForUid = pendingRegistrationUploadedPaths.filterKeys { it.startsWith(uploadKeyPrefix) }
            if (uploadedForUid.isNotEmpty()) {
                val deleted = runCatching {
                    storageRepository.deleteRegistrationUploads(uid, uploadedForUid.values.toList())
                }.getOrDefault(emptySet())
                uploadedForUid.filterValues { it in deleted }.keys.forEach { pendingRegistrationUploadedPaths.remove(it) }
            }
            val deleteResult = runCatching { authBackend.deleteCurrentUser() }
            if (deleteResult.isSuccess && authBackend.currentUser()?.uid != uid) {
                clearPendingIncompleteRegistration()
            } else {
                // Rollback could not complete (e.g. network lost): keep safe in-memory resume anchor
                pendingIncompleteRegistrationUid = uid
                pendingIncompleteRegistrationEmail = trimmedEmail.lowercase()
            }
        }

        try {
            // Step 2: Obtain Firebase Auth UID (either resume unrolled-back incomplete attempt for same email, or create new)
            val currentSessionUser = authBackend.currentUser()
            val canResumeIncompleteAttempt =
                !pendingIncompleteRegistrationUid.isNullOrBlank() &&
                pendingIncompleteRegistrationEmail.equals(trimmedEmail, ignoreCase = true) &&
                currentSessionUser != null &&
                currentSessionUser.uid == pendingIncompleteRegistrationUid &&
                !currentSessionUser.isEmailVerified &&
                runCatching { authBackend.getCaptainDoc(currentSessionUser.uid) }.getOrNull() == null

            val firebaseUser = if (canResumeIncompleteAttempt) {
                Log.i("CaptainRepository", "RESUMING INCOMPLETE CAPTAIN REGISTRATION - UID: ${currentSessionUser!!.uid}")
                currentSessionUser
            } else {
                clearPendingIncompleteRegistration()
                authBackend.createUserWithEmailAndPassword(trimmedEmail, pass).also {
                    createdAuthUser = true
                }
            }

            // Force-refresh ID token immediately so Firebase Storage & Cloud Firestore carry fresh auth credentials
            runCatching { authBackend.refreshIdToken() }

            val uid = firebaseUser.uid.trim()
            if (uid.isBlank()) {
                return Result.failure(IllegalStateException("تعذر الحصول على معرّف المستخدم (UID) من Firebase Authentication"))
            }
            activeUid = uid
            Log.i("CaptainRepository", "AUTH SUCCESS - CAPTAIN UID: $uid")

            // Step 3: Upload captain images strictly to Firebase Storage under captains/{uid}/
            // Perform uploads BEFORE finalizing Firestore doc so no unbacked or local-preview URI is ever stored.
            val profilePath = uploadRegistrationImageIfPresent(uid, "profile", avatarUri) {
                storageRepository.uploadCaptainProfileImage(uid, it)
            }
            val idFrontPath = uploadRegistrationImageIfPresent(uid, "id_front", nationalIdUri) {
                storageRepository.uploadCaptainIdFrontImage(uid, it)
            }
            val idBackPath = uploadRegistrationImageIfPresent(uid, "id_back", idBackUri) {
                storageRepository.uploadCaptainIdBackImage(uid, it)
            }
            val licensePath = uploadRegistrationImageIfPresent(uid, "license", licenseUri) {
                storageRepository.uploadCaptainLicenseImage(uid, it)
            }

            // Step 4: Create single document ONLY in captains/{uid} with canonical accountType + role + real Storage paths
            val now = System.currentTimeMillis()
            val captainDocData = buildNewCaptainDocumentData(
                uid = uid,
                name = trimmedName,
                email = trimmedEmail,
                phone = trimmedPhone,
                profilePath = profilePath,
                idFrontPath = idFrontPath,
                idBackPath = idBackPath,
                licensePath = licensePath,
                address = trimmedAddress,
                vehicleType = trimmedVehicle,
                avatarPresetIndex = avatarPresetIndex,
                createdAtMs = now
            )

            try {
                authBackend.createCaptainDoc(uid, captainDocData)
                Log.i("CaptainRepository", "FIRESTORE CAPTAIN CREATE SUCCESS: captains/$uid")
            } catch (fsEx: Exception) {
                val firestoreCode = (fsEx as? FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
                Log.e("CaptainRepository", "FIRESTORE CAPTAIN CREATE ERROR - code: $firestoreCode, message: ${fsEx.message}", fsEx)
                rollbackOrMarkResumable(uid)
                return Result.failure(
                    Exception("فشل إنشاء ملف الكابتن في Firestore: [$firestoreCode] ${fsEx.localizedMessage ?: fsEx.message}")
                )
            }

            // Registration succeeded atomically in Auth + Storage + Firestore — clear any recovery state
            clearPendingIncompleteRegistration()

            // Step 5: Send Firebase Email Verification & update Auth display name
            try {
                authBackend.sendEmailVerification()
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not send verification email: ${e.message}")
            }

            try {
                authBackend.updateDisplayName(trimmedName)
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not set displayName: ${e.message}")
            }

            // Step 6: Save locally in Room with isLoggedIn = false until email is verified
            val newCaptain = CaptainEntity(
                id = 1,
                uid = uid,
                fullName = trimmedName,
                phoneNumber = trimmedPhone,
                email = trimmedEmail,
                password = "",
                avatarUri = profilePath,
                avatarPresetIndex = avatarPresetIndex,
                profileImagePath = profilePath,
                idFrontImagePath = idFrontPath,
                idBackImagePath = idBackPath,
                licenseImagePath = licensePath,
                address = trimmedAddress,
                accountType = "captain",
                role = "captain",
                status = "pending",
                availabilityStatus = "offline",
                shiftStatus = "outside_shift",
                vehicleType = trimmedVehicle,
                licensePlate = "ب ط ل 1 0 0",
                rating = 5.0f,
                totalOrdersCount = 0,
                walletBalance = 0.0,
                isOnline = false,
                isLoggedIn = false
            )
            captainDao.insertOrUpdate(newCaptain)
            PhoneAuthRegistry.savePhoneEmailMapping(context, trimmedPhone, trimmedEmail)
            _accountApprovalState.value = CaptainApprovalSnapshot(
                status = CaptainAccountStatus.PENDING,
                isApproved = false,
                captainName = trimmedName,
                email = trimmedEmail,
                checkedFromServer = true
            )

            return Result.success(trimmedEmail)
        } catch (e: FirebaseAuthUserCollisionException) {
            // Strictly reject email collision — NEVER reuse existing account or overwrite/merge captains doc
            return Result.failure(
                Exception("هذا البريد الإلكتروني مسجل بالفعل. لا يمكن إنشاء حساب كابتن جديد بنفس البريد الإلكتروني.")
            )
        } catch (e: FirebaseAuthWeakPasswordException) {
            return Result.failure(Exception("كلمة السر ضعيفة جداً. يجب أن تتكون من 6 خانات على الأقل."))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            return Result.failure(Exception("صيغة البريد الإلكتروني غير صحيحة. يرجى التأكد من كتابة البريد بشكل صحيح."))
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Registration failure", e)
            activeUid?.let { rollbackOrMarkResumable(it) }
            val msg = e.localizedMessage ?: "حدث خطأ أثناء التسجيل"
            return Result.failure(Exception("فشل إنشاء الحساب: $msg"))
        }
    }

    /**
     * Reloads Firebase Auth user and checks isEmailVerified strictly against Firebase Authentication.
     * Upon verified == true, fetches captains/{uid} from Firestore server and evaluates Admin Approval.
     * NEVER grants Main access or sets isLoggedIn = true unless accountStatus == approved && isApproved == true.
     */
    suspend fun checkEmailVerification(): Result<Boolean> {
        return try {
            val reloaded = authBackend.reloadCurrentUser()
                ?: return Result.failure(Exception("لم يتم العثور على جلسة مستخدم نشطة. يرجى تسجيل الدخول."))

            if (!reloaded.isEmailVerified) {
                captainDao.updateLoginState(false)
                return Result.success(false)
            }

            val rawDoc = authBackend.getCaptainDocFromServer(reloaded.uid)
            val validDoc = validateCaptainDoc(rawDoc, reloaded.uid).getOrElse { err ->
                authBackend.signOut()
                _accountApprovalState.value = null
                captainDao.updateLoginState(false)
                return Result.failure(err)
            }

            val approvalSnapshot = CaptainAccountApprovalResolver.resolve(
                docData = validDoc,
                fallbackEmail = reloaded.email,
                fallbackName = reloaded.displayName
            )
            _accountApprovalState.value = approvalSnapshot

            val current = captainDao.getCaptain()
            if (!approvalSnapshot.canEnterApp) {
                val pendingCaptain = buildCaptainEntityFromDoc(
                    uid = reloaded.uid,
                    authEmail = reloaded.email,
                    authDisplayName = reloaded.displayName,
                    docData = validDoc,
                    fallbackPhone = current?.phoneNumber.orEmpty(),
                    isLoggedIn = false,
                    isOnline = false
                )
                captainDao.insertOrUpdate(pendingCaptain)

                runCatching {
                    authBackend.updateCaptainDoc(
                        reloaded.uid,
                        mapOf(
                            "emailVerified" to true,
                            "isEmailVerified" to true,
                            "isLoggedIn" to false,
                            "isOnline" to false,
                            "availabilityStatus" to "offline",
                            "presenceStatus" to "offline",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                return Result.success(true)
            }

            _serverConfirmedWorkingSession.value = null
            stopRealtimeOrdersListener()

            val verifiedCaptain = buildCaptainEntityFromDoc(
                uid = reloaded.uid,
                authEmail = reloaded.email,
                authDisplayName = reloaded.displayName,
                docData = validDoc,
                fallbackPhone = current?.phoneNumber.orEmpty(),
                isLoggedIn = true,
                isOnline = false
            )
            captainDao.insertOrUpdate(verifiedCaptain)

            runCatching {
                authBackend.updateCaptainDoc(
                    reloaded.uid,
                    mapOf(
                        "emailVerified" to true,
                        "isEmailVerified" to true,
                        "isLoggedIn" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            if (!hasTestBackendOverride) {
                syncFcmToken()
                startRealtimeShiftListener()
            }

            Result.success(true)
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Error checking verification/approval", e)
            captainDao.updateLoginState(false)
            Result.failure(Exception(e.localizedMessage ?: "تعذر التحقق من حالة البريد الإلكتروني أو موافقة الإدارة"))
        }
    }

    suspend fun resendVerificationEmail(): Result<String> {
        return try {
            val user = authBackend.currentUser()
                ?: return Result.failure(Exception("تعذر العثور على حسابك لإعادة الإرسال. يرجى تسجيل الدخول."))
            authBackend.sendEmailVerification()
            Result.success("تم إرسال رابط تأكيد جديد إلى ${user.email}")
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Error resending email", e)
            Result.failure(Exception(e.localizedMessage ?: "تعذر إعادة إرسال رابط التأكيد حالياً"))
        }
    }

    private var lastUploadedLocation: Location? = null
    private var lastLocationUploadTimestamp: Long = 0L
    private var lastUploadedPresence: String? = null
    @Volatile
    private var pendingOfflineServerSync: Boolean = false

    /**
     * Canonical shift instance key matching Backend `updateCaptainShiftPresence` verbatim:
     * `yyyy-MM-dd|dayKey|HHmm` in `Africa/Cairo`.
     * - Overnight shifts active after midnight resolve to yesterday's start date & dayKey.
     * - Midnight-crossing upcoming shifts resolve to tomorrow's start date & dayKey.
     */
    internal fun buildShiftInstanceKey(
        shift: ShiftEntity,
        nowCal: Calendar = ShiftManager.getCurrentCairoCalendar()
    ): String {
        val calendar = (nowCal.clone() as Calendar).apply {
            timeZone = ShiftManager.CAIRO_TIME_ZONE
        }
        val todayDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        if (shift.dayOfWeek != todayDayOfWeek) {
            val yesterdayDayOfWeek = if (todayDayOfWeek == Calendar.SUNDAY) Calendar.SATURDAY else todayDayOfWeek - 1
            val tomorrowDayOfWeek = if (todayDayOfWeek == Calendar.SATURDAY) Calendar.SUNDAY else todayDayOfWeek + 1
            when (shift.dayOfWeek) {
                yesterdayDayOfWeek -> calendar.add(Calendar.DAY_OF_YEAR, -1)
                tomorrowDayOfWeek -> calendar.add(Calendar.DAY_OF_YEAR, 1)
                else -> calendar.add(Calendar.DAY_OF_YEAR, -1)
            }
        }
        val dayKey = when (shift.dayOfWeek) {
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            else -> "unknown"
        }
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = ShiftManager.CAIRO_TIME_ZONE
        }.format(calendar.time)
        val startMinutes = ShiftManager.parseTimeToMinutes(shift.startTime)
        val startHour = (startMinutes / 60).coerceIn(0, 23)
        val startMinute = (startMinutes % 60).coerceIn(0, 59)
        val hhmm = String.format(Locale.US, "%02d%02d", startHour, startMinute)
        return "$dateKey|$dayKey|$hhmm"
    }

    /**
     * Evaluates a server-read `captains/{uid}` document against the captain's scheduled Cairo shift
     * and updates `_serverConfirmedWorkingSession` strictly when server fields and `shiftKey` match.
     */
    internal fun reconcileServerPresenceFromDoc(
        uid: String,
        docData: Map<String, Any?>,
        shifts: List<ShiftEntity>,
        nowCal: Calendar = ShiftManager.getCurrentCairoCalendar()
    ): ServerConfirmedWorkingSession? {
        val status = ShiftManager.calculateShiftStatus(shifts, nowCal)
        val serverIsOnline = docData["isOnline"] == true
        val workStatus = docData["workStatus"]?.toString()?.trim()?.lowercase().orEmpty()
        val readyConfirmed = docData["readyConfirmed"] == true
        val readyShiftKey = docData["readyShiftKey"]?.toString()?.trim().orEmpty()
        val workingShiftKey = docData["workingShiftKey"]?.toString()?.trim().orEmpty()

        val resolved: ServerConfirmedWorkingSession? = when (status) {
            is ShiftStatus.Active -> {
                val expectedShiftKey = buildShiftInstanceKey(status.shift, nowCal)
                when {
                    serverIsOnline &&
                        workStatus == "working" &&
                        readyConfirmed &&
                        readyShiftKey == expectedShiftKey &&
                        workingShiftKey == expectedShiftKey -> {
                        ServerConfirmedWorkingSession(
                            uid = uid,
                            workStatus = "working",
                            readyConfirmed = true,
                            readyShiftKey = readyShiftKey,
                            workingShiftKey = workingShiftKey
                        )
                    }
                    serverIsOnline &&
                        workStatus == "ready" &&
                        readyConfirmed &&
                        readyShiftKey == expectedShiftKey -> {
                        ServerConfirmedWorkingSession(
                            uid = uid,
                            workStatus = "ready",
                            readyConfirmed = true,
                            readyShiftKey = readyShiftKey,
                            workingShiftKey = ""
                        )
                    }
                    else -> null
                }
            }
            is ShiftStatus.UpcomingSoon -> {
                val inReadyWindow = status.millisUntilStart in 1L..(30L * 60_000L)
                val expectedShiftKey = buildShiftInstanceKey(status.shift, nowCal)
                if (inReadyWindow &&
                    serverIsOnline &&
                    workStatus == "ready" &&
                    readyConfirmed &&
                    readyShiftKey == expectedShiftKey
                ) {
                    ServerConfirmedWorkingSession(
                        uid = uid,
                        workStatus = "ready",
                        readyConfirmed = true,
                        readyShiftKey = readyShiftKey,
                        workingShiftKey = ""
                    )
                } else {
                    null
                }
            }
            else -> null
        }

        _serverConfirmedWorkingSession.value = resolved
        return resolved
    }

    suspend fun hasConfirmedShiftReadiness(): Boolean {
        val user = if (hasTestBackendOverride) {
            authBackendOverride?.currentUser()
        } else {
            runCatching { firebaseAuth.currentUser }.getOrNull()?.let {
                AuthUserSnapshot(it.uid, it.email.orEmpty(), it.isEmailVerified, it.displayName.orEmpty())
            }
        } ?: return false
        if (!user.isEmailVerified) return false
        val active = ShiftManager.calculateShiftStatus(getShifts()) as? ShiftStatus.Active ?: return false
        val expectedShiftKey = buildShiftInstanceKey(active.shift)
        if (authBackendOverride != null) {
            val doc = authBackendOverride.getCaptainDocFromServer(user.uid) ?: return false
            val ready = doc["workStatus"] == "ready" &&
                doc["readyConfirmed"] == true &&
                doc["readyShiftKey"] == expectedShiftKey
            if (ready) {
                _serverConfirmedWorkingSession.value = ServerConfirmedWorkingSession(
                    uid = user.uid,
                    workStatus = "ready",
                    readyConfirmed = true,
                    readyShiftKey = expectedShiftKey,
                    workingShiftKey = ""
                )
            }
            return ready
        }
        val snap = withTimeout(20_000L) {
            firestore.collection("captains").document(user.uid).get(Source.SERVER).await()
        }
        val ready = snap.exists() &&
            snap.getString("workStatus") == "ready" &&
            snap.getBoolean("readyConfirmed") == true &&
            snap.getString("readyShiftKey") == expectedShiftKey
        if (ready) {
            _serverConfirmedWorkingSession.value = ServerConfirmedWorkingSession(
                uid = user.uid,
                workStatus = "ready",
                readyConfirmed = true,
                readyShiftKey = expectedShiftKey,
                workingShiftKey = ""
            )
        }
        return ready
    }

    /**
     * Checks whether the captain is eligible for live GPS location tracking, distinguishing
     * confirmed ineligibility (e.g. shift ended, offline, unapproved) from transient network/timeout errors.
     */
    suspend fun checkLocationTrackingEligibility(): LocationEligibilityResult {
        if (_accountApprovalState.value != null && !isCurrentSessionApproved) {
            _serverConfirmedWorkingSession.value = null
            stopRealtimeOrdersListener()
            return LocationEligibilityResult.Ineligible("account_not_approved")
        }
        val user = if (hasTestBackendOverride) {
            authBackendOverride?.currentUser()
        } else {
            runCatching { firebaseAuth.currentUser }.getOrNull()?.let {
                AuthUserSnapshot(it.uid, it.email.orEmpty(), it.isEmailVerified, it.displayName.orEmpty())
            }
        } ?: run {
            _serverConfirmedWorkingSession.value = null
            stopRealtimeOrdersListener()
            return LocationEligibilityResult.Ineligible("unauthenticated")
        }
        if (!user.isEmailVerified) {
            _serverConfirmedWorkingSession.value = null
            stopRealtimeOrdersListener()
            return LocationEligibilityResult.Ineligible("email_not_verified")
        }
        val captain = captainDao.getCaptain()
            ?: run {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                return LocationEligibilityResult.Ineligible("no_local_captain")
            }
        if (!captain.isOnline) {
            _serverConfirmedWorkingSession.value = null
            stopRealtimeOrdersListener()
            return LocationEligibilityResult.Ineligible("captain_offline")
        }
        val active = ShiftManager.calculateShiftStatus(getShifts()) as? ShiftStatus.Active
            ?: run {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                return LocationEligibilityResult.Ineligible("no_active_shift")
            }
        val expectedShiftKey = buildShiftInstanceKey(active.shift)

        if (authBackendOverride != null) {
            val doc = try {
                authBackendOverride.getCaptainDocFromServer(user.uid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                return LocationEligibilityResult.NetworkError(e)
            } ?: run {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                return LocationEligibilityResult.Ineligible("captain_doc_missing")
            }

            val lastServerLocationTimestamp = (doc["lastLocationTimestamp"] as? Number)?.toLong() ?: 0L
            if (lastServerLocationTimestamp > lastLocationUploadTimestamp) {
                lastLocationUploadTimestamp = lastServerLocationTimestamp
            }
            val eligible = doc["isOnline"] == true &&
                doc["workStatus"] == "working" &&
                doc["readyConfirmed"] == true &&
                doc["readyShiftKey"] == expectedShiftKey &&
                doc["workingShiftKey"] == expectedShiftKey
            return if (eligible) {
                _serverConfirmedWorkingSession.value = ServerConfirmedWorkingSession(
                    uid = user.uid,
                    workStatus = "working",
                    readyConfirmed = true,
                    readyShiftKey = expectedShiftKey,
                    workingShiftKey = expectedShiftKey
                )
                LocationEligibilityResult.Eligible
            } else {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                LocationEligibilityResult.Ineligible("server_not_working_for_active_shift")
            }
        }

        // One server read per five-minute GPS callback; never trust a stale local online flag.
        val serverCaptain = try {
            withTimeout(20_000L) {
                firestore.collection("captains").document(user.uid).get(Source.SERVER).await()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return LocationEligibilityResult.NetworkError(e)
        }

        val lastServerLocationTimestamp = serverCaptain.getLong("lastLocationTimestamp") ?: 0L
        if (lastServerLocationTimestamp > lastLocationUploadTimestamp) {
            lastLocationUploadTimestamp = lastServerLocationTimestamp
        }
        val serverIsOnline = serverCaptain.getBoolean("isOnline") == true
        val strictEligible = serverCaptain.exists() &&
            serverIsOnline &&
            serverCaptain.getString("workStatus") == "working" &&
            serverCaptain.getBoolean("readyConfirmed") == true &&
            serverCaptain.getString("readyShiftKey") == expectedShiftKey &&
            serverCaptain.getString("workingShiftKey") == expectedShiftKey
        return if (strictEligible) {
            _serverConfirmedWorkingSession.value = ServerConfirmedWorkingSession(
                uid = user.uid,
                workStatus = "working",
                readyConfirmed = true,
                readyShiftKey = expectedShiftKey,
                workingShiftKey = expectedShiftKey
            )
            LocationEligibilityResult.Eligible
        } else {
            _serverConfirmedWorkingSession.value = null
            stopRealtimeOrdersListener()
            LocationEligibilityResult.Ineligible("server_not_working_for_active_shift")
        }
    }

    suspend fun isEligibleForLocationTracking(): Boolean {
        return checkLocationTrackingEligibility() is LocationEligibilityResult.Eligible
    }

    suspend fun updateCaptainGpsLocationDetailed(location: Location): LocationUploadResult {
        when (val eligibility = checkLocationTrackingEligibility()) {
            is LocationEligibilityResult.Eligible -> Unit
            is LocationEligibilityResult.NetworkError -> {
                return LocationUploadResult.TransientFailure(eligibility.cause)
            }
            is LocationEligibilityResult.Ineligible -> {
                return LocationUploadResult.ConfirmedIneligible(
                    reason = eligibility.reason,
                    shouldMarkOffline = eligibility.reason == "no_active_shift"
                )
            }
        }

        val uid = resolveCurrentCaptainUid()
        if (uid.isNullOrBlank()) {
            return LocationUploadResult.ConfirmedIneligible(
                reason = "unauthenticated",
                shouldMarkOffline = false
            )
        }

        val now = System.currentTimeMillis()
        val timeDiff = now - lastLocationUploadTimestamp
        // Fixed five-minute Firestore cadence. Movement and trip-state changes never bypass it.
        if (lastLocationUploadTimestamp > 0L && timeDiff < GpsConfig.FIRESTORE_HEARTBEAT_INTERVAL_MS) {
            return LocationUploadResult.Success
        }

        val activeOrder = activeOrderFlow.firstOrNull()
        val presenceValue = if (activeOrder != null) "on_trip" else "working"
        val locationData = hashMapOf<String, Any>(
            "location" to GeoPoint(location.latitude, location.longitude),
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy.toDouble(),
            "bearing" to location.bearing.toDouble(),
            "speed" to location.speed.toDouble(),
            "isOnline" to true,
            "presenceStatus" to presenceValue,
            "availabilityStatus" to presenceValue,
            "lastLocationUpdate" to FieldValue.serverTimestamp(),
            "lastLocationTimestamp" to now,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            if (authBackendOverride != null) {
                authBackendOverride.updateCaptainDoc(uid, locationData)
            } else {
                withTimeout(PRESENCE_CALLABLE_TIMEOUT_MS) {
                    firestore.collection("captains").document(uid).update(locationData).await()
                }
            }
            lastUploadedLocation = location
            lastLocationUploadTimestamp = now
            lastUploadedPresence = presenceValue
            Log.d("CaptainRepository", "Uploaded five-minute GPS location for working captain $uid")
            LocationUploadResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Error uploading location to Firestore", e)
            LocationUploadResult.TransientFailure(e)
        }
    }

    suspend fun updateCaptainGpsLocation(location: Location): Boolean {
        return when (updateCaptainGpsLocationDetailed(location)) {
            is LocationUploadResult.Success,
            is LocationUploadResult.TransientFailure -> true
            is LocationUploadResult.ConfirmedIneligible -> false
        }
    }

    /**
     * Retries syncing a previously deferred offline presence transition once connectivity returns.
     */
    suspend fun syncPendingOfflineStatusIfNeeded(): Boolean {
        if (!pendingOfflineServerSync) return true
        val uid = resolveCurrentCaptainUid()
        if (uid.isNullOrBlank()) {
            pendingOfflineServerSync = false
            return true
        }
        val offlineFallbackPayload = mapOf<String, Any>(
            "isOnline" to false,
            "presenceStatus" to "offline",
            "availabilityStatus" to "offline",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (hasTestBackendOverride) {
            if (authBackendOverride != null) {
                return try {
                    authBackendOverride.updateCaptainDoc(uid, offlineFallbackPayload)
                    pendingOfflineServerSync = false
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w("CaptainRepository", "Deferred offline sync retry failed in test backend: ${e.message}")
                    false
                }
            }
            pendingOfflineServerSync = false
            return true
        }

        return withContext(Dispatchers.IO) {
            val primaryError = try {
                invokeShiftPresenceCallable("offline")
                pendingOfflineServerSync = false
                return@withContext true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                e
            }
            try {
                withTimeout(FIRESTORE_OFFLINE_FALLBACK_TIMEOUT_MS) {
                    firestore.collection("captains").document(uid)
                        .update(offlineFallbackPayload)
                        .await()
                }
                pendingOfflineServerSync = false
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fallbackErr: Exception) {
                Log.w("CaptainRepository", "Deferred offline sync retry failed: ${fallbackErr.message} (primary: ${primaryError.message})")
                false
            }
        }
    }

    suspend fun markCaptainOfflineInFirestore() {
        _serverConfirmedWorkingSession.value = null
        stopRealtimeOrdersListener()
        val uid = resolveCurrentCaptainUid() ?: return
        val offlinePayload = mapOf<String, Any>(
            "isOnline" to false,
            "presenceStatus" to "offline",
            "availabilityStatus" to "offline",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (hasTestBackendOverride && presenceCallableOverride == null) {
            if (authBackendOverride != null) {
                try {
                    authBackendOverride.updateCaptainDoc(uid, offlinePayload)
                    pendingOfflineServerSync = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    pendingOfflineServerSync = true
                    Log.w("CaptainRepository", "Test backend offline sync failed: ${e.message}")
                }
            } else {
                pendingOfflineServerSync = false
            }
            return
        }

        withContext(NonCancellable + Dispatchers.IO) {
            val primaryError = try {
                invokeShiftPresenceCallable("offline")
                pendingOfflineServerSync = false
                return@withContext
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                e
            }
            // Firestore rules allow only the fail-closed offline presence fallback from the client.
            val fallbackSucceeded = try {
                withTimeout(FIRESTORE_OFFLINE_FALLBACK_TIMEOUT_MS) {
                    firestore.collection("captains").document(uid)
                        .update(offlinePayload)
                        .await()
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            pendingOfflineServerSync = !fallbackSucceeded
            Log.w("CaptainRepository", "Server offline transition fallback used (succeeded=$fallbackSucceeded): ${primaryError.message}")
        }
    }

    /** Mirrors an admin-side status change (approval, suspension) into the local profile. */
    suspend fun applyRemoteAccountStatus(status: String) {
        captainDao.updateAccountStatus(status)
    }

    suspend fun setOnlineStatus(isOnline: Boolean) {
        if (isOnline) {
            val wasLocallyOnline = captainDao.getCaptain()?.isOnline == true
            val uid = resolveCurrentCaptainUid()
            if (uid.isNullOrBlank()) {
                captainDao.updateOnlineStatus(false)
                throw CaptainPresenceException(
                    reason = PresenceErrorReason.UNAUTHENTICATED,
                    userMessage = "انتهت صلاحية الجلسة. يرجى إعادة تسجيل الدخول ثم المحاولة مرة أخرى."
                )
            }
            if (_accountApprovalState.value != null && !isCurrentSessionApproved) {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                captainDao.updateOnlineStatus(false)
                throw CaptainPresenceException(
                    reason = PresenceErrorReason.PERMISSION_DENIED,
                    userMessage = "لا تملك صلاحية تفعيل العمل حالياً. تأكد من اعتماد حسابك من الإدارة."
                )
            }
            val activeShift = ShiftManager.calculateShiftStatus(getShifts()) as? ShiftStatus.Active
            if (activeShift == null) {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                captainDao.updateOnlineStatus(false)
                throw CaptainPresenceException(
                    reason = PresenceErrorReason.FAILED_PRECONDITION,
                    userMessage = "لا يمكنك تفعيل العمل خارج وقت الشفت المعتمد."
                )
            }
            val expectedShiftKey = buildShiftInstanceKey(activeShift.shift)

            if (hasTestBackendOverride && presenceCallableOverride == null) {
                if (authBackendOverride != null) {
                    try {
                        authBackendOverride.updateCaptainDoc(
                            uid,
                            mapOf(
                                "isOnline" to true,
                                "workStatus" to "working",
                                "readyConfirmed" to true,
                                "readyShiftKey" to expectedShiftKey,
                                "workingShiftKey" to expectedShiftKey,
                                "presenceStatus" to "working",
                                "availabilityStatus" to "working",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        pendingOfflineServerSync = false
                        captainDao.updateOnlineStatus(true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        if (isConfirmedPresenceRejection(e) || !wasLocallyOnline) {
                            _serverConfirmedWorkingSession.value = null
                            stopRealtimeOrdersListener()
                            captainDao.updateOnlineStatus(false)
                        }
                        Log.e("CaptainRepository", "Test backend working-state transition failed", e)
                        throw e
                    }
                } else {
                    pendingOfflineServerSync = false
                    captainDao.updateOnlineStatus(true)
                }
                return
            }

            try {
                invokeShiftPresenceCallable("working")
                pendingOfflineServerSync = false
                captainDao.updateOnlineStatus(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (isConfirmedPresenceRejection(e) || !wasLocallyOnline) {
                    _serverConfirmedWorkingSession.value = null
                    stopRealtimeOrdersListener()
                    captainDao.updateOnlineStatus(false)
                }
                Log.e("CaptainRepository", "Server rejected or failed working-state transition", e)
                throw e
            }
        } else {
            _serverConfirmedWorkingSession.value = null
            stopRealtimeOrdersListener()
            captainDao.updateOnlineStatus(false)
            orderDao.deleteAllPendingOrders()
            context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
            val uid = resolveCurrentCaptainUid()
            if (uid.isNullOrBlank()) {
                pendingOfflineServerSync = false
                return
            }

            val minimalOfflinePayload = mapOf<String, Any>(
                "isOnline" to false,
                "presenceStatus" to "offline",
                "availabilityStatus" to "offline",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (hasTestBackendOverride && presenceCallableOverride == null) {
                if (authBackendOverride != null) {
                    try {
                        authBackendOverride.updateCaptainDoc(uid, minimalOfflinePayload)
                        pendingOfflineServerSync = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        pendingOfflineServerSync = true
                        Log.w("CaptainRepository", "Test backend offline transition failed; marked pending sync: ${e.message}")
                    }
                } else {
                    pendingOfflineServerSync = false
                }
                return
            }

            withContext(Dispatchers.IO) {
                val callableError = try {
                    invokeShiftPresenceCallable("offline")
                    pendingOfflineServerSync = false
                    return@withContext
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    e
                }
                // Fail closed locally even if the callable is temporarily unreachable.
                val fallbackSucceeded = try {
                    withTimeout(FIRESTORE_OFFLINE_FALLBACK_TIMEOUT_MS) {
                        firestore.collection("captains").document(uid)
                            .update(minimalOfflinePayload)
                            .await()
                    }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                pendingOfflineServerSync = !fallbackSucceeded
                Log.w("CaptainRepository", "Server offline transition fallback used (succeeded=$fallbackSucceeded): ${callableError.message}")
            }
        }
    }

    /** Records the captain's explicit confirmation during the 30-minute pre-shift window. */
    suspend fun setCaptainReadyStatus(ready: Boolean) {
        val wasLocallyOnline = captainDao.getCaptain()?.isOnline == true
        val uid = resolveCurrentCaptainUid()
        if (uid.isNullOrBlank()) {
            if (ready) {
                captainDao.updateOnlineStatus(false)
                throw CaptainPresenceException(
                    reason = PresenceErrorReason.UNAUTHENTICATED,
                    userMessage = "انتهت صلاحية الجلسة. يرجى إعادة تسجيل الدخول ثم المحاولة مرة أخرى."
                )
            }
            captainDao.updateOnlineStatus(false)
            orderDao.deleteAllPendingOrders()
            context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
            pendingOfflineServerSync = false
            return
        }
        if (ready && (_accountApprovalState.value != null && !isCurrentSessionApproved)) {
            captainDao.updateOnlineStatus(false)
            throw CaptainPresenceException(
                reason = PresenceErrorReason.PERMISSION_DENIED,
                userMessage = "لا تملك صلاحية تأكيد الجاهزية حالياً. تأكد من اعتماد حسابك من الإدارة."
            )
        }
        val status = ShiftManager.calculateShiftStatus(getShifts())
        val inReadyWindow = status is ShiftStatus.UpcomingSoon &&
            status.millisUntilStart in 1L..(30L * 60_000L)
        val activeNow = status is ShiftStatus.Active
        if (ready && !inReadyWindow && !activeNow) {
            captainDao.updateOnlineStatus(false)
            throw CaptainPresenceException(
                reason = PresenceErrorReason.FAILED_PRECONDITION,
                userMessage = "زر جاهز متاح فقط قبل بداية الشفت بـ30 دقيقة أو أثناء الشفت النشط."
            )
        }
        val targetShift = when (status) {
            is ShiftStatus.Active -> status.shift
            is ShiftStatus.UpcomingSoon -> status.shift
            else -> null
        }
        val expectedShiftKey = targetShift?.let { buildShiftInstanceKey(it) }.orEmpty()

        val minimalOfflinePayload = mapOf<String, Any>(
            "isOnline" to false,
            "presenceStatus" to "offline",
            "availabilityStatus" to "offline",
            "updatedAt" to FieldValue.serverTimestamp()
        )

        if (hasTestBackendOverride && presenceCallableOverride == null) {
            if (authBackendOverride != null) {
                try {
                    val payload = if (ready) {
                        mapOf<String, Any>(
                            "isOnline" to true,
                            "workStatus" to "ready",
                            "readyConfirmed" to true,
                            "readyShiftKey" to expectedShiftKey,
                            "presenceStatus" to "ready",
                            "availabilityStatus" to "ready",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    } else {
                        minimalOfflinePayload
                    }
                    authBackendOverride.updateCaptainDoc(uid, payload)
                    pendingOfflineServerSync = false
                    captainDao.updateOnlineStatus(ready)
                    if (!ready) {
                        _serverConfirmedWorkingSession.value = null
                        stopRealtimeOrdersListener()
                        orderDao.deleteAllPendingOrders()
                        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (!ready) {
                        _serverConfirmedWorkingSession.value = null
                        stopRealtimeOrdersListener()
                        captainDao.updateOnlineStatus(false)
                        orderDao.deleteAllPendingOrders()
                        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
                        pendingOfflineServerSync = true
                    } else if (isConfirmedPresenceRejection(e) || !wasLocallyOnline) {
                        _serverConfirmedWorkingSession.value = null
                        stopRealtimeOrdersListener()
                        captainDao.updateOnlineStatus(false)
                    }
                    throw e
                }
            } else {
                pendingOfflineServerSync = false
                captainDao.updateOnlineStatus(ready)
                if (!ready) {
                    _serverConfirmedWorkingSession.value = null
                    stopRealtimeOrdersListener()
                    orderDao.deleteAllPendingOrders()
                    context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
                }
            }
            return
        }

        try {
            invokeShiftPresenceCallable(if (ready) "ready" else "offline")
            pendingOfflineServerSync = false
            captainDao.updateOnlineStatus(ready)
            if (!ready) {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                orderDao.deleteAllPendingOrders()
                context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (!ready) {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                captainDao.updateOnlineStatus(false)
                orderDao.deleteAllPendingOrders()
                context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
                val fallbackSucceeded = withContext(Dispatchers.IO) {
                    try {
                        withTimeout(FIRESTORE_OFFLINE_FALLBACK_TIMEOUT_MS) {
                            firestore.collection("captains").document(uid)
                                .update(minimalOfflinePayload)
                                .await()
                        }
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }
                pendingOfflineServerSync = !fallbackSucceeded
            } else if (isConfirmedPresenceRejection(e) || !wasLocallyOnline) {
                _serverConfirmedWorkingSession.value = null
                stopRealtimeOrdersListener()
                captainDao.updateOnlineStatus(false)
            }
            Log.e("CaptainRepository", "Unable to update server-confirmed captain readiness", e)
            throw e
        }
    }

    suspend fun logout() {
        val localDeviceFcmToken = context?.let { CaptainNotificationManager.getSavedFcmToken(it) }?.trim().orEmpty()
        val uid = resolveCurrentCaptainUid()

        _accountApprovalState.value = null
        _serverConfirmedWorkingSession.value = null
        clearHistorySyncCaches()
        stopRealtimeShiftListener()
        stopRealtimeOrdersListener()
        context?.let { ctx ->
            ShiftNotificationScheduler.cancelAllShiftAlarms(ctx)
            CaptainNotificationManager.clearNotificationState(ctx)
        }
        storageRepository.clearSessionMemoryCache()

        if (!uid.isNullOrBlank()) {
            val offlineLogoutPayload = mapOf<String, Any>(
                "isLoggedIn" to false,
                "isOnline" to false,
                "availabilityStatus" to "offline",
                "presenceStatus" to "offline",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (hasTestBackendOverride) {
                if (authBackendOverride != null) {
                    try {
                        authBackendOverride.updateCaptainDoc(uid, offlineLogoutPayload)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.w("CaptainRepository", "Logout offline sync failed in test backend: ${e.message}")
                    }
                    if (localDeviceFcmToken.isNotBlank()) {
                        try {
                            val currentServerDoc = authBackendOverride.getCaptainDoc(uid)
                            val serverFcmToken = currentServerDoc?.get("fcmToken")?.toString()?.trim().orEmpty()
                            if (serverFcmToken.isNotBlank() && serverFcmToken == localDeviceFcmToken) {
                                authBackendOverride.updateCaptainDoc(uid, mapOf("fcmToken" to ""))
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.w("CaptainRepository", "Logout FCM token clear failed in test backend: ${e.message}")
                        }
                    }
                }
            } else {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        withTimeoutOrNull(LOGOUT_NETWORK_TIMEOUT_MS) {
                            presenceFunctions()
                                .getHttpsCallable("updateCaptainShiftPresence")
                                .call(mapOf("action" to "offline"))
                                .await()
                        }
                    }
                    runCatching {
                        withTimeoutOrNull(LOGOUT_NETWORK_TIMEOUT_MS) {
                            firestore.collection("captains").document(uid)
                                .update(offlineLogoutPayload)
                                .await()
                        }
                    }
                    if (localDeviceFcmToken.isNotBlank()) {
                        runCatching {
                            withTimeoutOrNull(LOGOUT_NETWORK_TIMEOUT_MS) {
                                val doc = firestore.collection("captains").document(uid).get().await()
                                val serverToken = doc.getString("fcmToken")?.trim().orEmpty()
                                if (serverToken.isNotBlank() && serverToken == localDeviceFcmToken) {
                                    firestore.collection("captains").document(uid)
                                        .update(mapOf("fcmToken" to ""))
                                        .await()
                                }
                            }
                        }
                    }
                    if (!fcmTokenHardFailureDetected) {
                        runCatching {
                            withTimeoutOrNull(LOGOUT_NETWORK_TIMEOUT_MS) {
                                FirebaseMessaging.getInstance().deleteToken().await()
                            }
                        }
                    }
                }
            }
        }

        lastSyncedFcmUid = null
        lastSyncedFcmToken = null
        pendingOfflineServerSync = false
        try {
            authBackend.signOut()
        } catch (_: Exception) {}
        captainDao.clearAll()
        shiftDao?.clearAll()
        orderDao.clearAll()
        dailyWalletDao?.clearAll()
    }

    suspend fun updateProfile(
        fullName: String,
        phoneNumber: String,
        email: String,
        vehicleType: String,
        licensePlate: String,
        avatarUri: String = "",
        address: String? = null
    ): Result<Unit> {
        val current = captainDao.getCaptain()
        val normalizedPhone = PhoneAuthRegistry.normalizeEgyptianPhone(phoneNumber)
        val cleanEmail = PhoneAuthRegistry.cleanInvisibleChars(email).ifBlank { current?.email.orEmpty() }
        val resolvedAddress = (address ?: current?.address.orEmpty()).trim()
        if (normalizedPhone.isBlank()) {
            return Result.failure(IllegalArgumentException("رقم الهاتف مطلوب ولا يمكن أن يكون فارغاً"))
        }

        val uid = cloudMetadataPort.currentAuthUid() ?: current?.uid ?: ""
        var resolvedProfilePath = current?.profileImagePath ?: ""
        val resolvedAvatarDisplay: String

        if (avatarUri.isNotBlank() && (avatarUri.startsWith("content://") || avatarUri.startsWith("file://"))) {
            if (uid.isBlank()) {
                return Result.failure(IllegalStateException("يجب تسجيل الدخول لرفع الصورة الشخصية إلى Firebase Storage"))
            }
            val uploadRes = storageRepository.uploadCaptainProfileImage(uid, Uri.parse(avatarUri))
            val uploadedPath = uploadRes.getOrElse { return Result.failure(it) }
            resolvedProfilePath = uploadedPath
            resolvedAvatarDisplay = uploadedPath
        } else {
            resolvedAvatarDisplay = avatarUri.ifBlank { current?.effectiveProfileImage ?: "" }
            if (FirebaseStorageRepository.isFirebaseStoragePath(resolvedAvatarDisplay)) {
                resolvedProfilePath = resolvedAvatarDisplay
            }
        }

        if (uid.isNotBlank()) {
            try {
                val coreProfileMap = mutableMapOf<String, Any>(
                    "fullName" to fullName.trim(),
                    "name" to fullName.trim(),
                    "phone" to normalizedPhone,
                    "phoneNumber" to normalizedPhone,
                    "address" to resolvedAddress,
                    "vehicleType" to vehicleType.trim(),
                    "licensePlate" to licensePlate.trim(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (resolvedProfilePath.isNotBlank()) {
                    coreProfileMap["profileImagePath"] = resolvedProfilePath
                    coreProfileMap["personalImage"] = resolvedProfilePath
                    coreProfileMap["avatarUri"] = resolvedProfilePath
                    coreProfileMap["profileImageUpdatedAt"] = FieldValue.serverTimestamp()
                }
                cloudMetadataPort.saveCaptainImageMetadata(uid, coreProfileMap)
            } catch (e: Exception) {
                Log.e("CaptainRepository", "Error updating profile in Firestore", e)
                return Result.failure(e)
            }
            if (!hasTestBackendOverride) {
                runCatching { startRealtimeShiftListener() }
            }
        }

        val updated = (current ?: CaptainEntity(
            id = 1,
            uid = uid,
            fullName = fullName.trim(),
            phoneNumber = normalizedPhone,
            email = cleanEmail,
            password = "",
            avatarUri = resolvedAvatarDisplay,
            profileImagePath = resolvedProfilePath,
            address = resolvedAddress,
            vehicleType = vehicleType.trim(),
            licensePlate = licensePlate.trim(),
            rating = 5.0f,
            walletBalance = 0.0,
            isOnline = false,
            isLoggedIn = true,
            accountType = "captain",
            role = "captain",
            status = "active"
        )).copy(
            fullName = fullName.trim(),
            phoneNumber = normalizedPhone,
            email = cleanEmail,
            address = resolvedAddress,
            vehicleType = vehicleType.trim(),
            licensePlate = licensePlate.trim(),
            avatarUri = resolvedAvatarDisplay,
            profileImagePath = resolvedProfilePath
        )
        captainDao.insertOrUpdate(updated)
        PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedPhone, cleanEmail)
        return Result.success(Unit)
    }

    private suspend fun resolveCaptainUidAndPhone(): Pair<String, String> {
        val captain = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: captain?.uid?.takeIf { it.isNotBlank() }
            ?: ""
        val phone = captain?.phoneNumber.orEmpty()
        return uid to phone
    }

    suspend fun acceptOrder(orderId: Long): Result<Unit> {
        val order = orderDao.getOrderById(orderId)
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val (currentUid, currentPhone) = resolveCaptainUidAndPhone()
        if (currentUid.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول أولاً"))
        }

        // Verify captain is currently online and active before assigning/accepting any order
        val currentCaptain = captainDao.getCaptain()
        if (currentCaptain != null && !currentCaptain.isOnline) {
            Log.w("CaptainRepository", "[acceptOrder] Blocked order acceptance: captain is offline")
            return Result.failure(IllegalStateException("لا يمكنك قبول أو استلام طلبات وأنت في وضع غير متصل."))
        }
        if (_accountApprovalState.value != null && !isCurrentSessionApproved) {
            return Result.failure(IllegalStateException("حسابك غير معتمد من الإدارة حالياً ولا يمكنك قبول الطلبات."))
        }
        if (currentCaptain != null &&
            currentCaptain.status.trim().lowercase() in listOf("banned", "suspended", "disabled", "blocked", "pending", "inactive", "rejected")
        ) {
            return Result.failure(IllegalStateException("حسابك غير مفعل حالياً لاستقبال أو قبول الطلبات."))
        }

        // Check if captain already has an ongoing active order
        val activeOrder = activeOrderFlow.firstOrNull()
        if (activeOrder != null && activeOrder.id != orderId) {
            return Result.failure(IllegalStateException("لديك طلب نشط بالفعل قيد التنفيذ، لا يمكن قبول أكثر من طلب في وقت واحد."))
        }

        // Verify working shift status: captain can only accept new orders during their active shift
        val currentShifts = getShifts()
        if (currentShifts.isNotEmpty()) {
            val shiftStatus = ShiftManager.calculateShiftStatus(currentShifts)
            if (!shiftStatus.isAvailableForOrders) {
                Log.w("CaptainRepository", "[acceptOrder] Blocked order acceptance: captain is outside active working shift")
                return Result.failure(IllegalStateException("عفواً، أنت خارج ساعات العمل الرسمية ولا يمكنك قبول طلبات جديدة حالياً."))
            }
        }

        val orderDocId = resolveCleanOrderDocId(order)
        val finalRes = cloudFunctionsOrderClient.acceptDeliveryOffer(orderDocId)

        return if (finalRes.isSuccess) {
            context?.let { CaptainNotificationManager.stopIncomingOrderBell(it, orderDocId) }
            orderDao.markOrderAccepted(orderId, currentUid, System.currentTimeMillis())
            Result.success(Unit)
        } else {
            val err = finalRes.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            // Remove locally ONLY if confirmed gone/claimed on server/Firestore
            // (never when Cloud Function endpoint is 404 or on network/5xx/permission failure).
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                context?.let { CaptainNotificationManager.stopIncomingOrderBell(it, orderDocId) }
                orderDao.deleteOrderById(orderId)
            }
            Result.failure(err ?: Exception("تعذر الاتصال بالخادم لقبول الطلب. يرجى إعادة المحاولة."))
        }
    }

    private fun resolveCleanOrderDocId(order: OrderEntity): String {
        val raw = order.firestoreDocId.trim().ifBlank { order.orderNumber.trim() }
        return raw.trimStart('#').trim()
    }

    /**
     * Removes a stale or cancelled order from local Room cache when the server confirms
     * it no longer exists (404 / cancelled on backend).
     */
    suspend fun removeStaleOrder(orderId: Long) {
        val existing = orderDao.getOrderById(orderId)
        context?.let {
            CaptainNotificationManager.stopIncomingOrderBell(
                it,
                existing?.firestoreDocId.orEmpty()
            )
        }
        orderDao.deleteOrderById(orderId)
        if (existing?.status == OrderStatus.DELIVERED) {
            reconcileDailyWallets()
        }
    }

    /**
     * 2. Arrived at merchant: calls Cloud Function `captainArrivedAtMerchant` (Server-only; zero direct Firestore fallback).
     */
    suspend fun markAtStore(orderId: Long): Result<String> {
        val order = orderDao.getOrderById(orderId)
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val (currentUid, currentPhone) = resolveCaptainUidAndPhone()
        if (currentUid.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول أولاً"))
        }

        val orderDocId = resolveCleanOrderDocId(order)
        val finalRes = cloudFunctionsOrderClient.captainArrivedAtMerchant(orderDocId)

        if (finalRes.isSuccess) {
            orderDao.updateOrderStatusAndCaptain(orderId, OrderStatus.AT_STORE, currentUid)
        } else {
            val err = finalRes.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return finalRes
    }

    /**
     * 3. Confirm pickup from merchant: calls Cloud Function `confirmPickup` (Server-only; zero direct Firestore fallback).
     */
    suspend fun confirmPickup(orderId: Long): Result<String> {
        val order = orderDao.getOrderById(orderId)
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val (currentUid, currentPhone) = resolveCaptainUidAndPhone()
        if (currentUid.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول أولاً"))
        }

        val orderDocId = resolveCleanOrderDocId(order)
        val finalRes = cloudFunctionsOrderClient.confirmPickup(orderDocId)

        if (finalRes.isSuccess) {
            orderDao.updateOrderStatusAndCaptain(orderId, OrderStatus.PICKED_UP, currentUid)
        } else {
            val err = finalRes.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return finalRes
    }

    /**
     * 4. Submit customer details & receipt image to Firebase Storage ("orders/{orderId}/receipts/{uniqueFileName}")
     * and link receiptImagePath, receiptUploadedAt (ServerTimestamp), and receiptUploadedBy via Cloud Functions
     * (`submitCustomerDeliveryDetails`, server-only; zero direct Firestore fallback).
     * Never marks receipt saved until BOTH Firebase Storage upload AND Cloud Function submission succeed.
     */
    suspend fun saveOrderCustomerData(
        orderId: Long,
        customerName: String,
        customerPhone: String,
        notes: String,
        imageUri: Uri,
        onUploadStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val order = orderDao.getOrderById(orderId)
            ?: return Result.failure(IllegalArgumentException("الطلب غير موجود"))

        val captainUid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع الإيصال"))
        val currentCaptainPhone = captainDao.getCaptain()?.phoneNumber.orEmpty()

        val normalizedAssignedCaptainId = if (
            order.captainId.trim() != captainUid &&
            currentCaptainPhone.isNotBlank() &&
            isSamePhone(order.captainId, currentCaptainPhone)
        ) {
            captainUid
        } else {
            order.captainId
        }

        val orderDocId = resolveCleanOrderDocId(order)

        // Enforce order ownership & explicit allowed lifecycle state before uploading to Firebase Storage
        val uploadResult = storageRepository.uploadOrderReceiptImage(
            orderId = orderDocId,
            assignedCaptainId = normalizedAssignedCaptainId,
            orderStatus = order.status,
            imageUri = imageUri,
            onStateChange = onUploadStateChange
        )
        val uploadedStoragePath = uploadResult.getOrElse { err ->
            return Result.failure(err)
        }

        val finalServerRes = cloudMetadataPort.submitOrderReceiptDetails(
            orderDocId = orderDocId,
            customerName = customerName,
            customerPhone = customerPhone,
            receiptImagePath = uploadedStoragePath,
            notes = notes
        )

        return if (finalServerRes.isSuccess) {
            val nowMs = System.currentTimeMillis()
            orderDao.updateCustomerDetails(
                orderId = orderId,
                customerName = customerName,
                customerPhone = customerPhone,
                imageUrl = uploadedStoragePath,
                notes = notes,
                status = OrderStatus.AWAITING_MERCHANT_CONFIRMATION,
                receiptImagePath = uploadedStoragePath,
                receiptUploadedAt = nowMs,
                receiptUploadedBy = captainUid
            )
            Result.success(uploadedStoragePath)
        } else {
            val serverErr = finalServerRes.exceptionOrNull() ?: Exception("فشل ربط بيانات الإيصال بالطلب على الخادم")
            val cfEx = serverErr as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                orderDao.deleteOrderById(orderId)
            }
            onUploadStateChange(
                ImageUploadState.Failed(
                    errorMessage = serverErr.localizedMessage ?: "فشل حفظ بيانات الإيصال على الخادم",
                    isRetryable = true
                )
            )
            Result.failure(serverErr)
        }
    }

    suspend fun saveOrderDataAndImage(orderId: Long, imageUri: Uri): Result<String> {
        val order = orderDao.getOrderById(orderId)
        val name = order?.customerName ?: "عميل البطل"
        val phone = order?.customerPhone ?: ""
        return saveOrderCustomerData(orderId, name, phone, "", imageUri)
    }

    /**
     * 5. Complete delivery: calls Cloud Function `confirmDelivery` (Server-only; zero direct Firestore fallback)
     * ONLY when merchant has confirmed (EN_ROUTE).
     * Never updates local order status or wallet if the backend call fails.
     */
    suspend fun completeDelivery(orderId: Long): Result<Double> {
        val order = orderDao.getOrderById(orderId)
            ?: return Result.failure(Exception("الطلب غير موجود"))

        if (order.status != OrderStatus.EN_ROUTE) {
            return Result.failure(
                IllegalStateException("لا يمكن تأكيد التسليم قبل اعتماد التاجر لبيانات الإيصال وتحول الطلب إلى حالة التوصيل.")
            )
        }

        val (currentUid, currentPhone) = resolveCaptainUidAndPhone()
        if (currentUid.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول أولاً"))
        }

        val orderDocId = resolveCleanOrderDocId(order)
        val finalRes = cloudFunctionsOrderClient.confirmDelivery(orderDocId)

        if (finalRes.isSuccess) {
            val earnedFee = finalRes.getOrNull()?.takeIf { it > 0.0 } ?: order.deliveryFee
            val nowMs = System.currentTimeMillis()
            orderDao.markOrderDelivered(orderId, currentUid, nowMs)

            // Immediately reflect in captain's overall wallet & total completed orders
            captainDao.addToWallet(earnedFee)
            val captain = captainDao.getCaptain()
            if (captain != null) {
                captainDao.insertOrUpdate(
                    captain.copy(
                        totalOrdersCount = captain.totalOrdersCount + 1
                    )
                )
            }
            reconcileDailyWallets()
        } else {
            val err = finalRes.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return finalRes
    }

    /**
     * 6. Expire delivery offer after 60 seconds via server-verified Cloud Function.
     * Stops local ringing immediately, but treats Cloud Functions / Cloud Tasks server clock
     * as the single source of truth before marking the offer EXPIRED locally.
     */
    suspend fun expireDeliveryOffer(orderId: Long): Result<Unit> {
        val order = orderDao.getOrderById(orderId)
            ?: return Result.failure(Exception("الطلب غير موجود"))
        val orderDocId = resolveCleanOrderDocId(order)
        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it, orderDocId) }

        val finalRes = cloudFunctionsOrderClient.expireDeliveryOffer(orderDocId, reason = "expired")
        if (finalRes.isSuccess) {
            orderDao.updateOrderStatus(orderId, OrderStatus.EXPIRED)
            return Result.success(Unit)
        }

        val cfEx = finalRes.exceptionOrNull() as? com.example.data.remote.CloudFunctionException
        if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
            orderDao.updateOrderStatus(orderId, OrderStatus.EXPIRED)
            return Result.success(Unit)
        }
        return finalRes
    }

    /**
     * 7. Decline incoming delivery offer via Cloud Function (zero direct Firestore fallback).
     */
    suspend fun declineOrder(orderId: Long): Result<Unit> {
        val order = orderDao.getOrderById(orderId)
            ?: return Result.failure(Exception("الطلب غير موجود"))
        val orderDocId = resolveCleanOrderDocId(order)
        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it, orderDocId) }
        val finalRes = cloudFunctionsOrderClient.expireDeliveryOffer(orderDocId, reason = "declined")

        if (finalRes.isSuccess) {
            orderDao.markOrderCancelled(orderId, System.currentTimeMillis(), "تم رفض العرض")
        }
        return finalRes
    }

    companion object {
        const val CAPTAIN_ACCOUNT_TYPE = "captain"
        const val CAPTAIN_ROLE = "captain"
        const val PRESENCE_CALLABLE_TIMEOUT_MS = 10_000L
        const val FIRESTORE_OFFLINE_FALLBACK_TIMEOUT_MS = 5_000L
        const val LOGOUT_NETWORK_TIMEOUT_MS = 3_000L

        const val DEFAULT_ORDERS_LISTENER_CONSUMER = "default"
        const val ORDERS_LISTENER_CONSUMER_VIEWMODEL = "viewmodel"
        const val ORDERS_LISTENER_CONSUMER_LOCATION_SERVICE = "location_service"
        internal const val LISTENER_KEY_PENDING_OFFER = "pending_offer"
        internal val ORDER_ASSIGNMENT_QUERY_FIELDS = listOf(
            "captainId",
            "driverId",
            "captainUid",
            "assignedCaptainId"
        )
        internal const val HISTORY_SYNC_TTL_MS = 3L * 60L * 1000L
        internal const val SHIFT_SYNC_DEDUPE_TTL_MS = 30L * 1000L
        internal const val WALLET_RECONCILE_DEBOUNCE_MS = 800L
        private const val OVERNIGHT_QUERY_BUFFER_MS = 24L * 60L * 60L * 1000L

        @Volatile
        private var sharedInstance: CaptainRepository? = null

        /**
         * Returns the single process-wide [CaptainRepository] bound strictly to [Context.getApplicationContext].
         * All app components (ViewModel, LocationService, IncomingOrderActivity, IncomingOrderBellService,
         * ShiftAlarmReceiver) share this instance to avoid duplicate Firestore listeners and cache divergence.
         */
        fun getInstance(context: Context): CaptainRepository {
            val appCtx = context.applicationContext
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: run {
                    val db = AppDatabase.getDatabase(appCtx)
                    CaptainRepository(
                        captainDao = db.captainDao(),
                        orderDao = db.orderDao(),
                        shiftDao = db.shiftDao(),
                        context = appCtx
                    ).also { sharedInstance = it }
                }
            }
        }

        internal fun resetSharedInstanceForTesting() {
            synchronized(this) {
                sharedInstance?.stopRealtimeOrdersListener()
                sharedInstance?.stopRealtimeShiftListener()
                sharedInstance?.clearHistorySyncCaches()
                sharedInstance = null
            }
        }

        private val fcmTokenSyncMutex = Mutex()

        @Volatile
        internal var fcmTokenHardFailureDetected: Boolean = false

        @Volatile
        private var lastSyncedFcmUid: String? = null

        @Volatile
        private var lastSyncedFcmToken: String? = null

        internal fun resetPresenceFallbackStateForTesting() {
            fcmTokenHardFailureDetected = false
            lastSyncedFcmUid = null
            lastSyncedFcmToken = null
        }

        /**
         * Determines whether a document inside `captains/{uid}` has a valid Captain identity:
         * - If `accountType` is present and non-blank: it MUST equal `"captain"` (blocks merchants even if `role` is set).
         * - If `accountType` is missing/blank on a legacy document: accepts ONLY when `role` equals `"captain"`.
         * - If `role` is also present alongside `accountType == "captain"`, `role` must not be a conflicting non-captain role.
         */
        fun isEligibleCaptainIdentity(rawAccountType: String?, rawRole: String?): Boolean {
            val cleanAccountType = rawAccountType?.trim()?.lowercase().orEmpty()
            val cleanRole = rawRole?.trim()?.lowercase().orEmpty()
            if (cleanAccountType.isNotEmpty() && cleanAccountType != "null") {
                if (cleanAccountType != CAPTAIN_ACCOUNT_TYPE) return false
                if (cleanRole.isNotEmpty() && cleanRole != "null" && cleanRole !in listOf(CAPTAIN_ROLE, "driver")) {
                    return false
                }
                return true
            }
            return cleanRole == CAPTAIN_ROLE
        }

        /**
         * Builds the canonical Firestore payload for `captains/{uid}` on new captain registration,
         * always writing both `accountType = "captain"` and `role = "captain"` uniformly.
         */
        fun buildNewCaptainDocumentData(
            uid: String,
            name: String,
            email: String,
            phone: String,
            profilePath: String,
            idFrontPath: String,
            idBackPath: String,
            licensePath: String,
            address: String,
            vehicleType: String,
            avatarPresetIndex: Int,
            createdAtMs: Long
        ): HashMap<String, Any> {
            return hashMapOf(
                "uid" to uid,
                "name" to name,
                "fullName" to name,
                "email" to email,
                "phone" to phone,
                "phoneNumber" to phone,
                "personalImage" to profilePath,
                "profileImagePath" to profilePath,
                "idCardFront" to idFrontPath,
                "idFrontImagePath" to idFrontPath,
                "idCardBack" to idBackPath,
                "idBackImagePath" to idBackPath,
                "licenseImagePath" to licensePath,
                "idDocumentsComplete" to (idFrontPath.isNotBlank() && idBackPath.isNotBlank()),
                "address" to address,
                "accountType" to CAPTAIN_ACCOUNT_TYPE,
                "role" to CAPTAIN_ROLE,
                "accountStatus" to CaptainAccountStatus.PENDING.wireValue,
                "isApproved" to false,
                "status" to "pending",
                "emailVerified" to false,
                "isEmailVerified" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "availabilityStatus" to "offline",
                "presenceStatus" to "offline",
                "shiftStatus" to "outside_shift",
                "vehicleType" to vehicleType,
                "licensePlate" to "ب ط ل 1 0 0",
                "avatarUri" to profilePath,
                "avatarPresetIndex" to avatarPresetIndex,
                "totalOrdersCount" to 0,
                "walletBalance" to 0.0,
                "rating" to 5.0,
                "isOnline" to false,
                "isLoggedIn" to false
            )
        }

        /**
         * Read-only diagnostic auditor for existing `captains` documents:
         * Identifies legacy documents that have `role = "captain"` without `accountType = "captain"`,
         * without mutating any Firestore data.
         */
        fun auditLegacyRoleWithoutAccountType(
            captainDocsById: Map<String, Map<String, Any?>>
        ): List<String> {
            return captainDocsById.entries.mapNotNull { (docId, fields) ->
                val accountType = fields["accountType"]?.toString()?.trim()?.lowercase().orEmpty()
                val role = fields["role"]?.toString()?.trim()?.lowercase().orEmpty()
                if ((accountType.isEmpty() || accountType == "null") && role == CAPTAIN_ROLE) {
                    docId
                } else {
                    null
                }
            }
        }
    }
}

fun DocumentSnapshot.safeDouble(vararg fields: String, default: Double = 0.0): Double {
    for (field in fields) {
        val raw = try { get(field) } catch (_: Exception) { null } ?: continue
        val num = when (raw) {
            is Number -> raw.toDouble()
            is String -> {
                val cleaned = raw.replace(Regex("[^0-9.-]"), "").trim()
                cleaned.toDoubleOrNull()
            }
            else -> null
        }
        if (num != null) return num
    }
    return default
}

fun DocumentSnapshot.safeLong(vararg fields: String, default: Long = 0L): Long {
    for (field in fields) {
        val raw = try { get(field) } catch (_: Exception) { null } ?: continue
        val num: Long? = when (raw) {
            is Timestamp -> raw.toDate().time
            is java.util.Date -> raw.time
            is Number -> {
                val v = raw.toLong()
                // Convert 10-digit Unix epoch seconds (e.g., 1762800000) to milliseconds
                if (v in 1_000_000_000L..99_999_999_999L) v * 1000L else v
            }
            is Map<*, *> -> {
                val sec = (raw["seconds"] as? Number ?: raw["_seconds"] as? Number)?.toLong()
                sec?.let { if (it in 1_000_000_000L..99_999_999_999L) it * 1000L else it }
            }
            is String -> {
                val trimmed = normalizeArabicDigits(raw).trim()
                val directLong = trimmed.toLongOrNull()
                if (directLong != null) {
                    if (directLong in 1_000_000_000L..99_999_999_999L) directLong * 1000L else directLong
                } else {
                    parseIsoDateStringOrNull(trimmed)
                }
            }
            else -> null
        }
        if (num != null && num > 0L) return num
    }
    return default
}

private fun parseIsoDateStringOrNull(raw: String): Long? {
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )
    for (pattern in patterns) {
        try {
            val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
            if (!pattern.contains("X")) {
                sdf.timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo")
            }
            val parsed = sdf.parse(raw)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {}
    }
    return null
}

fun isPlaceholderOrUnassigned(value: String?): Boolean {
    if (value.isNullOrBlank()) return true
    val clean = value.trim().lowercase()
    return clean in listOf(
        "", "none", "null", "nil", "unassigned", "undefined",
        "0", "-", "--", "n/a", "na", "false", "no_driver", "no-driver", "waiting"
    )
}

fun normalizeArabicDigits(input: String?): String {
    if (input == null) return ""
    val builder = StringBuilder(input.length)
    for (ch in input) {
        when (ch) {
            '٠' -> builder.append('0')
            '١' -> builder.append('1')
            '٢' -> builder.append('2')
            '٣' -> builder.append('3')
            '٤' -> builder.append('4')
            '٥' -> builder.append('5')
            '٦' -> builder.append('6')
            '٧' -> builder.append('7')
            '٨' -> builder.append('8')
            '٩' -> builder.append('9')
            '۰' -> builder.append('0')
            '۱' -> builder.append('1')
            '۲' -> builder.append('2')
            '۳' -> builder.append('3')
            '۴' -> builder.append('4')
            '۵' -> builder.append('5')
            '۶' -> builder.append('6')
            '۷' -> builder.append('7')
            '۸' -> builder.append('8')
            '۹' -> builder.append('9')
            else -> builder.append(ch)
        }
    }
    return builder.toString()
}

fun isSamePhone(phone1: String?, phone2: String?): Boolean {
    if (phone1.isNullOrBlank() || phone2.isNullOrBlank()) return false
    val p1 = normalizeArabicDigits(phone1).replace(Regex("[^0-9]"), "")
    val p2 = normalizeArabicDigits(phone2).replace(Regex("[^0-9]"), "")
    if (p1 == p2) return true
    val p1Trim = p1.removePrefix("20").removePrefix("0")
    val p2Trim = p2.removePrefix("20").removePrefix("0")
    return p1Trim.isNotEmpty() && p1Trim == p2Trim
}

fun DocumentSnapshot.isExcludedCaptain(uid: String): Boolean {
    if (uid.isBlank()) return false
    for (field in listOf("excludedCaptainIds", "excluded_captain_ids", "rejectedCaptainIds")) {
        val list = try { get(field) as? List<*> } catch (_: Exception) { null } ?: continue
        if (list.any { it?.toString()?.trim() == uid }) return true
    }
    return false
}

fun DocumentSnapshot.safeString(vararg fields: String, default: String = ""): String {
    for (field in fields) {
        val raw = try { get(field) } catch (_: Exception) { null } ?: continue
        val str = raw.toString().trim()
        if (str.isNotBlank()) return str
    }
    return default
}

private fun formatOrderCodeRawValue(raw: Any?): String {
    return when (raw) {
        null -> ""
        is Iterable<*> -> raw.mapNotNull { item ->
            formatOrderCodeRawValue(item).takeIf { it.isNotBlank() }
        }.distinct().joinToString(" • ")
        is Array<*> -> raw.mapNotNull { item ->
            formatOrderCodeRawValue(item).takeIf { it.isNotBlank() }
        }.distinct().joinToString(" • ")
        is Map<*, *> -> raw.values.mapNotNull { item ->
            formatOrderCodeRawValue(item).takeIf { it.isNotBlank() }
        }.distinct().joinToString(" • ")
        is Number -> {
            if (raw is Double && raw % 1.0 == 0.0) {
                raw.toLong().toString()
            } else {
                raw.toString().trim()
            }
        }
        else -> raw.toString().trim().trimStart('#').trim().let {
            if (it.equals("null", ignoreCase = true)) "" else it
        }
    }
}

fun extractOrderCodeFromFieldsMap(fieldsMap: Map<String, Any?>?): String {
    if (fieldsMap.isNullOrEmpty()) return ""
    val priorityKeys = listOf(
        "orderCodes", "order_codes", "orderCode", "order_code",
        "ordarCodes", "ordar_codes", "ordarCode", "ordar_code",
        "Ordar codes", "Ordar code", "Order codes", "Order code",
        "order codes", "order code", "Ordar_codes", "Ordar_code",
        "order_code_list", "orderCodesList",
        "trackingCode", "tracking_code", "shortCode", "short_code",
        "code", "codes"
    )
    for (key in priorityKeys) {
        if (fieldsMap.containsKey(key)) {
            val formatted = formatOrderCodeRawValue(fieldsMap[key])
            if (formatted.isNotBlank()) return formatted
        }
    }
    val normalizedMatches = setOf(
        "ordercodes", "ordercode", "ordarcodes", "ordarcode", "trackingcode", "shortcode"
    )
    for ((rawKey, rawVal) in fieldsMap) {
        val norm = rawKey.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (norm in normalizedMatches) {
            val formatted = formatOrderCodeRawValue(rawVal)
            if (formatted.isNotBlank()) return formatted
        }
    }
    return ""
}

fun DocumentSnapshot.extractOrderCode(): String {
    val priorityKeys = listOf(
        "orderCodes", "order_codes", "orderCode", "order_code",
        "ordarCodes", "ordar_codes", "ordarCode", "ordar_code",
        "Ordar codes", "Ordar code", "Order codes", "Order code",
        "order codes", "order code", "Ordar_codes", "Ordar_code",
        "order_code_list", "orderCodesList",
        "trackingCode", "tracking_code", "shortCode", "short_code",
        "code", "codes"
    )
    for (key in priorityKeys) {
        val raw = try { get(key) } catch (_: Exception) { null }
        val formatted = formatOrderCodeRawValue(raw)
        if (formatted.isNotBlank()) return formatted
    }
    return extractOrderCodeFromFieldsMap(try { data } catch (_: Exception) { null })
}

fun normalizeLocationUrl(raw: Any?): String {
    if (raw == null) return ""
    if (raw is Map<*, *>) {
        val preferredMapKeys = listOf(
            "url", "link", "href", "locationUrl", "location_url", "locationLink",
            "mapUrl", "map_url", "mapsUrl", "mapsLink", "googleMapsUrl", "googleMapUrl", "src"
        )
        for (k in preferredMapKeys) {
            val nested = normalizeLocationUrl(raw[k])
            if (nested.isNotBlank()) return nested
        }
        return ""
    }
    if (raw !is String) return ""
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return ""
    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("geo:") ||
            lower.startsWith("google.navigation:") -> trimmed
        lower.startsWith("maps.app.goo.gl/") ||
            lower.startsWith("goo.gl/") ||
            lower.startsWith("maps.google.") ||
            lower.startsWith("www.google.com/maps") ||
            lower.startsWith("google.com/maps") -> "https://$trimmed"
        else -> ""
    }
}

fun extractMerchantLocationUrlFromFieldsMap(fieldsMap: Map<String, Any?>?): String {
    if (fieldsMap.isNullOrEmpty()) return ""
    val priorityKeys = listOf(
        "storeLocationUrl", "store_location_url", "storeLocationLink", "store_location_link",
        "storeMapUrl", "store_map_url", "storeMapsUrl", "storeMapsLink",
        "merchantLocationUrl", "merchant_location_url", "merchantLocationLink", "merchant_location_link",
        "merchantMapUrl", "merchant_map_url", "merchantMapsUrl", "merchantMapsLink",
        "restaurantLocationUrl", "restaurant_location_url", "restaurantLocationLink", "restaurant_location_link",
        "restaurantMapUrl", "restaurant_map_url", "restaurantMapsUrl", "restaurantMapsLink",
        "pickupLocationUrl", "pickup_location_url", "pickupMapUrl", "pickup_map_url",
        "locationUrl", "location_url", "locationLink", "location_link",
        "mapUrl", "map_url", "mapLink", "map_link",
        "mapsUrl", "maps_url", "mapsLink", "maps_link",
        "googleMapsUrl", "google_maps_url", "googleMapUrl", "google_map_url",
        "googleMapsLink", "google_maps_link", "googleMapLink", "googleLocation",
        "gpsLink", "gpsUrl", "location_map_url", "location"
    )
    for (key in priorityKeys) {
        if (fieldsMap.containsKey(key)) {
            val url = normalizeLocationUrl(fieldsMap[key])
            if (url.isNotBlank()) return url
        }
    }

    val normalizedTargetKeys = setOf(
        "storelocationurl", "storelocationlink", "storemapurl", "storemapsurl", "storemapslink",
        "merchantlocationurl", "merchantlocationlink", "merchantmapurl", "merchantmapsurl", "merchantmapslink",
        "restaurantlocationurl", "restaurantlocationlink", "restaurantmapurl", "restaurantmapsurl",
        "pickuplocationurl", "pickupmapurl",
        "locationurl", "locationlink", "mapurl", "maplink", "mapsurl", "mapslink",
        "googlemapsurl", "googlemapurl", "googlemapslink", "googlemaplink", "googlelocation",
        "gpslink", "gpsurl"
    )
    for ((rawKey, rawVal) in fieldsMap) {
        val norm = rawKey.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (norm in normalizedTargetKeys) {
            val url = normalizeLocationUrl(rawVal)
            if (url.isNotBlank()) return url
        }
    }

    val nestedContainerKeys = listOf(
        "restaurant", "merchant", "store", "vendor", "shop",
        "restaurantInfo", "merchantInfo", "storeInfo", "locationInfo", "pickupLocation"
    )
    for (containerKey in nestedContainerKeys) {
        val nested = fieldsMap[containerKey] as? Map<*, *> ?: continue
        @Suppress("UNCHECKED_CAST")
        val nestedUrl = extractMerchantLocationUrlFromFieldsMap(nested as? Map<String, Any?>)
        if (nestedUrl.isNotBlank()) return nestedUrl
    }
    return ""
}

fun DocumentSnapshot.toOrderEntity(): OrderEntity? {
    val docId = id
    val cleanDocId = docId.trim().trimStart('#').trim()
    val rawOrderNum = safeString("orderNumber", "order_number", "orderId", "order_id", "id", default = (cleanDocId.ifBlank { docId }).takeLast(6).uppercase())
    val orderNum = rawOrderNum.trim().trimStart('#').trim()
    val extractedOrderCode = extractOrderCode()
    val custName = safeString(
        "customerName", "customer_name", "client_name", "clientName",
        "userName", "user_name", "name", default = "عميل البطل"
    )
    val custPhone = safeString(
        "customerPhone", "customer_phone", "client_phone", "clientPhone",
        "phone", "phoneNumber", "mobile", default = "01000000000"
    )
    val storeName = safeString(
        "restaurant name", "restaurant_name", "restaurantName",
        "storeName", "store_name", "restaurant", "store",
        "vendorName", "vendor", "shopName", "shop",
        default = "مطعم الشريك"
    )
    val storePhone = safeString("storePhone", "restaurantPhone", "restaurant_phone", "store_phone", "vendorPhone")
    val storeImage = resolveMerchantImageReference(
        storeImage = safeString(
            "storeImage", "merchantImageUrl", "merchantLogo", "profileImageUrl",
            "logoUrl", "storeLogo", "storePhotoUrl", "restaurantImage",
            "restaurant_image", "restaurantPhoto", "store_image", "imageUrl", "image", "photoUrl"
        ),
        storeImagePath = safeString(
            "storeImagePath", "store_image_path", "merchantStoragePath", "merchant_storage_path",
            "logoStoragePath", "logo_storage_path", "merchantLogoPath", "merchant_logo_path"
        )
    )
    val pickup = safeString(
        "pickupAddress", "pickup_address", "restaurant_address", "restaurantAddress",
        "store_address", "storeAddress", "sourceAddress", default = "عنوان المتجر"
    )
    val delivery = safeString(
        "address", "deliveryAddress", "delivery_address", "clientAddress",
        "customerAddress", "dropoffAddress", "destination", default = "عنوان العميل"
    )

    // GeoPoint or numbers for coordinates
    var lat: Double? = null
    var lng: Double? = null
    val geo = try {
        getGeoPoint("restaurant_location") ?: getGeoPoint("location") ?: getGeoPoint("pickup_location")
    } catch (_: Exception) { null }

    if (geo != null) {
        lat = geo.latitude
        lng = geo.longitude
    } else {
        lat = (get("restaurantLatitude") as? Number)?.toDouble()
            ?: (get("latitude") as? Number)?.toDouble()
            ?: (get("lat") as? Number)?.toDouble()
            ?: safeDouble("restaurantLatitude", "latitude", "lat", "pickup_lat").takeIf { it != 0.0 }
        lng = (get("restaurantLongitude") as? Number)?.toDouble()
            ?: (get("longitude") as? Number)?.toDouble()
            ?: (get("lng") as? Number)?.toDouble()
            ?: safeDouble("restaurantLongitude", "longitude", "lng", "pickup_lng").takeIf { it != 0.0 }
    }

    val itemsSum = safeString("itemsSummary", "items_summary", "order_details", "details", "items", default = "طلب من المطعم")
    val itemsCnt = safeLong("itemsCount", "items_count", "quantity", default = 1L).toInt()

    val totalAmt = safeDouble("price", "orderTotalAmount", "total", "total_price", "amount", default = 0.0)
    val fee = safeDouble("deliveryFee", "delivery_fee", "shippingFee", "fee", default = 25.0)
    val dist = safeDouble("distanceKm", "distance_km", "distance", default = 3.0)

    val captId = safeString(
        "captainId", "captain_id", "driverId", "driver_id",
        "assignedCaptainId", "assignedDriverId", "captainUid", "driverUid",
        "assignedCaptainPhone", "driverPhone", "captain_phone"
    )

    val rawStatus = safeString("status", "order_status", "orderStatus", "state", default = "pending").trim()
    val created = safeLong("created_at", "createdAt", "timestamp", "date", default = System.currentTimeMillis())
    val note = safeString("notes", "note", "comment")
    val pin = safeString("deliveryPin", "pin", default = "1234")
    val accAt = safeLong("acceptedAt", "accepted_at")

    val receiptPath = safeString("receiptImagePath", "receipt_image_path")
    val receiptUpAt = safeLong("receiptUploadedAt", "receipt_uploaded_at", "receiptSubmittedAt")
    val receiptUpBy = safeString("receiptUploadedBy", "receipt_uploaded_by")
    // Backward compatibility with legacy ImgBB / URL fields without modifying them
    val imgUrl = receiptPath.ifBlank {
        safeString("orderImageUrl", "order_image_url", "receiptImageUrl", "deliveryImageUrl", "receiptImage")
    }
    val dataEnteredAt = safeLong("orderDataEnteredAt")
    val rejectionReason = safeString("rejectionReason", "rejection_reason")

    val fieldsMap = try { data } catch (_: Exception) { null }
    val rawMerchantConfirmedFlag = extractMerchantConfirmedFromFieldsMap(fieldsMap) ||
        isTruthyConfirmationValue(get("merchantConfirmed")) ||
        isTruthyConfirmationValue(get("merchant_confirmed")) ||
        isTruthyConfirmationValue(get("isMerchantConfirmed")) ||
        isTruthyConfirmationValue(get("confirmedByMerchant")) ||
        isTruthyConfirmationValue(get("merchantApproved")) ||
        isTruthyConfirmationValue(get("isConfirmed")) ||
        isTruthyConfirmationValue(get("confirmed")) ||
        (try { get("merchantConfirmedAt") != null || get("merchant_confirmed_at") != null } catch (_: Exception) { false })

    val hasReceiptOrData = receiptPath.isNotBlank() || imgUrl.isNotBlank() || receiptUpAt > 0L || dataEnteredAt > 0L
    val resolvedStatus = resolveOrderStatusFromFirestoreFields(
        rawStatus = rawStatus,
        captainId = captId,
        merchantConfirmedFlag = rawMerchantConfirmedFlag,
        hasReceiptOrOrderData = hasReceiptOrData,
        hasAcceptedAt = accAt > 0L,
        rejectionReason = rejectionReason
    )
    val statusEnum = resolvedStatus.status
    val merchantConfirmed = resolvedStatus.merchantConfirmed

    val compAtRaw = safeLong("completedAt", "completed_at", "deliveredAt", "delivered_at")
    val compAt = when {
        compAtRaw > 0L -> compAtRaw
        statusEnum == OrderStatus.DELIVERED -> {
            val upAt = safeLong("updatedAt", "updated_at")
            if (upAt > 0L) upAt else System.currentTimeMillis()
        }
        else -> 0L
    }
    val cancAtRaw = safeLong("cancelledAt", "canceledAt", "cancelled_at", "canceled_at", "rejectedAt", "declinedAt")
    val cancAt = when {
        cancAtRaw > 0L -> cancAtRaw
        statusEnum == OrderStatus.CANCELLED -> safeLong("updatedAt", "updated_at", default = created)
        else -> 0L
    }

    val offerExpiresAt = safeLong("offerExpiresAt", "offer_expires_at")
    val cancelReason = safeString(
        "cancellationReason", "cancelReason", "cancellation_reason",
        "cancel_reason", "cancelledReason", "reason"
    ).ifBlank {
        if (statusEnum == OrderStatus.CANCELLED) rejectionReason else ""
    }
    val etaMinutes = safeLong("etaMinutes", "eta", default = 15L).toInt()

    val numericId = docId.hashCode().toLong().let { if (it < 0) -it else it }

    val merchantId = safeString(
        "merchantId", "merchant_id", "restaurantId", "restaurant_id",
        "storeId", "store_id", "vendorId", "vendor_id",
        "ownerId", "owner_id", "merchantUid", "restaurantUid"
    )
    val storeLocationUrl = extractMerchantLocationUrlFromFieldsMap(fieldsMap)

    return OrderEntity(
        id = if (numericId == 0L) 1L else numericId,
        orderNumber = orderNum,
        orderCode = extractedOrderCode,
        customerName = custName,
        customerPhone = custPhone,
        storeName = storeName,
        storePhone = storePhone,
        storeImage = storeImage,
        pickupAddress = pickup,
        deliveryAddress = delivery,
        merchantId = merchantId,
        storeLocationUrl = storeLocationUrl,
        restaurantLatitude = lat,
        restaurantLongitude = lng,
        itemsSummary = itemsSum,
        itemsCount = itemsCnt,
        orderTotalAmount = totalAmt,
        deliveryFee = fee,
        distanceKm = dist,
        status = statusEnum,
        createdAt = created,
        notes = note,
        deliveryPin = pin,
        firestoreDocId = docId,
        captainId = captId,
        acceptedAt = accAt,
        orderImageUrl = imgUrl,
        receiptImagePath = receiptPath,
        receiptUploadedAt = receiptUpAt,
        receiptUploadedBy = receiptUpBy,
        orderDataEnteredAt = dataEnteredAt,
        offerExpiresAt = offerExpiresAt,
        merchantConfirmed = merchantConfirmed,
        rejectionReason = rejectionReason,
        etaMinutes = etaMinutes,
        completedAt = compAt,
        cancelledAt = cancAt,
        cancellationReason = cancelReason
    )
}

data class ResolvedOrderStatus(
    val status: OrderStatus,
    val merchantConfirmed: Boolean
)

private val DATE_KEY_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
private val WHITESPACE_RUN_REGEX = Regex("\\s+")
private val UNDERSCORE_RUN_REGEX = Regex("_+")

fun isTruthyConfirmationValue(raw: Any?): Boolean {
    return when (raw) {
        null -> false
        is Boolean -> raw
        is Number -> raw.toLong() > 0L
        is Timestamp -> raw.seconds > 0L
        is java.util.Date -> raw.time > 0L
        is String -> {
            val clean = raw.trim().lowercase().replace("-", "_").replace(WHITESPACE_RUN_REGEX, "_")
            clean in setOf(
                "true", "1", "yes", "ok", "confirmed", "approved", "accepted",
                "done", "verified", "نعم", "مؤكد", "تم_التأكيد", "تم_الاعتماد"
            )
        }
        else -> false
    }
}

private fun normalizeOrderStatusToken(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace('-', '_')
        .replace(WHITESPACE_RUN_REGEX, "_")
        .replace(UNDERSCORE_RUN_REGEX, "_")
        .trim('_')
}

fun extractMerchantConfirmedFromFieldsMap(fields: Map<String, Any?>?): Boolean {
    if (fields.isNullOrEmpty()) return false
    val boolKeys = listOf(
        "merchantConfirmed", "merchant_confirmed", "isMerchantConfirmed", "is_merchant_confirmed",
        "confirmedByMerchant", "confirmed_by_merchant", "merchantConfirmation", "merchant_confirmation",
        "merchantApproved", "merchant_approved", "isMerchantApproved", "is_merchant_approved",
        "approvedByMerchant", "approved_by_merchant", "orderDataConfirmed", "order_data_confirmed",
        "dataConfirmed", "data_confirmed", "receiptConfirmed", "receipt_confirmed",
        "isReceiptConfirmed", "receiptApproved", "receipt_approved", "isConfirmed", "is_confirmed", "confirmed"
    )
    for (key in boolKeys) {
        if (fields.containsKey(key) && isTruthyConfirmationValue(fields[key])) {
            return true
        }
    }
    val statusKeys = listOf(
        "merchantStatus", "merchant_status", "confirmationStatus", "confirmation_status",
        "merchantApprovalStatus", "merchant_approval_status", "receiptStatus", "receipt_status",
        "approvalStatus", "approval_status", "dataConfirmationStatus", "data_confirmation_status"
    )
    val confirmedTokens = setOf(
        "confirmed", "approved", "accepted", "verified", "en_route", "enroute",
        "تم_التأكيد", "تم_تأكيد_التاجر", "تم_تأكيد_الطلب", "مؤكد", "تم_الاعتماد"
    )
    for (key in statusKeys) {
        val raw = fields[key]?.toString() ?: continue
        val norm = normalizeOrderStatusToken(raw)
        if (norm in confirmedTokens) return true
    }
    val timestampKeys = listOf("merchantConfirmedAt", "merchant_confirmed_at", "confirmedAt", "confirmed_at")
    for (key in timestampKeys) {
        val v = fields[key] ?: continue
        when (v) {
            is Timestamp, is java.util.Date -> return true
            is Number -> if (v.toLong() > 0L) return true
            is String -> {
                val clean = v.trim().lowercase()
                if (clean.isNotBlank() && clean != "null" && clean != "0" && clean != "false") {
                    return true
                }
            }
        }
    }
    return false
}

fun resolveOrderStatusFromFirestoreFields(
    rawStatus: String,
    captainId: String,
    merchantConfirmedFlag: Boolean,
    hasReceiptOrOrderData: Boolean = false,
    hasAcceptedAt: Boolean = false,
    rejectionReason: String = ""
): ResolvedOrderStatus {
    val token = normalizeOrderStatusToken(rawStatus)
    val compact = token.replace("_", "")
    val hasAssignedCaptain = !isPlaceholderOrUnassigned(captainId)

    // 1. Terminal statuses take strict precedence
    if (compact in setOf("delivered", "completed", "done") ||
        token in setOf("تم_التسليم", "مكتمل")
    ) {
        return ResolvedOrderStatus(OrderStatus.DELIVERED, merchantConfirmed = merchantConfirmedFlag || true)
    }

    if (compact in setOf("cancelled", "canceled", "rejected", "declined") ||
        token in setOf("ملغي", "مرفوض")
    ) {
        return ResolvedOrderStatus(OrderStatus.CANCELLED, merchantConfirmed = false)
    }

    if (compact == "expired" || token in setOf("منتهي", "منتهي_الصلاحية")) {
        return ResolvedOrderStatus(OrderStatus.EXPIRED, merchantConfirmed = false)
    }

    // 2. Merchant rejection (unless explicitly re-confirmed)
    val isMerchantRejectedToken = compact in setOf(
        "merchantrejected", "rejectedbymerchant", "datarejected", "receiptrejected"
    ) || token in setOf("مرفوض_من_التاجر", "تم_رفض_البيانات")
    if (isMerchantRejectedToken && !merchantConfirmedFlag) {
        return ResolvedOrderStatus(OrderStatus.MERCHANT_REJECTED, merchantConfirmed = false)
    }

    // 3. Merchant confirmed / En route to deliver to customer
    val isEnRouteOrConfirmedToken = compact in setOf(
        "enroute", "onway", "ontheway", "onwaytocustomer", "onthewaytocustomer",
        "delivering", "deliveringtocustomer", "outfordelivery", "indelivery", "ondelivery",
        "dispatched", "confirmed", "merchantconfirmed", "confirmedbymerchant",
        "orderconfirmed", "orderdataconfirmed", "dataconfirmed", "receiptconfirmed",
        "approved", "merchantapproved", "approvedbymerchant", "orderapproved",
        "readyfordelivery", "readytodeliver", "readytocustomer", "headingtocustomer"
    ) || token in setOf(
        "في_الطريق", "في_الطريق_للعميل", "جاري_التوصيل", "جاري_التسليم",
        "تم_التأكيد", "تم_تأكيد_التاجر", "تم_تأكيد_الطلب", "تم_تأكيد_البيانات",
        "تم_تأكيد_بيانات_الطلب", "مؤكد", "مؤكد_من_التاجر", "تم_الاعتماد",
        "جاهز_للتسليم", "جاهز_للتوصيل"
    )
    if (isEnRouteOrConfirmedToken) {
        return ResolvedOrderStatus(OrderStatus.EN_ROUTE, merchantConfirmed = true)
    }

    // 4. Boolean / timestamp confirmation flag overrides pre-delivery waiting statuses
    if (merchantConfirmedFlag) {
        return ResolvedOrderStatus(OrderStatus.EN_ROUTE, merchantConfirmed = true)
    }

    // 5. Awaiting merchant confirmation
    if (compact in setOf(
            "awaitingmerchantconfirmation", "awaitingconfirmation",
            "pendingmerchantconfirmation", "waitingmerchantconfirmation", "waitingconfirmation"
        ) || token in setOf("بانتظار_تأكيد_التاجر", "في_انتظار_تأكيد_التاجر")
    ) {
        return ResolvedOrderStatus(OrderStatus.AWAITING_MERCHANT_CONFIRMATION, merchantConfirmed = false)
    }

    // 6. Order data entered / Picked up / At store
    if (compact in setOf("orderdataentered", "dataentered") ||
        token in setOf("تم_إدخال_البيانات", "تم_ادخال_البيانات")
    ) {
        return ResolvedOrderStatus(OrderStatus.ORDER_DATA_ENTERED, merchantConfirmed = false)
    }

    if (compact == "pickedup" || token in setOf("تم_الاستلام", "مستلم")) {
        return ResolvedOrderStatus(OrderStatus.PICKED_UP, merchantConfirmed = false)
    }

    if (compact in setOf("atstore", "arrivedatmerchant", "arrived", "arrivedstore", "arrivedatstore") ||
        token in setOf("في_المتجر", "تم_الوصول", "تم_الوصول_للتاجر")
    ) {
        return ResolvedOrderStatus(OrderStatus.AT_STORE, merchantConfirmed = false)
    }

    // 7. Accepted / Assigned
    if (compact in setOf("accepted", "assigned") || token in setOf("تم_القبول", "مقبول")) {
        return if (!hasAssignedCaptain) {
            ResolvedOrderStatus(OrderStatus.PENDING, merchantConfirmed = false)
        } else {
            ResolvedOrderStatus(OrderStatus.ACCEPTED, merchantConfirmed = false)
        }
    }

    // 8. Guard against already-active assigned orders regressing into PENDING
    // when a merchant dashboard sets e.g. "ready" / "ready_for_pickup" / custom confirmed state
    // after the captain has already submitted receipt & customer data.
    val isExplicitNewOrderToken = compact in setOf(
        "pending", "new", "offered", "searching", "waitingdriver", "waitingcaptain"
    ) || token in setOf("جديد", "متاح", "بانتظار_كابتن")

    if (hasAssignedCaptain && hasReceiptOrOrderData) {
        return if (rejectionReason.isNotBlank() && !merchantConfirmedFlag && isExplicitNewOrderToken) {
            ResolvedOrderStatus(OrderStatus.MERCHANT_REJECTED, merchantConfirmed = false)
        } else if (compact in setOf("ready", "readyforpickup", "prepared", "donepreparing") || !isExplicitNewOrderToken) {
            // Merchant updated an already-receipt-submitted assigned order to a non-pending/ready status -> ready to deliver
            ResolvedOrderStatus(OrderStatus.EN_ROUTE, merchantConfirmed = true)
        } else {
            ResolvedOrderStatus(OrderStatus.AWAITING_MERCHANT_CONFIRMATION, merchantConfirmed = false)
        }
    }

    if (hasAssignedCaptain && hasAcceptedAt && !isExplicitNewOrderToken) {
        return ResolvedOrderStatus(OrderStatus.ACCEPTED, merchantConfirmed = false)
    }

    return ResolvedOrderStatus(OrderStatus.PENDING, merchantConfirmed = false)
}

