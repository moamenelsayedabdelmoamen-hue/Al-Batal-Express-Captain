package com.example.ui

import android.app.Application
import android.location.Location
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.CaptainAccountApprovalResolver
import com.example.data.CaptainAccountStatus
import com.example.data.CaptainApprovalSnapshot
import com.example.data.CaptainEntity
import com.example.data.CaptainPresenceException
import com.example.data.CaptainRepository
import com.example.data.DailyWalletEntity
import com.example.data.DailyWalletManager
import com.example.data.LocationEligibilityResult
import com.example.data.LoginOutcome
import com.example.data.OrderEntity
import com.example.data.OrderHistoryHelper
import com.example.data.OrderHistorySummaryStats
import com.example.data.OrderHistoryTab
import com.example.data.OrderStatus
import com.example.data.PresenceErrorReason
import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import com.example.data.StartupSessionState
import com.example.data.location.CaptainLocationTracker
import com.example.data.location.GpsStatus
import com.example.data.location.LocationServiceStartResult
import com.example.data.storage.ImageUploadState
import com.example.ui.navigation.ScreenRoute
import android.util.Log
import com.example.data.extractShiftsFromMap
import com.example.notifications.CaptainNotificationManager
import com.example.notifications.ShiftNotificationScheduler
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    object OrderAccepted : UiEvent()
    object OrderCompleted : UiEvent()
    object RequestLocationPermission : UiEvent()
    object RequestGpsEnable : UiEvent()
}

data class OrderActionError(
    val message: String,
    val actionLabel: String,
    val isRetryable: Boolean = true,
    val isNotFound: Boolean = false,
    val onRetry: () -> Unit,
    val onDismissOrClear: (() -> Unit)? = null
)

class CaptainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val repository = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), application.applicationContext)

    private fun ensureFirebaseInitialized(): Boolean {
        return runCatching {
            val app = getApplication<Application>()
            if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
                com.google.firebase.FirebaseApp.initializeApp(app)
            }
            com.google.firebase.FirebaseApp.getApps(app).isNotEmpty()
        }.getOrDefault(false)
    }

    private val firestore: FirebaseFirestore? by lazy {
        if (ensureFirebaseInitialized()) {
            runCatching { FirebaseFirestore.getInstance() }.getOrNull()
        } else null
    }
    private val auth: FirebaseAuth? by lazy {
        if (ensureFirebaseInitialized()) {
            runCatching { FirebaseAuth.getInstance() }.getOrNull()
        } else null
    }
    private var shiftDocListener: ListenerRegistration? = null
    private var accountStatusListener: ListenerRegistration? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    val captainState: StateFlow<CaptainEntity?> = repository.captainFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val gpsStatus: StateFlow<GpsStatus> = CaptainLocationTracker.gpsStatus
    val currentLocation: StateFlow<Location?> = CaptainLocationTracker.currentLocation
    val isGpsTracking: StateFlow<Boolean> = CaptainLocationTracker.isTracking

    // Work Shift State & Management with Live Canonical Firestore Real-Time Updates
    private val _liveShifts = MutableStateFlow<List<ShiftEntity>>(emptyList())
    internal val liveShiftsState: StateFlow<List<ShiftEntity>> = _liveShifts.asStateFlow()
    private val _hasLiveShiftSnapshot = MutableStateFlow(false)
    val shifts: StateFlow<List<ShiftEntity>> = combine(
        repository.shiftsFlow,
        _liveShifts,
        _hasLiveShiftSnapshot
    ) { roomShifts, liveShifts, hasLiveSnapshot ->
        if (hasLiveSnapshot) liveShifts else roomShifts
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentCairoTime = MutableStateFlow(ShiftManager.getCurrentCairoTimeFormatted())
    val currentCairoTime: StateFlow<String> = _currentCairoTime.asStateFlow()

    private val _currentCairoDate = MutableStateFlow(ShiftManager.getCurrentCairoDateFormatted())
    val currentCairoDate: StateFlow<String> = _currentCairoDate.asStateFlow()

    private val _shiftStatus = MutableStateFlow<ShiftStatus>(ShiftStatus.NoSchedule())
    val shiftStatus: StateFlow<ShiftStatus> = _shiftStatus.asStateFlow()

    val isShiftNetworkConnected: StateFlow<Boolean> = repository.isShiftNetworkConnected
    val isShiftSynced: StateFlow<Boolean> = repository.isShiftSynced

    val weeklyWorkHours: StateFlow<Double> = shifts.map { ShiftManager.calculateWeeklyWorkHours(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val dailyWorkHours: StateFlow<Double> = combine(shifts, _currentCairoDate) { list, _ ->
        calculateDailyWorkHours(list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val activeOrder: StateFlow<OrderEntity?> = repository.activeOrderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Orders filtered by BOTH captain online status AND active working shift:
    // if captain is offline or outside shift hours, zero pending/offered orders are shown or assigned.
    val availableOrders: StateFlow<List<OrderEntity>> = combine(
        repository.availableOrdersFlow,
        captainState,
        _shiftStatus
    ) { orders, captain, status ->
        val isOnline = captain?.isOnline == true
        val isAccountActive = captain?.status?.trim()?.lowercase() !in listOf("banned", "suspended", "disabled", "blocked", "pending", "inactive")
        if (isOnline && isAccountActive && status.isAvailableForOrders) orders else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingIncomingOrder: StateFlow<OrderEntity?> = combine(
        repository.pendingOrderFlow,
        captainState,
        _shiftStatus
    ) { order, captain, status ->
        val isOnline = captain?.isOnline == true
        val isAccountActive = captain?.status?.trim()?.lowercase() !in listOf("banned", "suspended", "disabled", "blocked", "pending", "inactive")
        if (isOnline && isAccountActive && status.isAvailableForOrders) order else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allOrders: StateFlow<List<OrderEntity>> = repository.allOrdersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedOrders: StateFlow<List<OrderEntity>> = repository.completedOrdersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedCount: StateFlow<Int> = combine(
        allOrders,
        captainState
    ) { orders, captain ->
        val uid = auth?.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        val fromOrders = orders.count {
            it.status == OrderStatus.DELIVERED &&
                DailyWalletManager.isCaptainOwnOrder(it, uid, phone)
        }
        maxOf(fromOrders, captain?.totalOrdersCount ?: 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalEarnings: StateFlow<Double> = combine(
        allOrders,
        captainState
    ) { orders, captain ->
        val uid = auth?.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        val fromOrders = orders
            .filter {
                it.status == OrderStatus.DELIVERED &&
                    DailyWalletManager.isCaptainOwnOrder(it, uid, phone)
            }
            .sumOf { it.deliveryFee }
        maxOf(fromOrders, captain?.walletBalance ?: 0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // --- 1:00 PM Daily Wallet Rollover & Archived Order History Date Selection ---
    private val _currentOperationalDayKey = MutableStateFlow(DailyWalletManager.getOperationalDayKey())
    val currentOperationalDayKey: StateFlow<String> = _currentOperationalDayKey.asStateFlow()

    private val _todayCalendarDateKey = MutableStateFlow(OrderHistoryHelper.getTodayDateKey())
    val todayCalendarDateKey: StateFlow<String> = _todayCalendarDateKey.asStateFlow()

    // Selected calendar date ("yyyy-MM-dd") for Archived Order History — defaults strictly to TODAY
    private val _selectedHistoryDateKey = MutableStateFlow(OrderHistoryHelper.getTodayDateKey())
    val selectedHistoryDateKey: StateFlow<String> = _selectedHistoryDateKey.asStateFlow()

    // Legacy compatibility alias for WalletScreen day selection
    val selectedHistoryDayKey: StateFlow<String?> = _selectedHistoryDateKey.map<String, String?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _selectedHistoryDateKey.value)

    private val _selectedHistoryTab = MutableStateFlow(OrderHistoryTab.ALL)
    val selectedHistoryTab: StateFlow<OrderHistoryTab> = _selectedHistoryTab.asStateFlow()

    private val _isSyncingOrdersHistory = MutableStateFlow(false)
    val isSyncingOrdersHistory: StateFlow<Boolean> = _isSyncingOrdersHistory.asStateFlow()

    private val _historySyncError = MutableStateFlow<String?>(null)
    val historySyncError: StateFlow<String?> = _historySyncError.asStateFlow()

    val dailyWalletRecords: StateFlow<List<DailyWalletEntity>> = repository.dailyWalletsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val captainHistoryOrders: StateFlow<List<OrderEntity>> = combine(
        allOrders,
        captainState
    ) { orders, captain ->
        val uid = auth?.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        orders
            .filter { DailyWalletManager.isCaptainOwnOrder(it, uid, phone) }
            .sortedByDescending { DailyWalletManager.getOrderEffectiveTimestamp(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Archived finished orders (strictly DELIVERED and CANCELLED) for the logged-in captain
     * whose completion/cancellation timestamp falls within _selectedHistoryDateKey,
     * sorted descending (newest finished first).
     */
    val archivedOrdersForSelectedDate: StateFlow<List<OrderEntity>> = combine(
        allOrders,
        captainState,
        _selectedHistoryDateKey
    ) { orders, captain, dateKey ->
        val uid = auth?.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = orders,
            captainUid = uid,
            captainPhone = phone,
            selectedDateKey = dateKey
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredHistoryOrders: StateFlow<List<OrderEntity>> = combine(
        archivedOrdersForSelectedDate,
        _selectedHistoryTab
    ) { dateOrders, tab ->
        OrderHistoryHelper.filterByTab(dateOrders, tab)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historySummaryStats: StateFlow<OrderHistorySummaryStats> = combine(
        archivedOrdersForSelectedDate,
        _selectedHistoryTab
    ) { dateOrders, tab ->
        OrderHistoryHelper.computeSummaryStats(dateOrders, tab)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OrderHistorySummaryStats(0, 0, 0))

    val selectedDayOrders: StateFlow<List<OrderEntity>> = archivedOrdersForSelectedDate

    // Current day wallet balance: real delivery fees earned today (operational day or today's calendar date)
    val currentDayWalletBalance: StateFlow<Double> = combine(
        allOrders,
        captainState,
        _currentOperationalDayKey
    ) { orders, captain, currentDayKey ->
        val uid = auth?.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        val todayCalendarKey = DailyWalletManager.getCalendarDateKey(System.currentTimeMillis())

        orders
            .filter { order ->
                order.status == OrderStatus.DELIVERED &&
                    DailyWalletManager.isCaptainOwnOrder(order, uid, phone) &&
                    (
                        DailyWalletManager.getOrderOperationalDayKey(order) == currentDayKey ||
                        DailyWalletManager.getCalendarDateKey(DailyWalletManager.getOrderEffectiveTimestamp(order)) == todayCalendarKey
                    )
            }
            .sumOf { it.deliveryFee }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val currentDayCompletedCount: StateFlow<Int> = combine(
        allOrders,
        captainState,
        _currentOperationalDayKey
    ) { orders, captain, currentDayKey ->
        val uid = auth?.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        val todayCalendarKey = DailyWalletManager.getCalendarDateKey(System.currentTimeMillis())

        orders.count { order ->
            order.status == OrderStatus.DELIVERED &&
                DailyWalletManager.isCaptainOwnOrder(order, uid, phone) &&
                (
                    DailyWalletManager.getOrderOperationalDayKey(order) == currentDayKey ||
                    DailyWalletManager.getCalendarDateKey(DailyWalletManager.getOrderEffectiveTimestamp(order)) == todayCalendarKey
                )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Previous operational day's archived wallet balance (recorded at 1:00 PM rollover)
    val previousDayWalletBalance: StateFlow<Double> = combine(
        captainHistoryOrders,
        dailyWalletRecords,
        _currentOperationalDayKey
    ) { orders, records, currentDayKey ->
        val prevKey = DailyWalletManager.getPreviousOperationalDayKey(currentDayKey)
        val fromOrders = orders
            .filter {
                it.status == OrderStatus.DELIVERED &&
                    DailyWalletManager.getOrderOperationalDayKey(it) == prevKey
            }
            .sumOf { it.deliveryFee }
        if (fromOrders > 0.0) {
            fromOrders
        } else {
            records.find { it.dayKey == prevKey }?.walletBalance ?: 0.0
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val selectedDayWalletBalance: StateFlow<Double> = combine(
        captainHistoryOrders,
        dailyWalletRecords,
        _selectedHistoryDateKey,
        _currentOperationalDayKey
    ) { orders, records, selectedKey, currentDayKey ->
        if (selectedKey == currentDayKey) {
            orders
                .filter {
                    it.status == OrderStatus.DELIVERED &&
                        DailyWalletManager.getOrderOperationalDayKey(it) == currentDayKey
                }
                .sumOf { it.deliveryFee }
        } else {
            val fromOrders = orders
                .filter {
                    it.status == OrderStatus.DELIVERED &&
                        DailyWalletManager.getOrderOperationalDayKey(it) == selectedKey
                }
                .sumOf { it.deliveryFee }
            if (fromOrders > 0.0) {
                fromOrders
            } else {
                records.find { it.dayKey == selectedKey }?.walletBalance ?: 0.0
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val _eventFlow = MutableSharedFlow<UiEvent>()
    val eventFlow: SharedFlow<UiEvent> = _eventFlow.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSimulatingOrder = MutableStateFlow(false)
    val isSimulatingOrder: StateFlow<Boolean> = _isSimulatingOrder.asStateFlow()

    // --- Firebase Storage Upload States ---
    private val _profileUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val profileUploadState: StateFlow<ImageUploadState> = _profileUploadState.asStateFlow()

    private val _idFrontUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val idFrontUploadState: StateFlow<ImageUploadState> = _idFrontUploadState.asStateFlow()

    private val _idBackUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val idBackUploadState: StateFlow<ImageUploadState> = _idBackUploadState.asStateFlow()

    private val _licenseUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val licenseUploadState: StateFlow<ImageUploadState> = _licenseUploadState.asStateFlow()

    private val _receiptUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val receiptUploadState: StateFlow<ImageUploadState> = _receiptUploadState.asStateFlow()

    private val _orderActionError = MutableStateFlow<OrderActionError?>(null)
    val orderActionError: StateFlow<OrderActionError?> = _orderActionError.asStateFlow()

    private val _pendingNavigateTab = MutableStateFlow<Int?>(null)
    val pendingNavigateTab: StateFlow<Int?> = _pendingNavigateTab.asStateFlow()

    // Process-scoped splash & route state (survives backgrounding & config changes; resets on cold restart)
    private val _hasCompletedIntroSplash = MutableStateFlow(false)
    val hasCompletedIntroSplash: StateFlow<Boolean> = _hasCompletedIntroSplash.asStateFlow()

    private val _currentRoute = MutableStateFlow<String>(ScreenRoute.Splash.route)
    val currentRoute: StateFlow<String> = _currentRoute.asStateFlow()

    private val _unverifiedEmail = MutableStateFlow("")
    val unverifiedEmail: StateFlow<String> = _unverifiedEmail.asStateFlow()

    val accountApprovalState: StateFlow<CaptainApprovalSnapshot?> = repository.accountApprovalState

    private val _approvalFeedback = MutableStateFlow<String?>(null)
    val approvalFeedback: StateFlow<String?> = _approvalFeedback.asStateFlow()

    private var captainApprovalDocListener: ListenerRegistration? = null
    private var attachedApprovalUid: String? = null

    internal var startupSessionResolverOverride: (suspend () -> StartupSessionState)? = null
    internal var startupSessionCheckTimeoutMs: Long = STARTUP_SESSION_CHECK_TIMEOUT_MS

    // Resilient 1-second ticker test hooks
    internal var tickerRetryBackoffMs: Long = SHIFT_TRANSITION_RETRY_INTERVAL_MS
    internal var tickerShiftStatusOverride: (() -> ShiftStatus)? = null
    internal var tickerCaptainLoggedInOverride: (() -> Boolean)? = null
    internal var tickerCaptainOnlineOverride: (() -> Boolean)? = null
    internal var tickerShiftReadinessOverride: (suspend () -> Boolean)? = null
    internal var tickerLocationEligibilityOverride: (suspend () -> LocationEligibilityResult)? = null
    internal var tickerSetOnlineStatusOverride: (suspend (Boolean) -> Unit)? = null
    internal var setCaptainReadyStatusOverride: (suspend (Boolean) -> Unit)? = null
    internal var tickerSyncOrdersOverride: (suspend () -> Unit)? = null
    internal var tickerSyncPendingOfflineOverride: (suspend () -> Boolean)? = null

    internal class TickerLoopState(
        var previousLoadedShiftStatus: ShiftStatus? = null,
        var lastTransitionRetryAtMs: Long = 0L,
        var consecutiveTickerErrors: Int = 0,
        var tickCount: Int = 0,
        var lastClockUiUpdateMs: Long = 0L
    )

    // Startup auth/approval validation runs while the branded intro is visible.
    // The operational session remains gated until BOTH checks have completed.
    private var startupSessionCheckStarted = false
    private var startupSessionCheckFinished = false
    private var introVisualFinished = false
    private var resolvedStartupSession: StartupSessionState? = null

    private val expiredOfferNotifiedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun navigateTo(route: String) {
        if (route == ScreenRoute.Splash.route && _hasCompletedIntroSplash.value) {
            return
        }
        _currentRoute.value = route
    }

    fun setUnverifiedEmail(email: String) {
        _unverifiedEmail.value = email
    }

    fun consumePendingNavigateTab() {
        _pendingNavigateTab.value = null
    }

    fun dismissOrderActionError() {
        _orderActionError.value = null
    }

    init {
        val appContext = getApplication<Application>()
        ensureFirebaseInitialized()
        CaptainNotificationManager.ensureNotificationChannels(appContext)

        // Check shift boundaries every second, but only publish UI clock state every 15s and when displayed values change.
        viewModelScope.launch {
            val tickerState = TickerLoopState()
            while (true) {
                try {
                    runSingleTickerCycle(tickerState)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    tickerState.consecutiveTickerErrors++
                    Log.e("CaptainViewModel", "Ticker loop caught unexpected exception (continuing): ${e.message}", e)
                }
                delay(1000L)
            }
        }
        // Auto-sync shifts, historical orders & FCM token on launch/initialization only for verified users
        viewModelScope.launch {
            repository.reconcileDailyWallets()
            if (auth?.currentUser?.isEmailVerified == true) {
                val syncRes = repository.syncShiftsFromFirestore()
                if (syncRes.isSuccess) {
                    val synced = syncRes.getOrDefault(emptyList())
                    _liveShifts.value = synced
                    _hasLiveShiftSnapshot.value = true
                    _shiftStatus.value = ShiftManager.calculateShiftStatus(synced)
                }
                repository.syncFcmToken()
                repository.syncCaptainOrdersHistoryFromFirestore()
            }
        }
        // Keep persistent AlarmManager shift start/end notifications synced with latest schedule
        viewModelScope.launch {
            combine(captainState, shifts) { captain, shiftList ->
                Triple(captain?.uid.orEmpty().ifBlank { auth?.currentUser?.uid.orEmpty() }, captain?.isLoggedIn == true, shiftList)
            }
                .distinctUntilChanged()
                .collect { (uid, isLoggedIn, shiftList) ->
                    if (isLoggedIn && uid.isNotBlank() && shiftList.isNotEmpty()) {
                        ShiftNotificationScheduler.scheduleShiftNotifications(appContext, shiftList, uid)
                    } else {
                        ShiftNotificationScheduler.cancelAllShiftAlarms(appContext)
                    }
                }
        }
        // Notify captain immediately with continuous bell + 60s countdown when a new eligible order arrives
        viewModelScope.launch {
            combine(availableOrders, pendingIncomingOrder, captainState, _shiftStatus) { avail, incoming, captain, status ->
                val canNotify = captain?.isLoggedIn == true &&
                    captain.isOnline &&
                    status.isAvailableForOrders
                if (canNotify) {
                    (avail + listOfNotNull(incoming)).distinctBy { it.id }
                } else {
                    emptyList()
                }
            }.collect { eligiblePendingOrders ->
                if (eligiblePendingOrders.isEmpty()) {
                    CaptainNotificationManager.stopIncomingOrderBell(appContext)
                } else {
                    for (order in eligiblePendingOrders) {
                        val orderKey = order.firestoreDocId.ifBlank { order.id.toString() }
                        CaptainNotificationManager.showNewOrderNotification(
                            context = appContext,
                            orderKey = orderKey,
                            storeName = order.storeName,
                            pickupAddress = order.pickupAddress,
                            deliveryAddress = order.deliveryAddress,
                            deliveryFee = order.deliveryFee,
                            orderCode = order.cleanOrderCode,
                            storeLocationUrl = order.effectiveStoreLocationUrl,
                            orderId = order.id,
                            offerExpiresAtMs = order.offerExpiresAt,
                            distanceKm = order.distanceKm,
                            startContinuousBell = true
                        )
                    }
                }
            }
        }
        // Watchdog: enforce 60-second expiry + transfer to nearest captain even if alert dialog isn't mounted
        viewModelScope.launch {
            while (true) {
                val incoming = pendingIncomingOrder.value
                if (incoming != null && incoming.status == OrderStatus.PENDING && incoming.offerExpiresAt > 0L) {
                    val now = System.currentTimeMillis()
                    if (now >= incoming.offerExpiresAt) {
                        val key = incoming.firestoreDocId.ifBlank { incoming.id.toString() }
                        CaptainNotificationManager.stopIncomingOrderBell(appContext, key)
                        expireIncomingOrder(incoming.id)
                    }
                }
                delay(1000L)
            }
        }
        // Auto-clear lingering pending order offers and stop realtime orders listener whenever
        // captain is offline, outside shift hours, or lacks a server-confirmed working session.
        viewModelScope.launch {
            combine(
                captainState,
                _shiftStatus,
                shifts,
                repository.serverConfirmedWorkingSession
            ) { captain, status, shiftList, confirmedSession ->
                if (captain == null) {
                    null
                } else {
                    val online = captain.isOnline
                    val accountActive = captain.status.trim().lowercase() !in listOf("banned", "suspended", "disabled", "blocked", "pending", "inactive", "rejected")
                    val shiftAllows = if (shiftList.isNotEmpty()) status.isAvailableForOrders else false
                    val serverWorkingConfirmed = confirmedSession != null &&
                        confirmedSession.isConfirmedWorking &&
                        confirmedSession.uid == captain.uid
                    online && accountActive && shiftAllows && serverWorkingConfirmed
                }
            }
                .distinctUntilChanged()
                .collect { isEligible ->
                    if (isEligible == false) {
                        CaptainNotificationManager.stopIncomingOrderBell(appContext)
                        repository.stopRealtimeOrdersListener()
                        repository.clearPendingOrdersLocally()
                    } else if (isEligible == true && auth?.currentUser?.isEmailVerified == true) {
                        runCatching { repository.startRealtimeOrdersListener() }
                        repository.syncOrdersDetailedFromFirestore()
                    }
                }
        }
        // Tell the captain when the trip they were on is cancelled (merchant or admin); before, the
        // trip just vanished from the screen with no explanation.
        viewModelScope.launch {
            var previousActiveKey: String? = null
            activeOrder.collect { current ->
                val currentKey = current?.firestoreDocId?.ifBlank { current.id.toString() }
                val endedKey = previousActiveKey
                previousActiveKey = currentKey
                if (endedKey == null || endedKey == currentKey) return@collect
                val ended = repository.allOrdersFlow.first().find {
                    it.firestoreDocId == endedKey || it.id.toString() == endedKey
                } ?: return@collect
                if (ended.status != OrderStatus.CANCELLED) return@collect
                CaptainNotificationManager.showActiveOrderCancelledNotification(
                    context = appContext,
                    orderKey = endedKey,
                    storeName = ended.storeName,
                    orderCode = ended.cleanOrderCode,
                    reason = ended.cancellationReason
                )
                _eventFlow.emit(
                    UiEvent.ShowToast(
                        CaptainNotificationManager.buildActiveOrderCancelledMessage(
                            ended.storeName, ended.cleanOrderCode, ended.cancellationReason
                        )
                    )
                )
            }
        }
        // The location service is not restarted by Android after the app is killed or the captain
        // logs in while already marked online, so GPS went stale until the switch was toggled.
        // Wait for the startup session check: the cached profile can say "logged in" for a session
        // that check then rejects, and tracking must stop again when that happens.
        viewModelScope.launch {
            combine(captainState, _hasCompletedIntroSplash) { captain, sessionChecked ->
                if (!sessionChecked) null else captain != null && captain.isLoggedIn && captain.isOnline
            }
                .distinctUntilChanged()
                .collect { shouldTrack ->
                    when (shouldTrack) {
                        true -> resumeLocationTrackingIfNeeded()
                        false -> if (CaptainLocationTracker.isTracking.value) {
                            CaptainLocationTracker.stopService(getApplication())
                        }
                        null -> Unit
                    }
                }
        }
        // Set up real-time SnapshotListener on Firestore for captain shifts
        setupRealtimeShiftListener()
    }

    internal suspend fun runSingleTickerCycle(
        state: TickerLoopState,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val appContext = getApplication<Application>()
        state.tickCount++

        // Throttle Cairo clock/date StateFlow publishing to every 15 seconds while keeping 1s shift checks.
        if (state.lastClockUiUpdateMs == 0L || nowMs - state.lastClockUiUpdateMs >= 15_000L) {
            val nextTime = ShiftManager.getCurrentCairoTimeFormatted()
            if (_currentCairoTime.value != nextTime) _currentCairoTime.value = nextTime
            val nextDate = ShiftManager.getCurrentCairoDateFormatted()
            if (_currentCairoDate.value != nextDate) _currentCairoDate.value = nextDate
            state.lastClockUiUpdateMs = nowMs
        }

        val currentShiftsList = if (_hasLiveShiftSnapshot.value) _liveShifts.value else shifts.value
        val latestShiftStatus = tickerShiftStatusOverride?.invoke()
            ?: ShiftManager.calculateShiftStatus(currentShiftsList)
        if (_shiftStatus.value != latestShiftStatus) _shiftStatus.value = latestShiftStatus

        // Retry any deferred server-side offline sync once connectivity returns.
        try {
            if (tickerSyncPendingOfflineOverride != null) {
                tickerSyncPendingOfflineOverride!!.invoke()
            } else {
                repository.syncPendingOfflineStatusIfNeeded()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("CaptainViewModel", "Deferred offline sync retry notice: ${e.message}")
        }

        val hasShiftsLoaded = tickerShiftStatusOverride != null || currentShiftsList.isNotEmpty()
        val isLoggedIn = tickerCaptainLoggedInOverride?.invoke() ?: (captainState.value?.isLoggedIn == true)
        val isOnlineNow: () -> Boolean = { tickerCaptainOnlineOverride?.invoke() ?: (captainState.value?.isOnline == true) }

        if (hasShiftsLoaded && isLoggedIn) {
            val prev = state.previousLoadedShiftStatus
            val dedupeKey = when (latestShiftStatus) {
                is ShiftStatus.UpcomingSoon -> ShiftNotificationScheduler.buildTodayDedupeKey(latestShiftStatus.shift, isStart = true).removeSuffix("_start") + "_ready"
                is ShiftStatus.Active -> ShiftNotificationScheduler.buildTodayDedupeKey(latestShiftStatus.shift, isStart = true)
                else -> ""
            }

            if (latestShiftStatus is ShiftStatus.UpcomingSoon &&
                latestShiftStatus.millisUntilStart in 1L..(30L * 60_000L) &&
                (prev !is ShiftStatus.UpcomingSoon || prev.shift.id != latestShiftStatus.shift.id ||
                    prev.millisUntilStart > 30L * 60_000L)
            ) {
                CaptainNotificationManager.showShiftReadyWindowNotification(
                    context = appContext,
                    dayNameArabic = latestShiftStatus.shift.dayNameArabic,
                    startFormatted = latestShiftStatus.startFormatted,
                    endFormatted = latestShiftStatus.endFormatted,
                    dedupeKey = dedupeKey
                )
            }

            var advancePreviousStatus = true

            if (prev !is ShiftStatus.Active && latestShiftStatus is ShiftStatus.Active) {
                val backoffActive = state.lastTransitionRetryAtMs > 0L &&
                    (nowMs - state.lastTransitionRetryAtMs) < tickerRetryBackoffMs
                if (backoffActive) {
                    advancePreviousStatus = false
                } else {
                    CaptainNotificationManager.showShiftStartNotification(
                        context = appContext,
                        dayNameArabic = latestShiftStatus.shift.dayNameArabic,
                        startFormatted = latestShiftStatus.startFormatted,
                        endFormatted = latestShiftStatus.endFormatted,
                        dedupeKey = ShiftNotificationScheduler.buildTodayDedupeKey(latestShiftStatus.shift, isStart = true)
                    )
                    var shiftStartDeferredDueToNetwork = false
                    val captainCurrentlyOnline = isOnlineNow()

                    val wasReady = if (captainCurrentlyOnline) {
                        try {
                            tickerShiftReadinessOverride?.invoke() ?: repository.hasConfirmedShiftReadiness()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            state.consecutiveTickerErrors++
                            state.lastTransitionRetryAtMs = nowMs
                            shiftStartDeferredDueToNetwork = true
                            Log.w("CaptainViewModel", "Network error checking shift readiness at shift start; will retry: ${e.message}")
                            false
                        }
                    } else {
                        false
                    }

                    val alreadyWorking = if (!shiftStartDeferredDueToNetwork && !wasReady && captainCurrentlyOnline) {
                        val eligibility = try {
                            tickerLocationEligibilityOverride?.invoke() ?: repository.checkLocationTrackingEligibility()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            LocationEligibilityResult.NetworkError(e)
                        }
                        when (eligibility) {
                            is LocationEligibilityResult.Eligible -> true
                            is LocationEligibilityResult.NetworkError -> {
                                state.consecutiveTickerErrors++
                                state.lastTransitionRetryAtMs = nowMs
                                shiftStartDeferredDueToNetwork = true
                                Log.w("CaptainViewModel", "Network error checking working state at shift start; will retry: ${eligibility.cause.message}")
                                false
                            }
                            is LocationEligibilityResult.Ineligible -> false
                        }
                    } else {
                        false
                    }

                    var workingTransitionConfirmed = false
                    var promotionFailureError: Throwable? = null

                    if (!shiftStartDeferredDueToNetwork) {
                        if (wasReady) {
                            if (!CaptainLocationTracker.hasLocationPermission(appContext)) {
                                _eventFlow.emit(UiEvent.RequestLocationPermission)
                            } else if (!CaptainLocationTracker.isGpsProviderEnabled(appContext)) {
                                _eventFlow.emit(UiEvent.RequestGpsEnable)
                            } else {
                                val promoteSucceeded = try {
                                    if (tickerSetOnlineStatusOverride != null) {
                                        tickerSetOnlineStatusOverride!!.invoke(true)
                                    } else {
                                        repository.setOnlineStatus(true)
                                    }
                                    true
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    if (repository.isConfirmedPresenceRejection(e)) {
                                        promotionFailureError = e
                                        CaptainLocationTracker.stopService(appContext)
                                        repository.revertLocalOnlineStatusToOffline()
                                        Log.e("CaptainViewModel", "Server rejected ready->working transition at shift start", e)
                                    } else {
                                        state.consecutiveTickerErrors++
                                        state.lastTransitionRetryAtMs = nowMs
                                        shiftStartDeferredDueToNetwork = true
                                        Log.w("CaptainViewModel", "Transient error promoting captain to working at shift start; will retry: ${e.message}")
                                    }
                                    false
                                }

                                if (promoteSucceeded) {
                                    val postPromoteEligibility = try {
                                        tickerLocationEligibilityOverride?.invoke()
                                            ?: repository.checkLocationTrackingEligibility()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (e: Exception) {
                                        LocationEligibilityResult.NetworkError(e)
                                    }
                                    when (postPromoteEligibility) {
                                        is LocationEligibilityResult.Eligible -> {
                                            workingTransitionConfirmed = true
                                            CaptainLocationTracker.startServiceSafely(appContext)
                                            runCatching { repository.startRealtimeOrdersListener() }
                                            try {
                                                if (tickerSyncOrdersOverride != null) {
                                                    tickerSyncOrdersOverride!!.invoke()
                                                } else {
                                                    repository.syncOrdersDetailedFromFirestore()
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (e: Exception) {
                                                Log.w("CaptainViewModel", "Order sync after shift start failed: ${e.message}")
                                            }
                                        }
                                        is LocationEligibilityResult.NetworkError -> {
                                            state.consecutiveTickerErrors++
                                            state.lastTransitionRetryAtMs = nowMs
                                            shiftStartDeferredDueToNetwork = true
                                        }
                                        is LocationEligibilityResult.Ineligible -> {
                                            CaptainLocationTracker.stopService(appContext)
                                            repository.revertLocalOnlineStatusToOffline()
                                        }
                                    }
                                }
                            }
                        } else if (alreadyWorking) {
                            // Re-open a previously working session without asking the captain to confirm again.
                            runCatching { repository.startRealtimeOrdersListener() }
                            if (!CaptainLocationTracker.isTracking.value &&
                                CaptainLocationTracker.hasLocationPermission(appContext) &&
                                CaptainLocationTracker.isGpsProviderEnabled(appContext)
                            ) {
                                CaptainLocationTracker.startServiceSafely(appContext)
                            }
                        } else if (captainCurrentlyOnline) {
                            // A stale online flag is never treated as proof of readiness.
                            try {
                                if (tickerSetOnlineStatusOverride != null) {
                                    tickerSetOnlineStatusOverride!!.invoke(false)
                                } else {
                                    repository.setOnlineStatus(false)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                Log.w("CaptainViewModel", "Error clearing stale online status at shift start: ${e.message}")
                            }
                        }
                    }

                    if (shiftStartDeferredDueToNetwork) {
                        advancePreviousStatus = false
                    } else {
                        state.consecutiveTickerErrors = 0
                        state.lastTransitionRetryAtMs = 0L
                        val failedErr = promotionFailureError
                        _eventFlow.emit(
                            UiEvent.ShowToast(
                                when {
                                    failedErr != null -> mapPresenceErrorToArabicMessage(
                                        error = failedErr,
                                        fallback = "تعذر تحويل حالتك إلى يعمل عند بداية الشفت. حاول مرة أخرى."
                                    )
                                    wasReady && workingTransitionConfirmed ->
                                        "بدأ شفتك. تم تحويل حالتك إلى يعمل الآن."
                                    wasReady && (!CaptainLocationTracker.hasLocationPermission(appContext) ||
                                        !CaptainLocationTracker.isGpsProviderEnabled(appContext)) ->
                                        "أنت جاهز، لكن يلزم منح إذن الموقع وتشغيل GPS قبل بدء استقبال الطلبات."
                                    wasReady ->
                                        "تعذر تأكيد تفعيل العمل من الخادم عند بداية الشفت. حاول مرة أخرى."
                                    alreadyWorking -> "تم استعادة جلسة عملك الحالية."
                                    else -> "بدأ شفتك. لم يتم تسجيل تأكيد جاهزية لهذا الشفت."
                                }
                            )
                        )
                    }
                }
            } else if (prev is ShiftStatus.Active && latestShiftStatus !is ShiftStatus.Active) {
                CaptainLocationTracker.stopService(appContext)
                if (isOnlineNow()) {
                    try {
                        if (tickerSetOnlineStatusOverride != null) {
                            tickerSetOnlineStatusOverride!!.invoke(false)
                        } else {
                            repository.setOnlineStatus(false)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        state.consecutiveTickerErrors++
                        Log.w("CaptainViewModel", "Shift-end offline server sync failed (deferred for retry): ${e.message}")
                    }
                }
                CaptainNotificationManager.showShiftEndNotification(
                    context = appContext,
                    dayNameArabic = prev.shift.dayNameArabic,
                    endFormatted = prev.endFormatted,
                    dedupeKey = ShiftNotificationScheduler.buildTodayDedupeKey(prev.shift, isStart = false)
                )
                _eventFlow.emit(
                    UiEvent.ShowToast("انتهى شفت العمل. تم إيقاف استقبال الطلبات وتتبع الموقع.")
                )
            }

            if (prev == null && isOnlineNow() &&
                latestShiftStatus !is ShiftStatus.Active &&
                !(latestShiftStatus is ShiftStatus.UpcomingSoon &&
                    latestShiftStatus.millisUntilStart in 1L..(30L * 60_000L))
            ) {
                // Clear stale online state left by an older app version or a missed shift end.
                CaptainLocationTracker.stopService(appContext)
                try {
                    if (tickerSetOnlineStatusOverride != null) {
                        tickerSetOnlineStatusOverride!!.invoke(false)
                    } else {
                        repository.setOnlineStatus(false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w("CaptainViewModel", "Error clearing stale outside-shift online state: ${e.message}")
                }
            }

            if (advancePreviousStatus) {
                state.previousLoadedShiftStatus = latestShiftStatus
            }
        }

        val latestCalendarToday = OrderHistoryHelper.getTodayDateKey()
        if (latestCalendarToday != _todayCalendarDateKey.value) {
            val prevToday = _todayCalendarDateKey.value
            _todayCalendarDateKey.value = latestCalendarToday
            if (_selectedHistoryDateKey.value == prevToday) {
                _selectedHistoryDateKey.value = latestCalendarToday
            }
        }

        val latestOpDay = DailyWalletManager.getOperationalDayKey()
        if (latestOpDay != _currentOperationalDayKey.value) {
            _currentOperationalDayKey.value = latestOpDay
            try {
                repository.reconcileDailyWallets()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("CaptainViewModel", "Daily wallet rollover reconcile notice: ${e.message}")
            }
            _eventFlow.emit(
                UiEvent.ShowToast("الساعة 1:00 م: تم تسجيل رصيد المحفظة في اليوم السابق وبدء محفظة اليوم الجديد برصيد 0 ج.م")
            )
        }
    }

    private fun resumeLocationTrackingIfNeeded() {
        val app = getApplication<Application>()
        if (CaptainLocationTracker.isTracking.value) return
        if (auth?.currentUser?.isEmailVerified != true) return
        viewModelScope.launch {
            // Do not ask the server to promote Ready to Working until GPS permission/provider exist.
            if (!CaptainLocationTracker.hasLocationPermission(app)) {
                _eventFlow.emit(UiEvent.RequestLocationPermission)
                return@launch
            }
            if (!CaptainLocationTracker.isGpsProviderEnabled(app)) {
                _eventFlow.emit(UiEvent.RequestGpsEnable)
                return@launch
            }
            when (val eligibility = repository.checkLocationTrackingEligibility()) {
                is LocationEligibilityResult.Eligible -> {
                    runCatching { repository.startRealtimeOrdersListener() }
                    CaptainLocationTracker.startServiceSafely(app)
                }
                is LocationEligibilityResult.Ineligible -> {
                    Log.i("CaptainViewModel", "Skipping location service resume (confirmed ineligible: ${eligibility.reason})")
                    repository.stopRealtimeOrdersListener()
                    CaptainLocationTracker.stopService(app)
                }
                is LocationEligibilityResult.NetworkError -> {
                    // Never stop/offline captain on transient network errors; keep state intact for retry.
                    Log.w("CaptainViewModel", "Could not verify GPS resume eligibility due to network error: ${eligibility.cause.message}")
                }
            }
        }
    }

    /**
     * Applies an admin-side status change (approval, suspension) while the app is open.
     * Without this the app kept the status it read at login until it was restarted.
     */
    private suspend fun onRemoteAccountStatus(remoteStatus: String) {
        val local = captainState.value ?: repository.getCaptain() ?: return
        val previous = local.status.trim().lowercase()
        if (previous == remoteStatus) return
        repository.applyRemoteAccountStatus(remoteStatus)
        val app = getApplication<Application>()
        when {
            remoteStatus in BLOCKED_ACCOUNT_STATUSES -> {
                CaptainNotificationManager.stopIncomingOrderBell(app)
                if (local.isOnline) {
                    CaptainLocationTracker.stopService(app)
                    repository.setOnlineStatus(false)
                }
                _eventFlow.emit(UiEvent.ShowToast("تم إيقاف حسابك من الإدارة، ولن تصلك طلبات جديدة. تواصل مع الإدارة."))
            }
            remoteStatus !in INACTIVE_ACCOUNT_STATUSES && previous in INACTIVE_ACCOUNT_STATUSES -> {
                _eventFlow.emit(UiEvent.ShowToast("تم تفعيل حسابك من الإدارة ✅ يمكنك الآن استقبال الطلبات."))
            }
        }
    }

    /**
     * Called when opening the Order History screen ("سجل الطلبات") directly so it always
     * selects today's date automatically and displays only today's finished orders.
     */
    fun openOrdersHistoryToday() {
        val today = OrderHistoryHelper.getTodayDateKey()
        _todayCalendarDateKey.value = today
        _selectedHistoryDateKey.value = today
        _selectedHistoryTab.value = OrderHistoryTab.ALL
        _historySyncError.value = null
        if (auth?.currentUser?.isEmailVerified == true) {
            syncOrdersHistory(silentSuccess = true)
        }
    }

    fun selectHistoryDate(dateKey: String) {
        val today = OrderHistoryHelper.getTodayDateKey()
        _todayCalendarDateKey.value = today
        if (OrderHistoryHelper.isFutureDateKey(dateKey, today)) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast("لا يمكن اختيار تاريخ مستقبلي في سجل الطلبات"))
            }
            return
        }
        val clamped = OrderHistoryHelper.clampToMaxToday(dateKey, today)
        _selectedHistoryDateKey.value = clamped
        _historySyncError.value = null
        if (auth?.currentUser?.isEmailVerified == true) {
            syncOrdersHistory(silentSuccess = true)
        }
    }

    fun resetHistoryDateToToday() {
        val today = OrderHistoryHelper.getTodayDateKey()
        _todayCalendarDateKey.value = today
        _selectedHistoryDateKey.value = today
        _historySyncError.value = null
        if (auth?.currentUser?.isEmailVerified == true) {
            syncOrdersHistory(silentSuccess = true)
        }
    }

    fun selectHistoryTab(tab: OrderHistoryTab) {
        _selectedHistoryTab.value = tab
    }

    fun selectHistoryDay(dayKey: String?) {
        val target = dayKey ?: OrderHistoryHelper.getTodayDateKey()
        selectHistoryDate(target)
    }

    fun shiftSelectedHistoryDay(deltaDays: Int) {
        val today = OrderHistoryHelper.getTodayDateKey()
        val candidate = OrderHistoryHelper.shiftDateKey(_selectedHistoryDateKey.value, deltaDays)
        if (!OrderHistoryHelper.isFutureDateKey(candidate, today)) {
            selectHistoryDate(candidate)
        }
    }

    fun syncOrdersHistory(silentSuccess: Boolean = false) {
        viewModelScope.launch {
            _isSyncingOrdersHistory.value = true
            _historySyncError.value = null
            val targetDate = _selectedHistoryDateKey.value
            val res = repository.syncCaptainArchivedOrdersForDate(targetDate)
            _isSyncingOrdersHistory.value = false
            if (res.isSuccess) {
                if (!silentSuccess) {
                    _eventFlow.emit(UiEvent.ShowToast("تم تحديث سجل الطلبات المنتهية بنجاح"))
                }
            } else {
                val errMsg = res.exceptionOrNull()?.localizedMessage
                    ?: "تعذر الاتصال بقاعدة البيانات لجلب سجل الطلبات. تحقق من الإنترنت وأعد المحاولة."
                _historySyncError.value = errMsg
                if (!silentSuccess) {
                    _eventFlow.emit(UiEvent.ShowToast(errMsg))
                }
            }
        }
    }

    @Volatile
    private var isUpdatingOnlineStatus: Boolean = false

    @Volatile
    private var pendingStartWorkAfterGpsEnable: Boolean = false

    internal fun mapPresenceErrorToArabicMessage(error: Throwable, fallback: String): String {
        if (error is CaptainPresenceException) {
            return error.userMessage
        }
        if (error is TimeoutCancellationException) {
            return "انتهت مهلة الاتصال بالخادم. تحقق من اتصال الإنترنت وحاول مرة أخرى."
        }
        if (error is FirebaseFunctionsException) {
            return when (error.code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    "انتهت صلاحية الجلسة. يرجى إعادة تسجيل الدخول ثم المحاولة مرة أخرى."
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    "لا تملك صلاحية تفعيل العمل حالياً. تأكد من اعتماد حسابك من الإدارة."
                FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                    error.message?.takeIf { it.isNotBlank() && it.any { ch -> ch in '\u0600'..'\u06FF' } }
                        ?: "تعذر تفعيل العمل حالياً. تأكد من أنك داخل وقت الشفت المعتمد وحاول مرة أخرى."
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "تعذر الوصول إلى الخادم حالياً. تحقق من اتصال الإنترنت وحاول مرة أخرى."
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.UNIMPLEMENTED ->
                    "خدمة تفعيل الشفت غير متاحة مؤقتاً على الخادم. يرجى المحاولة لاحقاً."
                FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                    "بيانات تفعيل الشفت غير صالحة. يرجى إعادة المحاولة."
                else -> fallback
            }
        }
        if (error is IOException || error is FirebaseNetworkException) {
            return "تعذر بدء العمل حالياً بسبب انقطاع الإنترنت. تحقق من الاتصال وحاول مرة أخرى."
        }
        return fallback
    }

    private suspend fun activateShiftWorkSafely(
        app: Application,
        callReadyFirst: Boolean,
        successToast: String,
        ineligibleToast: String
    ) {
        val wasOfflineBeforeActivation = (repository.getCaptain()?.isOnline ?: (captainState.value?.isOnline == true)) != true
        try {
            if (callReadyFirst) {
                setCaptainReadyStatusOverride?.invoke(true) ?: repository.setCaptainReadyStatus(true)
            }
            tickerSetOnlineStatusOverride?.invoke(true) ?: repository.setOnlineStatus(true)
            when (val eligibility = tickerLocationEligibilityOverride?.invoke() ?: repository.checkLocationTrackingEligibility()) {
                is LocationEligibilityResult.Eligible -> {
                    val startResult = CaptainLocationTracker.startServiceSafely(app)
                    runCatching { repository.startRealtimeOrdersListener() }
                    if (tickerSyncOrdersOverride != null) {
                        try {
                            tickerSyncOrdersOverride?.invoke()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.w("CaptainViewModel", "Order sync override after starting work failed: ${e.message}")
                        }
                    } else if (auth?.currentUser?.isEmailVerified == true) {
                        try {
                            repository.syncOrdersDetailedFromFirestore()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.w("CaptainViewModel", "Order sync after starting work failed: ${e.message}")
                        }
                    }
                    when (startResult) {
                        is LocationServiceStartResult.Started -> {
                            _eventFlow.emit(UiEvent.ShowToast(successToast))
                        }
                        is LocationServiceStartResult.BlockedByBackgroundRestriction -> {
                            _eventFlow.emit(
                                UiEvent.ShowToast("تم تفعيل وضع متصل، يرجى إبقاء التطبيق مفتوحاً في الواجهة لتشغيل تتبع الموقع.")
                            )
                        }
                        is LocationServiceStartResult.Failed -> {
                            _eventFlow.emit(
                                UiEvent.ShowToast("تم تفعيل وضع متصل، لكن تعذر تشغيل خدمة الموقع حالياً. أعد المحاولة.")
                            )
                        }
                    }
                }
                is LocationEligibilityResult.NetworkError -> {
                    if (wasOfflineBeforeActivation) {
                        repository.revertLocalOnlineStatusToOffline()
                    }
                    CaptainLocationTracker.stopService(app)
                    Log.w("CaptainViewModel", "Network error verifying work eligibility: ${eligibility.cause.message}")
                    _eventFlow.emit(
                        UiEvent.ShowToast("تعذر التحقق من حالة الشفت مع الخادم. تحقق من اتصال الإنترنت وحاول مرة أخرى.")
                    )
                }
                is LocationEligibilityResult.Ineligible -> {
                    repository.revertLocalOnlineStatusToOffline()
                    CaptainLocationTracker.stopService(app)
                    Log.w("CaptainViewModel", "Captain ineligible to start GPS tracking: ${eligibility.reason}")
                    _eventFlow.emit(UiEvent.ShowToast(ineligibleToast))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (wasOfflineBeforeActivation || repository.isConfirmedPresenceRejection(e)) {
                repository.revertLocalOnlineStatusToOffline()
            }
            CaptainLocationTracker.stopService(app)
            Log.e("CaptainViewModel", "Failed to start shift work", e)
            _eventFlow.emit(
                UiEvent.ShowToast(
                    mapPresenceErrorToArabicMessage(
                        error = e,
                        fallback = "تعذر بدء العمل حالياً. تحقق من اتصال الإنترنت وحاول مرة أخرى."
                    )
                )
            )
        }
    }

    fun toggleOnlineStatus() {
        val app = getApplication<Application>()
        val status = _shiftStatus.value
        if (isUpdatingOnlineStatus) return

        viewModelScope.launch {
            isUpdatingOnlineStatus = true
            try {
                val currentlyOnline = repository.getCaptain()?.isOnline ?: (captainState.value?.isOnline == true)
                val isApproved = repository.accountApprovalState.value == null || repository.isCurrentSessionApproved

                if (currentlyOnline) {
                    if (status is ShiftStatus.Active && isApproved) {
                        if (!CaptainLocationTracker.hasLocationPermission(app)) {
                            _eventFlow.emit(UiEvent.RequestLocationPermission)
                            return@launch
                        }
                        if (!CaptainLocationTracker.isGpsProviderEnabled(app)) {
                            pendingStartWorkAfterGpsEnable = true
                            _eventFlow.emit(UiEvent.RequestGpsEnable)
                            _eventFlow.emit(UiEvent.ShowToast("يرجى تشغيل الموقع (GPS) لبدء العمل واستقبال الطلبات."))
                            return@launch
                        }
                        val alreadyServerWorking = try {
                            (tickerLocationEligibilityOverride?.invoke()
                                ?: repository.checkLocationTrackingEligibility()) is LocationEligibilityResult.Eligible
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.w("CaptainViewModel", "Error checking working status while toggling online status: ${e.message}")
                            false
                        }
                        val alreadyServerReady = if (alreadyServerWorking) {
                            true
                        } else {
                            try {
                                tickerShiftReadinessOverride?.invoke() ?: repository.hasConfirmedShiftReadiness()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                Log.w("CaptainViewModel", "Error checking readiness while toggling online status: ${e.message}")
                                false
                            }
                        }
                        if (!alreadyServerWorking || !CaptainLocationTracker.isTracking.value) {
                            activateShiftWorkSafely(
                                app = app,
                                callReadyFirst = !alreadyServerReady,
                                successToast = "أنت الآن متصل وتم فتح الموقع (GPS) لاستقبال الطلبات.",
                                ineligibleToast = "تعذر تفعيل العمل وGPS حالياً. تأكد من تأكيد الجاهزية وحاول مرة أخرى."
                            )
                            return@launch
                        }
                    }

                    pendingStartWorkAfterGpsEnable = false
                    CaptainNotificationManager.stopIncomingOrderBell(app)
                    CaptainLocationTracker.stopService(app)
                    if (status is ShiftStatus.UpcomingSoon) {
                        try {
                            repository.setCaptainReadyStatus(false)
                            _eventFlow.emit(UiEvent.ShowToast("تم إلغاء تأكيد الجاهزية لهذا الشفت."))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.w("CaptainViewModel", "Error cancelling shift readiness remotely; kept local offline", e)
                            _eventFlow.emit(UiEvent.ShowToast("تم إلغاء الجاهزية محلياً. سيتم مزامنة الحالة عند عودة الاتصال."))
                        }
                    } else {
                        try {
                            repository.setOnlineStatus(false)
                            _eventFlow.emit(UiEvent.ShowToast("أنت الآن غير متصل. تم إيقاف استقبال الطلبات وتتبع الموقع."))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.w("CaptainViewModel", "Error updating offline status", e)
                            _eventFlow.emit(UiEvent.ShowToast("تم تحويلك إلى غير متصل محلياً. سيتم المزامنة مع الخادم لاحقاً."))
                        }
                    }
                    return@launch
                }

                if (!isApproved) {
                    _eventFlow.emit(UiEvent.ShowToast("حسابك غير معتمد حالياً من الإدارة ولا يمكنك تأكيد الجاهزية."))
                    return@launch
                }

                when (status) {
                    is ShiftStatus.UpcomingSoon -> {
                        if (status.millisUntilStart > 30L * 60_000L) {
                            _eventFlow.emit(UiEvent.ShowToast("زر جاهز سيتاح قبل بداية الشفت بـ30 دقيقة فقط."))
                            return@launch
                        }
                        try {
                            setCaptainReadyStatusOverride?.invoke(true) ?: repository.setCaptainReadyStatus(true)
                            _eventFlow.emit(UiEvent.ShowToast("تم تسجيلك جاهزاً. سيتم تفعيل العمل وGPS تلقائياً عند بداية الشفت."))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.e("CaptainViewModel", "Failed to confirm pre-shift readiness", e)
                            _eventFlow.emit(
                                UiEvent.ShowToast(
                                    mapPresenceErrorToArabicMessage(
                                        error = e,
                                        fallback = "تعذر تسجيل الجاهزية. تحقق من اتصال الإنترنت وحاول مرة أخرى."
                                    )
                                )
                            )
                        }
                    }
                    is ShiftStatus.Active -> {
                        if (!CaptainLocationTracker.hasLocationPermission(app)) {
                            _eventFlow.emit(UiEvent.RequestLocationPermission)
                            return@launch
                        }
                        if (!CaptainLocationTracker.isGpsProviderEnabled(app)) {
                            pendingStartWorkAfterGpsEnable = true
                            _eventFlow.emit(UiEvent.RequestGpsEnable)
                            _eventFlow.emit(UiEvent.ShowToast("يرجى تشغيل الموقع (GPS) لبدء العمل واستقبال الطلبات."))
                            return@launch
                        }
                        pendingStartWorkAfterGpsEnable = false
                        activateShiftWorkSafely(
                            app = app,
                            callReadyFirst = true,
                            successToast = "أنت الآن متصل وتم فتح الموقع (GPS) لاستقبال الطلبات.",
                            ineligibleToast = "تعذر بدء العمل حالياً. تأكد من اعتماد حسابك ووجود شفت نشط ثم أعد المحاولة."
                        )
                    }
                    else -> {
                        _eventFlow.emit(UiEvent.ShowToast("لا يمكنك الاتصال خارج الشفت. سيظهر زر جاهز قبل بداية الشفت بـ30 دقيقة."))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e("CaptainViewModel", "Unexpected error toggling online status", e)
                _eventFlow.emit(UiEvent.ShowToast("حدث خطأ أثناء تحديث حالة الاتصال. حاول مرة أخرى."))
            } finally {
                isUpdatingOnlineStatus = false
            }
        }
    }

    fun onLocationPermissionGranted() {
        val app = getApplication<Application>()
        if (repository.accountApprovalState.value != null && !repository.isCurrentSessionApproved) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast("حسابك غير معتمد حالياً من الإدارة ولا يمكنك بدء العمل."))
            }
            return
        }
        if (!CaptainLocationTracker.isGpsProviderEnabled(app)) {
            pendingStartWorkAfterGpsEnable = true
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.RequestGpsEnable)
                _eventFlow.emit(UiEvent.ShowToast("تم منح الإذن. يرجى تشغيل الموقع (GPS) لبدء العمل."))
            }
            return
        }
        pendingStartWorkAfterGpsEnable = false
        if (isUpdatingOnlineStatus) return
        viewModelScope.launch {
            isUpdatingOnlineStatus = true
            try {
                if (_shiftStatus.value !is ShiftStatus.Active) {
                    _eventFlow.emit(UiEvent.ShowToast("تم منح إذن الموقع. لن يبدأ التتبع قبل بداية الشفت وتأكيد الجاهزية."))
                    return@launch
                }
                val alreadyServerReady = try {
                    tickerShiftReadinessOverride?.invoke() ?: repository.hasConfirmedShiftReadiness()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                activateShiftWorkSafely(
                    app = app,
                    callReadyFirst = !alreadyServerReady,
                    successToast = "أنت الآن متصل وتم فتح الموقع (GPS) لاستقبال الطلبات.",
                    ineligibleToast = "لا يمكن تشغيل GPS قبل تأكيد الجاهزية وداخل الشفت."
                )
            } finally {
                isUpdatingOnlineStatus = false
            }
        }
    }

    fun onLocationPermissionDenied() {
        viewModelScope.launch {
            _eventFlow.emit(UiEvent.ShowToast("لا يمكن تفعيل وضع (متصل) واستقبال الطلبات دون إذن الموقع GPS"))
        }
    }

    fun login(
        phoneOrEmail: String,
        pass: String,
        onSuccess: () -> Unit,
        onNeedsEmailVerification: (String) -> Unit = {},
        onAwaitingApproval: (CaptainApprovalSnapshot) -> Unit = {},
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (phoneOrEmail.isBlank() || pass.isBlank()) {
                onError("يرجى إدخال البريد الإلكتروني أو رقم الهاتف وكلمة المرور")
                return@launch
            }
            _isLoading.value = true
            _approvalFeedback.value = null
            try {
                when (val outcome = repository.login(phoneOrEmail, pass)) {
                    is LoginOutcome.Success -> {
                        attachCaptainApprovalListenerForCurrentUser()
                        repository.syncFcmToken()
                        _eventFlow.emit(UiEvent.ShowToast("أهلاً بك يا كابتن! تم تسجيل الدخول بنجاح"))
                        onSuccess()
                    }
                    is LoginOutcome.EmailNotVerified -> {
                        onNeedsEmailVerification(outcome.email)
                    }
                    is LoginOutcome.AwaitingApproval -> {
                        attachCaptainApprovalListenerForCurrentUser()
                        _currentRoute.value = ScreenRoute.AccountApproval.route
                        onAwaitingApproval(outcome.approval)
                    }
                    is LoginOutcome.Error -> {
                        onError(outcome.message)
                    }
                }
            } catch (e: Exception) {
                onError("تعذر الاتصال: ${e.localizedMessage ?: "خطأ في الشبكة"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendPasswordReset(
        phoneOrEmail: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (phoneOrEmail.isBlank()) {
                onError("يرجى إدخال البريد الإلكتروني أو رقم الهاتف")
                return@launch
            }
            _isLoading.value = true
            try {
                val res = repository.sendPasswordReset(phoneOrEmail)
                if (res.isSuccess) {
                    val msg = res.getOrDefault("تم إرسال رابط استعادة كلمة السر بنجاح")
                    _eventFlow.emit(UiEvent.ShowToast(msg))
                    onSuccess(msg)
                } else {
                    onError(res.exceptionOrNull()?.localizedMessage ?: "تعذر إرسال رابط الاستعادة")
                }
            } catch (e: Exception) {
                onError("خطأ: ${e.localizedMessage ?: "تعذر الاتصال"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signUp(
        fullName: String,
        phone: String,
        email: String,
        pass: String,
        confirmPass: String = "",
        address: String = "",
        avatarUri: String = "",
        avatarPresetIndex: Int = 0,
        vehicleType: String = "سكوتر / دراجة نارية",
        nationalIdUri: String = "",
        idBackUri: String = "",
        licenseUri: String = "",
        onEmailSent: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (fullName.isBlank()) {
                onError("يرجى إدخال اسم الكابتن الكامل")
                return@launch
            }
            if (phone.length < 8) {
                onError("يرجى إدخال رقم هاتف صحيح")
                return@launch
            }
            if (email.isBlank()) {
                onError("البريد الإلكتروني إلزامي لتأكيد الحساب وتفعيله")
                return@launch
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                onError("صيغة البريد الإلكتروني غير صحيحة (مثال: captain@example.com)")
                return@launch
            }
            if (pass.length < 6) {
                onError("كلمة المرور يجب أن تكون 6 أحرف أو أرقام على الأقل")
                return@launch
            }
            if (confirmPass.isNotBlank() && pass != confirmPass) {
                onError("كلمة المرور وتأكيد كلمة المرور غير متطابقتين")
                return@launch
            }
            if ((nationalIdUri.isNotBlank() && idBackUri.isBlank()) || (nationalIdUri.isBlank() && idBackUri.isNotBlank())) {
                onError("يرجى رفع الوجه الأمامي والوجه الخلفي للبطاقة الشخصية معاً لاكتمال المستند")
                return@launch
            }

            _isLoading.value = true
            try {
                val result = repository.registerCaptainWithEmailVerification(
                    fullName = fullName,
                    phone = phone,
                    email = email,
                    pass = pass,
                    address = address,
                    avatarPresetIndex = avatarPresetIndex,
                    avatarUri = avatarUri,
                    vehicleType = vehicleType,
                    nationalIdUri = nationalIdUri,
                    idBackUri = idBackUri,
                    licenseUri = licenseUri
                )
                if (result.isSuccess) {
                    val sentEmail = result.getOrNull() ?: email.trim()
                    _eventFlow.emit(UiEvent.ShowToast("تم إنشاء الحساب ورفع الصور بأمان وإرسال رابط التأكيد"))
                    onEmailSent(sentEmail)
                } else {
                    onError(result.exceptionOrNull()?.localizedMessage ?: "فشل إنشاء الحساب")
                }
            } catch (e: Exception) {
                onError("خطأ: ${e.localizedMessage ?: "حدث خطأ غير متوقع"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun checkEmailVerification(
        onVerified: () -> Unit,
        onNotVerified: (String) -> Unit,
        onAwaitingApproval: (CaptainApprovalSnapshot) -> Unit = {}
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _approvalFeedback.value = null
            try {
                val res = repository.checkEmailVerification()
                if (res.isSuccess && res.getOrNull() == true) {
                    val approval = repository.accountApprovalState.value
                    attachCaptainApprovalListenerForCurrentUser()
                    if (approval != null && approval.canEnterApp) {
                        _eventFlow.emit(UiEvent.ShowToast("تم تأكيد البريد بنجاح! مرحباً بك يا كابتن"))
                        onVerified()
                    } else {
                        val pendingSnapshot = approval ?: CaptainApprovalSnapshot(
                            status = CaptainAccountStatus.PENDING,
                            isApproved = false,
                            email = _unverifiedEmail.value
                        )
                        _currentRoute.value = ScreenRoute.AccountApproval.route
                        _eventFlow.emit(UiEvent.ShowToast("تم تأكيد البريد الإلكتروني بنجاح. حسابك الآن قيد مراجعة الإدارة."))
                        onAwaitingApproval(pendingSnapshot)
                    }
                } else if (res.isSuccess) {
                    onNotVerified("لم يتم تأكيد البريد الإلكتروني بعد! يرجى فتح البريد الإلكتروني والضغط على الرابط أولاً ثم الضغط هنا مجدداً.")
                } else {
                    onNotVerified(res.exceptionOrNull()?.localizedMessage ?: "تعذر التحقق من حالة البريد")
                }
            } catch (e: Exception) {
                onNotVerified(e.localizedMessage ?: "حدث خطأ أثناء التحقق")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resendVerificationEmail(
        onResult: (isSuccess: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val res = repository.resendVerificationEmail()
                if (res.isSuccess) {
                    val msg = res.getOrNull() ?: "تمت إعادة إرسال رابط التأكيد بنجاح"
                    _eventFlow.emit(UiEvent.ShowToast(msg))
                    onResult(true, msg)
                } else {
                    val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر إعادة الإرسال حالياً"
                    onResult(false, err)
                }
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "حدث خطأ أثناء إعادة الإرسال")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun validateSession(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isValid = repository.validateCurrentSession()
            onResult(isValid)
        }
    }

    /**
     * Manual refresh action from AccountApprovalScreen ("تحديث حالة الحساب").
     * Strictly queries Firestore server (never trusts local storage/cache).
     */
    fun refreshAccountApprovalStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            _approvalFeedback.value = null
            try {
                val snapshot = repository.refreshLiveAccountApproval()
                if (snapshot.canEnterApp) {
                    attachCaptainApprovalListenerForCurrentUser()
                    auth?.currentUser?.uid?.let { attachShiftSnapshotListeners(it) }
                    _approvalFeedback.value = null
                    _currentRoute.value = ScreenRoute.Main.route
                    _eventFlow.emit(UiEvent.ShowToast("تمت الموافقة على حسابك! مرحباً بك في البطل إكسبريس"))
                } else {
                    val msg = CaptainAccountApprovalResolver.statusSummaryMessage(snapshot)
                    _approvalFeedback.value = msg
                    revokeActiveSessionToApprovalScreenIfNeeded(snapshot, showToast = false)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Called when the app returns to foreground (`ON_RESUME`) so any admin suspension,
     * block, rejection, or approval is enforced immediately without waiting for relogin.
     */
    fun onAppForegrounded() {
        val app = getApplication<Application>()
        val route = _currentRoute.value
        if (route == ScreenRoute.Main.route && pendingStartWorkAfterGpsEnable) {
            pendingStartWorkAfterGpsEnable = false
            if (repository.accountApprovalState.value != null && !repository.isCurrentSessionApproved) {
                viewModelScope.launch {
                    _eventFlow.emit(UiEvent.ShowToast("حسابك غير معتمد حالياً من الإدارة ولا يمكنك بدء العمل."))
                }
            } else if (_shiftStatus.value is ShiftStatus.Active &&
                CaptainLocationTracker.hasLocationPermission(app) &&
                CaptainLocationTracker.isGpsProviderEnabled(app) &&
                !isUpdatingOnlineStatus
            ) {
                viewModelScope.launch {
                    isUpdatingOnlineStatus = true
                    try {
                        val alreadyServerReady = try {
                            tickerShiftReadinessOverride?.invoke() ?: repository.hasConfirmedShiftReadiness()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        activateShiftWorkSafely(
                            app = app,
                            callReadyFirst = !alreadyServerReady,
                            successToast = "أنت الآن متصل وتم فتح الموقع (GPS) لاستقبال الطلبات.",
                            ineligibleToast = "تعذر بدء العمل حالياً. تأكد من اعتماد حسابك ووجود شفت نشط ثم أعد المحاولة."
                        )
                    } finally {
                        isUpdatingOnlineStatus = false
                    }
                }
            }
        }
        if (route != ScreenRoute.Main.route && route != ScreenRoute.AccountApproval.route) {
            return
        }
        viewModelScope.launch {
            val snapshot = repository.refreshLiveAccountApproval()
            if (snapshot.canEnterApp) {
                if (_currentRoute.value == ScreenRoute.AccountApproval.route) {
                    attachCaptainApprovalListenerForCurrentUser()
                    auth?.currentUser?.uid?.let { attachShiftSnapshotListeners(it) }
                    _approvalFeedback.value = null
                    _currentRoute.value = ScreenRoute.Main.route
                    _eventFlow.emit(UiEvent.ShowToast("تمت الموافقة على حسابك! مرحباً بك في البطل إكسبريس"))
                }
            } else if (snapshot.checkedFromServer) {
                // Server explicitly reported pending/rejected/suspended/blocked — revoke immediately!
                revokeActiveSessionToApprovalScreenIfNeeded(snapshot, showToast = route == ScreenRoute.Main.route)
            } else if (_currentRoute.value == ScreenRoute.AccountApproval.route) {
                // Fail-closed: network outage while awaiting approval keeps the user blocked.
                _approvalFeedback.value = snapshot.networkError
            }
        }
    }

    /**
     * Applies a real-time Firestore `captains/{uid}` snapshot update (live listener or unit test).
     * Automatically unlocks Main on fresh server approval, and immediately revokes Main session
     * (stopping GPS, bell, shifts, and orders) on suspension/blocking/rejection/pending.
     */
    internal suspend fun applyLiveAccountApprovalSnapshot(
        uid: String,
        docData: Map<String, Any?>?,
        isFromCache: Boolean = false
    ): CaptainApprovalSnapshot {
        val snapshot = repository.handleLiveApprovalDocUpdate(
            uid = uid,
            docData = docData,
            authEmail = auth?.currentUser?.email.orEmpty(),
            authDisplayName = auth?.currentUser?.displayName.orEmpty(),
            isServerConfirmedDoc = !isFromCache
        )
        if (snapshot.canEnterApp) {
            // Never unlock from stale offline cache; require live server-confirmed approval!
            if (!isFromCache && _currentRoute.value == ScreenRoute.AccountApproval.route) {
                attachShiftSnapshotListeners(uid)
                _approvalFeedback.value = null
                _currentRoute.value = ScreenRoute.Main.route
                _eventFlow.emit(UiEvent.ShowToast("تمت الموافقة على حسابك! مرحباً بك في البطل إكسبريس"))
            }
        } else {
            revokeActiveSessionToApprovalScreenIfNeeded(
                snapshot = snapshot,
                showToast = _currentRoute.value == ScreenRoute.Main.route
            )
        }
        return snapshot
    }

    private suspend fun revokeActiveSessionToApprovalScreenIfNeeded(
        snapshot: CaptainApprovalSnapshot,
        showToast: Boolean
    ) {
        val app = getApplication<Application>()
        CaptainNotificationManager.stopIncomingOrderBell(app)
        CaptainLocationTracker.stopService(app)
        detachShiftSnapshotListeners()
        if (_currentRoute.value == ScreenRoute.Main.route || _currentRoute.value == ScreenRoute.VerifyEmail.route) {
            _currentRoute.value = ScreenRoute.AccountApproval.route
        }
        if (showToast) {
            _eventFlow.emit(UiEvent.ShowToast(CaptainAccountApprovalResolver.statusSummaryMessage(snapshot)))
        }
    }

    /**
     * Starts Firebase Auth + server approval validation immediately while the intro is visible.
     * This overlaps network/session work with the branded splash instead of waiting for video
     * playback to finish before starting the most latency-sensitive startup operation.
     */
    fun prepareStartupSessionCheck() {
        if (startupSessionCheckStarted || startupSessionCheckFinished) return
        startupSessionCheckStarted = true
        val timeoutMs = startupSessionCheckTimeoutMs
        viewModelScope.launch {
            val sessionState = try {
                withTimeoutOrNull(timeoutMs) {
                    startupSessionResolverOverride?.invoke()
                        ?: repository.resolveStartupSessionState()
                } ?: run {
                    Log.w(
                        "CaptainViewModel",
                        "Startup session check timed out after ${timeoutMs}ms; keeping access closed."
                    )
                    val timeoutSnapshot = CaptainAccountApprovalResolver.networkFailure(
                        message = "استغرق التحقق من الحساب وقتاً أطول من المعتاد. تحقق من اتصال الإنترنت ثم أعد المحاولة."
                    )
                    repository.setAccountApprovalSnapshot(timeoutSnapshot)
                    StartupSessionState.ApprovalCheckFailed(timeoutSnapshot)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("CaptainViewModel", "Startup session check failed; keeping access closed: ${e.message}", e)
                val errorSnapshot = CaptainAccountApprovalResolver.networkFailure(
                    message = "تعذر التحقق من حالة الحساب. تحقق من اتصال الإنترنت ثم أعد المحاولة."
                )
                repository.setAccountApprovalSnapshot(errorSnapshot)
                StartupSessionState.ApprovalCheckFailed(errorSnapshot)
            }
            resolvedStartupSession = sessionState
            startupSessionCheckFinished = true
            if (introVisualFinished) {
                completeStartupAfterChecks(sessionState)
            }
        }
    }

    /**
     * Called when the intro video ends or its short startup budget expires.
     * Navigation waits for the server approval result too; local cached approval never unlocks Main.
     */
    fun onIntroSplashFinished() {
        if (introVisualFinished) return
        introVisualFinished = true
        prepareStartupSessionCheck()
        if (startupSessionCheckFinished) {
            completeStartupAfterChecks(resolvedStartupSession)
        }
    }

    private fun completeStartupAfterChecks(sessionState: StartupSessionState?) {
        if (_hasCompletedIntroSplash.value) return
        _hasCompletedIntroSplash.value = true
        when (sessionState) {
            is StartupSessionState.Authenticated -> {
                attachCaptainApprovalListenerForCurrentUser()
                auth?.currentUser?.uid?.let { attachShiftSnapshotListeners(it) }
                _currentRoute.value = ScreenRoute.Main.route
            }
            is StartupSessionState.NeedsEmailVerification -> {
                detachCaptainApprovalListener()
                _unverifiedEmail.value = sessionState.email
                _currentRoute.value = ScreenRoute.VerifyEmail.route
            }
            is StartupSessionState.AwaitingApproval -> {
                repository.setAccountApprovalSnapshot(sessionState.approval)
                attachCaptainApprovalListenerForCurrentUser()
                detachShiftSnapshotListeners()
                CaptainLocationTracker.stopService(getApplication())
                _currentRoute.value = ScreenRoute.AccountApproval.route
            }
            is StartupSessionState.ApprovalCheckFailed -> {
                repository.setAccountApprovalSnapshot(sessionState.approval)
                detachShiftSnapshotListeners()
                CaptainLocationTracker.stopService(getApplication())
                _approvalFeedback.value = sessionState.approval.networkError
                _currentRoute.value = ScreenRoute.AccountApproval.route
            }
            is StartupSessionState.Unauthenticated, null -> {
                detachCaptainApprovalListener()
                detachShiftSnapshotListeners()
                _currentRoute.value = ScreenRoute.Login.route
            }
        }
    }

    fun handleNotificationIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val navTarget = intent.getStringExtra(CaptainNotificationManager.EXTRA_NAVIGATE_TARGET).orEmpty()
        val acceptOrderId = intent.getLongExtra(CaptainNotificationManager.EXTRA_ACCEPT_ORDER_ID, 0L)
        val orderKey = intent.getStringExtra(CaptainNotificationManager.EXTRA_ORDER_KEY).orEmpty()

        if (orderKey.isNotBlank() || acceptOrderId > 0L) {
            CaptainNotificationManager.stopIncomingOrderBell(getApplication(), orderKey)
        }

        when (navTarget) {
            CaptainNotificationManager.TARGET_SHIFTS -> _pendingNavigateTab.value = 2
            CaptainNotificationManager.TARGET_DASHBOARD -> _pendingNavigateTab.value = 0
        }

        if (acceptOrderId > 0L || (orderKey.isNotBlank() && intent.hasExtra(CaptainNotificationManager.EXTRA_ACCEPT_ORDER_ID))) {
            viewModelScope.launch {
                // Allow Room/shifts initial emissions up to ~2.5s on cold start from notification action
                var attempts = 0
                while ((captainState.value == null || (!_shiftStatus.value.isAvailableForOrders && shifts.value.isEmpty())) && attempts < 25) {
                    delay(100L)
                    attempts++
                }
                val resolvedId = if (acceptOrderId > 0L) {
                    acceptOrderId
                } else {
                    allOrders.value.firstOrNull {
                        orderKey.isNotBlank() && (it.firestoreDocId == orderKey || it.cleanOrderNumber == orderKey)
                    }?.id ?: 0L
                }
                if (resolvedId > 0L) {
                    acceptIncomingOrder(resolvedId)
                }
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        val app = getApplication<Application>()
        CaptainNotificationManager.stopIncomingOrderBell(app)
        CaptainLocationTracker.stopService(app)
        detachCaptainApprovalListener()
        detachShiftSnapshotListeners()
        viewModelScope.launch {
            _profileUploadState.value = ImageUploadState.Idle
            _idFrontUploadState.value = ImageUploadState.Idle
            _idBackUploadState.value = ImageUploadState.Idle
            _licenseUploadState.value = ImageUploadState.Idle
            _receiptUploadState.value = ImageUploadState.Idle
            _orderActionError.value = null
            _approvalFeedback.value = null
            _liveShifts.value = emptyList()
            _hasLiveShiftSnapshot.value = false
            _shiftStatus.value = ShiftStatus.NoSchedule()
            repository.logout()
            onLoggedOut()
        }
    }

    fun acceptIncomingOrder(orderId: Long, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        CaptainNotificationManager.stopIncomingOrderBell(getApplication())
        if (captainState.value?.isOnline != true) {
            val offlineMsg = "لا يمكنك قبول أو استلام طلبات وأنت في وضع غير متصل."
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast(offlineMsg))
            }
            onResult(false, offlineMsg)
            return
        }
        if (!_shiftStatus.value.isAvailableForOrders) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast("عفواً، أنت خارج ساعات العمل الرسمية ولا يمكنك قبول طلبات جديدة حالياً."))
            }
            onResult(false, "أنت خارج ساعات العمل الرسمية")
            return
        }

        viewModelScope.launch {
            _orderActionError.value = null
            _isLoading.value = true
            val res = repository.acceptOrder(orderId)
            _isLoading.value = false
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.OrderAccepted)
                _eventFlow.emit(UiEvent.ShowToast("تم قبول الطلب بنجاح! اضغط 'تم الوصول للتاجر' عند الوصول 🚀"))
                onResult(true, "تم قبول الطلب بنجاح")
            } else {
                val err = res.exceptionOrNull()
                val cfEx = err as? com.example.data.remote.CloudFunctionException
                val errorMsg = err?.localizedMessage ?: "تعذر الاتصال بالخادم لقبول الطلب. يرجى إعادة المحاولة."
                val isEndpointMissing = cfEx?.isEndpointNotFound == true
                val isOrderGone = !isEndpointMissing && (cfEx?.isNotFound == true)
                _orderActionError.value = OrderActionError(
                    message = errorMsg,
                    actionLabel = if (isOrderGone) "تحديث الطلبات" else "إعادة محاولة قبول الطلب",
                    isRetryable = if (isOrderGone) false else (cfEx?.isRetryable ?: true),
                    isNotFound = isOrderGone,
                    onRetry = {
                        if (isOrderGone) {
                            removeStaleOrder(orderId)
                            refreshOrders()
                        } else {
                            acceptIncomingOrder(orderId, onResult)
                        }
                    },
                    onDismissOrClear = {
                        if (isOrderGone) {
                            removeStaleOrder(orderId)
                            refreshOrders()
                        } else {
                            dismissOrderActionError()
                        }
                    }
                )
                _eventFlow.emit(UiEvent.ShowToast(errorMsg))
                onResult(false, errorMsg)
            }
        }
    }

    fun refreshShifts(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isLoading.value = true
            val res = repository.syncShiftsFromFirestore()
            _isLoading.value = false
            if (res.isSuccess) {
                val syncedShifts = res.getOrDefault(emptyList())
                _liveShifts.value = syncedShifts
                _hasLiveShiftSnapshot.value = true
                _shiftStatus.value = ShiftManager.calculateShiftStatus(syncedShifts)
                val count = syncedShifts.size
                val msg = if (count > 0) "تم تحديث جدول الشفتات ($count ورديات مسجلة) بنجاح" else "تم التحديث: لا توجد شفتات مخصصة لك حالياً من الإدارة"
                _eventFlow.emit(UiEvent.ShowToast(msg))
                onResult(true, msg)
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر تحديث الشفتات من السيرفر"
                _eventFlow.emit(UiEvent.ShowToast(err))
                onResult(false, err)
            }
        }
    }

    fun refreshMerchantLocationForOrder(order: OrderEntity) {
        viewModelScope.launch {
            runCatching {
                repository.resolveMerchantLocationForOrder(order, forceRefresh = true)
            }
        }
    }

    fun removeStaleOrder(orderId: Long) {
        viewModelScope.launch {
            repository.removeStaleOrder(orderId)
            _orderActionError.value = null
            _eventFlow.emit(UiEvent.ShowToast("تم تحديث القائمة وإزالة الطلب غير المتاح"))
        }
    }

    fun markArrivedAtStore(orderId: Long) {
        viewModelScope.launch {
            _orderActionError.value = null
            _isLoading.value = true
            val res = repository.markAtStore(orderId)
            _isLoading.value = false
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم تأكيد الوصول إلى التاجر بنجاح! اضغط 'استلام الطلب' 📦"))
            } else {
                val err = res.exceptionOrNull()
                val cfEx = err as? com.example.data.remote.CloudFunctionException
                val errorMsg = err?.message ?: "تعذر الاتصال بالخادم لتسجيل الوصول للتاجر."
                val isEndpointMissing = cfEx?.isEndpointNotFound == true
                val isNotFound = !isEndpointMissing && (
                    cfEx?.isNotFound == true ||
                        errorMsg.contains("الطلب غير موجود") ||
                        errorMsg.contains("لم يعد متاحاً")
                    )

                _orderActionError.value = OrderActionError(
                    message = if (isNotFound) "الطلب غير موجود أو لم يعد متاحاً على الخادم." else errorMsg,
                    actionLabel = if (isNotFound) "تحديث الطلبات" else "إعادة المحاولة",
                    isRetryable = if (isNotFound) false else (cfEx?.isRetryable ?: true),
                    isNotFound = isNotFound,
                    onRetry = {
                        if (isNotFound) {
                            removeStaleOrder(orderId)
                        } else {
                            markArrivedAtStore(orderId)
                        }
                    },
                    onDismissOrClear = {
                        removeStaleOrder(orderId)
                    }
                )
                _eventFlow.emit(UiEvent.ShowToast(errorMsg))
            }
        }
    }

    fun confirmPickup(orderId: Long) {
        viewModelScope.launch {
            _orderActionError.value = null
            _isLoading.value = true
            val res = repository.confirmPickup(orderId)
            _isLoading.value = false
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم استلام الطلب من التاجر! يرجى إدخال بيانات العميل وصورة الإيصال 📷"))
            } else {
                val err = res.exceptionOrNull()
                val cfEx = err as? com.example.data.remote.CloudFunctionException
                val errorMsg = err?.message ?: "تعذر الاتصال بالخادم لتأكيد استلام الطلب."
                val isEndpointMissing = cfEx?.isEndpointNotFound == true
                val isNotFound = !isEndpointMissing && (
                    cfEx?.isNotFound == true ||
                        errorMsg.contains("الطلب غير موجود") ||
                        errorMsg.contains("لم يعد متاحاً")
                    )

                _orderActionError.value = OrderActionError(
                    message = if (isNotFound) "الطلب غير موجود أو لم يعد متاحاً على الخادم." else errorMsg,
                    actionLabel = if (isNotFound) "تحديث الطلبات" else "إعادة المحاولة",
                    isRetryable = if (isNotFound) false else (cfEx?.isRetryable ?: true),
                    isNotFound = isNotFound,
                    onRetry = {
                        if (isNotFound) {
                            removeStaleOrder(orderId)
                        } else {
                            confirmPickup(orderId)
                        }
                    },
                    onDismissOrClear = {
                        removeStaleOrder(orderId)
                    }
                )
                _eventFlow.emit(UiEvent.ShowToast(errorMsg))
            }
        }
    }

    fun saveOrderCustomerData(
        orderId: Long,
        customerName: String,
        customerPhone: String,
        notes: String,
        imageUri: Uri,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            _orderActionError.value = null
            _isLoading.value = true
            val res = repository.saveOrderCustomerData(
                orderId = orderId,
                customerName = customerName,
                customerPhone = customerPhone,
                notes = notes,
                imageUri = imageUri,
                onUploadStateChange = { state -> _receiptUploadState.value = state }
            )
            _isLoading.value = false
            if (res.isSuccess) {
                _receiptUploadState.value = ImageUploadState.Idle
                _eventFlow.emit(UiEvent.ShowToast("تم رفع الإيصال وحفظ بيانات العميل وإرسالها للتاجر بنجاح ✓"))
                onSuccess()
            } else {
                val errObj = res.exceptionOrNull()
                val err = errObj?.localizedMessage ?: "تعذر حفظ بيانات العميل والإيصال على الخادم"
                _orderActionError.value = OrderActionError(
                    message = err,
                    actionLabel = "إعادة المحاولة",
                    isRetryable = true,
                    onRetry = {
                        saveOrderCustomerData(orderId, customerName, customerPhone, notes, imageUri, onSuccess, onError)
                    }
                )
                _eventFlow.emit(UiEvent.ShowToast(err))
                onError(err)
            }
        }
    }

    fun resetReceiptUploadState() {
        _receiptUploadState.value = ImageUploadState.Idle
    }

    fun saveOrderDataAndImage(
        orderId: Long,
        imageUri: Uri,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        saveOrderCustomerData(orderId, "عميل البطل", "", "", imageUri, onSuccess, onError)
    }

    fun expireIncomingOrder(orderId: Long) {
        val currentOrder = allOrders.value.firstOrNull { it.id == orderId }
        val dedupeKey = "${orderId}_${currentOrder?.offerExpiresAt ?: 0L}"
        if (!expiredOfferNotifiedIds.add(dedupeKey)) {
            return
        }
        CaptainNotificationManager.stopIncomingOrderBell(
            getApplication(),
            currentOrder?.firestoreDocId.orEmpty()
        )
        viewModelScope.launch {
            val res = repository.expireDeliveryOffer(orderId)
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("انتهت مهلة قبول الطلب وتم تحويله إلى أقرب كابتن للتاجر"))
            } else {
                expiredOfferNotifiedIds.remove(dedupeKey)
                val errMsg = res.exceptionOrNull()?.localizedMessage ?: "تعذر إبلاغ الخادم بانتهاء مهلة العرض"
                _orderActionError.value = OrderActionError(
                    message = errMsg,
                    actionLabel = "إعادة المحاولة",
                    isRetryable = true,
                    onRetry = { expireIncomingOrder(orderId) }
                )
                _eventFlow.emit(UiEvent.ShowToast(errMsg))
            }
        }
    }

    fun declineIncomingOrder(orderId: Long) {
        CaptainNotificationManager.stopIncomingOrderBell(getApplication())
        viewModelScope.launch {
            _orderActionError.value = null
            val res = repository.declineOrder(orderId)
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم رفض الطلب"))
            } else {
                val errMsg = res.exceptionOrNull()?.localizedMessage ?: "تعذر الاتصال بالخادم لرفض الطلب"
                _orderActionError.value = OrderActionError(
                    message = errMsg,
                    actionLabel = "إعادة المحاولة",
                    isRetryable = true,
                    onRetry = { declineIncomingOrder(orderId) }
                )
                _eventFlow.emit(UiEvent.ShowToast(errMsg))
            }
        }
    }

    fun advanceActiveOrderStatus(order: OrderEntity) {
        viewModelScope.launch {
            when (order.status) {
                OrderStatus.ACCEPTED -> {
                    markArrivedAtStore(order.id)
                }
                OrderStatus.AT_STORE -> {
                    confirmPickup(order.id)
                }
                OrderStatus.EN_ROUTE -> {
                    _orderActionError.value = null
                    _isLoading.value = true
                    val res = repository.completeDelivery(order.id)
                    _isLoading.value = false
                    if (res.isSuccess) {
                        _eventFlow.emit(UiEvent.OrderCompleted)
                        _eventFlow.emit(UiEvent.ShowToast("تم تسليم الطلب بنجاح! تم إضافة ${res.getOrDefault(25.0).toInt()} ج.م لمحفظتك 🏁"))
                    } else {
                        val err = res.exceptionOrNull()
                        val cfEx = err as? com.example.data.remote.CloudFunctionException
                        val errorMsg = err?.message ?: "تعذر الاتصال بالخادم لتأكيد تسليم الطلب."
                        val isEndpointMissing = cfEx?.isEndpointNotFound == true
                        val isNotFound = !isEndpointMissing && (
                            cfEx?.isNotFound == true ||
                                errorMsg.contains("الطلب غير موجود") ||
                                errorMsg.contains("لم يعد متاحاً")
                            )

                        _orderActionError.value = OrderActionError(
                            message = if (isNotFound) "الطلب غير موجود أو لم يعد متاحاً على الخادم." else errorMsg,
                            actionLabel = if (isNotFound) "تحديث الطلبات" else "إعادة المحاولة",
                            isRetryable = if (isNotFound) false else (cfEx?.isRetryable ?: true),
                            isNotFound = isNotFound,
                            onRetry = {
                                if (isNotFound) {
                                    removeStaleOrder(order.id)
                                } else {
                                    advanceActiveOrderStatus(order)
                                }
                            },
                            onDismissOrClear = {
                                removeStaleOrder(order.id)
                            }
                        )
                        _eventFlow.emit(UiEvent.ShowToast(errorMsg))
                    }
                }
                else -> {}
            }
        }
    }

    fun refreshOrders(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isLoading.value = true
            val res = repository.syncOrdersDetailedFromFirestore()
            _isLoading.value = false
            if (res.isSuccess) {
                val summary = res.getOrNull()
                val pending = summary?.pendingCount ?: 0
                val active = summary?.activeCount ?: 0
                val isOnline = captainState.value?.isOnline == true
                val isInShift = _shiftStatus.value.isAvailableForOrders
                val msg = when {
                    !isOnline -> "تم التحديث: أنت في وضع غير متصل ولا يتم إسناد طلبات جديدة لك حالياً"
                    !isInShift -> "تم التحديث: أنت خارج ساعات الشفت ولا يتم إسناد طلبات جديدة حالياً"
                    pending > 0 -> "تم تحديث وجلب $pending طلب جديد متاح للاستلام"
                    active > 0 -> "تم التحديث: لديك طلب نشط قيد التنفيذ (لا توجد رحلات جديدة)"
                    else -> "تم التحديث: لا توجد طلبات جديدة متاحة حالياً"
                }
                _eventFlow.emit(UiEvent.ShowToast(msg))
                onResult(true, msg)
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "فشل تحديث الطلبات"
                _eventFlow.emit(UiEvent.ShowToast(err))
                onResult(false, err)
            }
        }
    }

    fun updateProfileInfo(
        fullName: String,
        phoneNumber: String,
        email: String,
        vehicleType: String,
        licensePlate: String,
        avatarUri: String = "",
        address: String? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val res = repository.updateProfile(fullName, phoneNumber, email, vehicleType, licensePlate, avatarUri, address)
            _isLoading.value = false
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم تحديث بيانات الكابتن بنجاح"))
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "فشل تحديث البيانات"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun uploadCaptainProfilePhoto(uri: Uri, onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.uploadAndSaveProfilePhoto(uri) { state ->
                _profileUploadState.value = state
            }
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم حفظ الصورة الشخصية بنجاح ✓"))
                onCompleted()
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر رفع الصورة الشخصية"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun uploadCaptainIdFront(uri: Uri, onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.uploadAndSaveIdFront(uri) { state ->
                _idFrontUploadState.value = state
            }
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم الحفظ والتوثيق بنجاح للوجه الأمامي 🔒"))
                onCompleted()
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر رفع الوجه الأمامي للبطاقة"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun uploadCaptainIdBack(uri: Uri, onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.uploadAndSaveIdBack(uri) { state ->
                _idBackUploadState.value = state
            }
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم الحفظ والتوثيق بنجاح للوجه الخلفي 🔒"))
                onCompleted()
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر رفع الوجه الخلفي للبطاقة"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun resetProfileUploadState() {
        _profileUploadState.value = ImageUploadState.Idle
    }

    fun resetIdFrontUploadState() {
        _idFrontUploadState.value = ImageUploadState.Idle
    }

    fun resetIdBackUploadState() {
        _idBackUploadState.value = ImageUploadState.Idle
    }

    fun uploadCaptainLicensePhoto(uri: Uri, onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.uploadAndSaveLicensePhoto(uri) { state ->
                _licenseUploadState.value = state
            }
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم حفظ صورة الرخصة بنجاح ✓"))
                onCompleted()
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر رفع صورة الرخصة"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun resetLicenseUploadState() {
        _licenseUploadState.value = ImageUploadState.Idle
    }

    fun setupRealtimeShiftListener() {
        val currentAuth = auth ?: return
        val user = currentAuth.currentUser
        if (user != null && user.isEmailVerified) {
            attachShiftSnapshotListeners(user.uid)
        } else {
            detachShiftSnapshotListeners()
        }
        if (authStateListener == null) {
            authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                val current = firebaseAuth.currentUser
                if (current != null && current.isEmailVerified) {
                    attachCaptainApprovalListener(current.uid)
                    if (repository.isCurrentSessionApproved) {
                        attachShiftSnapshotListeners(current.uid)
                    }
                } else {
                    detachCaptainApprovalListener()
                    detachShiftSnapshotListeners()
                }
            }
            currentAuth.addAuthStateListener(authStateListener!!)
        }
    }

    private fun attachCaptainApprovalListenerForCurrentUser() {
        val uid = auth?.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return
        attachCaptainApprovalListener(uid)
    }

    private fun attachCaptainApprovalListener(uid: String) {
        if (attachedApprovalUid == uid && captainApprovalDocListener != null) return
        detachCaptainApprovalListener()
        val dbFirestore = firestore ?: return
        attachedApprovalUid = uid
        captainApprovalDocListener = dbFirestore.collection("captains").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("CaptainViewModel", "[ApprovalSnapshotListener] captains/$uid error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val isFromCache = snapshot.metadata.isFromCache
                val docData = if (snapshot.exists()) snapshot.data else null
                viewModelScope.launch {
                    applyLiveAccountApprovalSnapshot(
                        uid = uid,
                        docData = docData,
                        isFromCache = isFromCache
                    )
                }
            }
    }

    fun detachCaptainApprovalListener() {
        captainApprovalDocListener?.remove()
        captainApprovalDocListener = null
        attachedApprovalUid = null
    }

    internal suspend fun applyCanonicalShiftSnapshot(
        uid: String,
        exists: Boolean,
        docData: Map<String, Any?>?
    ) {
        val latestShifts = if (exists && docData != null) {
            extractShiftsFromMap(docData, uid, uid)
        } else {
            emptyList()
        }
        Log.i(
            "CaptainViewModel",
            "[ShiftSnapshotListener] Applying canonical schedule for $uid: exists=$exists, shifts=${latestShifts.size}"
        )
        _liveShifts.value = latestShifts
        _hasLiveShiftSnapshot.value = true
        _shiftStatus.value = ShiftManager.calculateShiftStatus(latestShifts)
        repository.saveShiftsLocally(uid, latestShifts)
    }

    private fun attachShiftSnapshotListeners(uid: String) {
        detachShiftSnapshotListeners()
        val dbFirestore = firestore ?: return
        Log.i("CaptainViewModel", "[ShiftSnapshotListener] Attaching canonical Firestore listener for UID: $uid (target: captain_schedules/$uid)")

        // Canonical single source of truth: captain_schedules/{uid}
        // Replaces _liveShifts and Room even when deleted or empty so removed shifts never linger.
        shiftDocListener = dbFirestore.collection("captain_schedules").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("CaptainViewModel", "[ShiftSnapshotListener] captain_schedules/$uid error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                viewModelScope.launch {
                    applyCanonicalShiftSnapshot(
                        uid = uid,
                        exists = snapshot.exists(),
                        docData = if (snapshot.exists()) snapshot.data else null
                    )
                }
            }

        accountStatusListener = dbFirestore.collection("captains").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("CaptainViewModel", "[CaptainDocListener] captains/$uid error: ${error.message}")
                    return@addSnapshotListener
                }
                val remoteStatus = snapshot?.takeIf { it.exists() }
                    ?.getString("status")?.trim()?.lowercase()
                    ?.takeIf { it.isNotBlank() } ?: return@addSnapshotListener
                viewModelScope.launch { onRemoteAccountStatus(remoteStatus) }
            }
    }

    fun detachShiftSnapshotListeners() {
        shiftDocListener?.remove()
        shiftDocListener = null
        accountStatusListener?.remove()
        accountStatusListener = null
        _liveShifts.value = emptyList()
        _hasLiveShiftSnapshot.value = false
        _shiftStatus.value = ShiftStatus.NoSchedule()
    }

    override fun onCleared() {
        super.onCleared()
        detachCaptainApprovalListener()
        detachShiftSnapshotListeners()
        authStateListener?.let { listener ->
            runCatching { auth?.removeAuthStateListener(listener) }
        }
    }

    companion object {
        const val STARTUP_SESSION_CHECK_TIMEOUT_MS = 25_000L
        const val SHIFT_TRANSITION_RETRY_INTERVAL_MS = 5_000L
        private val BLOCKED_ACCOUNT_STATUSES = setOf("banned", "suspended", "disabled", "blocked")
        private val INACTIVE_ACCOUNT_STATUSES = BLOCKED_ACCOUNT_STATUSES + setOf("pending", "inactive")

        fun calculateDailyWorkHours(
            shifts: List<ShiftEntity>,
            nowCal: java.util.Calendar = ShiftManager.getCurrentCairoCalendar()
        ): Double {
            val todayDayOfWeek = nowCal.get(java.util.Calendar.DAY_OF_WEEK)
            return shifts
                .filter { it.dayOfWeek == todayDayOfWeek && !it.isDayOff }
                .sumOf { ShiftManager.calculateShiftDurationHours(it) }
        }
    }
}
