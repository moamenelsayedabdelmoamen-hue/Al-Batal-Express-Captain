package com.example.data

enum class CaptainAccountStatus(
    val wireValue: String,
    val arabicLabel: String
) {
    PENDING("pending", "قيد المراجعة"),
    APPROVED("approved", "معتمد ومفعّل"),
    REJECTED("rejected", "مرفوض"),
    SUSPENDED("suspended", "موقوف مؤقتاً"),
    BLOCKED("blocked", "محظور")
}

data class CaptainApprovalSnapshot(
    val status: CaptainAccountStatus,
    val isApproved: Boolean,
    val rejectionReason: String = "",
    val captainName: String = "",
    val email: String = "",
    val checkedFromServer: Boolean = true,
    val networkError: String? = null
) {
    val canEnterApp: Boolean
        get() = checkedFromServer &&
            networkError == null &&
            status == CaptainAccountStatus.APPROVED &&
            isApproved
}

/**
 * Canonical client-side approval resolver matching Firestore Security Rules,
 * Cloud Functions (`validators.ts`), and the Admin Dashboard 1:1.
 *
 * Fail-closed by design:
 * - Missing document or unknown status resolves to PENDING (not approved).
 * - Preserves backward compatibility for legacy active captains lacking explicit
 *   `accountStatus` / `isApproved` fields.
 */
object CaptainAccountApprovalResolver {

    private val LEGACY_APPROVED_STATUSES = setOf(
        "active",
        "approved",
        "online",
        "available",
        "busy"
    )

    fun resolve(
        docData: Map<String, Any?>?,
        fallbackEmail: String = "",
        fallbackName: String = ""
    ): CaptainApprovalSnapshot {
        if (docData == null) {
            return CaptainApprovalSnapshot(
                status = CaptainAccountStatus.PENDING,
                isApproved = false,
                captainName = fallbackName,
                email = fallbackEmail,
                checkedFromServer = true
            )
        }

        val name = readNonBlankString(docData, "fullName", "name", "driver_name").ifBlank { fallbackName }
        val email = readNonBlankString(docData, "email").ifBlank { fallbackEmail }
        val rejectionReason = readNonBlankString(docData, "rejectionReason")

        if (docData["isBlocked"] == true) {
            return CaptainApprovalSnapshot(
                status = CaptainAccountStatus.BLOCKED,
                isApproved = false,
                rejectionReason = rejectionReason,
                captainName = name,
                email = email
            )
        }
        if (docData["isSuspended"] == true) {
            return CaptainApprovalSnapshot(
                status = CaptainAccountStatus.SUSPENDED,
                isApproved = false,
                rejectionReason = rejectionReason,
                captainName = name,
                email = email
            )
        }

        val explicitAccountStatus = readNonBlankString(docData, "accountStatus").lowercase()
        val explicitIsApproved = docData["isApproved"] as? Boolean

        if (explicitAccountStatus.isNotEmpty()) {
            return when (explicitAccountStatus) {
                "approved" -> {
                    if (explicitIsApproved == false) {
                        CaptainApprovalSnapshot(
                            status = CaptainAccountStatus.PENDING,
                            isApproved = false,
                            rejectionReason = rejectionReason,
                            captainName = name,
                            email = email
                        )
                    } else {
                        CaptainApprovalSnapshot(
                            status = CaptainAccountStatus.APPROVED,
                            isApproved = true,
                            rejectionReason = "",
                            captainName = name,
                            email = email
                        )
                    }
                }
                "pending" -> CaptainApprovalSnapshot(
                    status = CaptainAccountStatus.PENDING,
                    isApproved = false,
                    rejectionReason = rejectionReason,
                    captainName = name,
                    email = email
                )
                "rejected" -> CaptainApprovalSnapshot(
                    status = CaptainAccountStatus.REJECTED,
                    isApproved = false,
                    rejectionReason = rejectionReason,
                    captainName = name,
                    email = email
                )
                "suspended" -> CaptainApprovalSnapshot(
                    status = CaptainAccountStatus.SUSPENDED,
                    isApproved = false,
                    rejectionReason = rejectionReason,
                    captainName = name,
                    email = email
                )
                "blocked", "banned", "disabled" -> CaptainApprovalSnapshot(
                    status = CaptainAccountStatus.BLOCKED,
                    isApproved = false,
                    rejectionReason = rejectionReason,
                    captainName = name,
                    email = email
                )
                else -> CaptainApprovalSnapshot(
                    status = CaptainAccountStatus.PENDING,
                    isApproved = false,
                    rejectionReason = rejectionReason,
                    captainName = name,
                    email = email
                )
            }
        }

        if (explicitIsApproved == false) {
            return CaptainApprovalSnapshot(
                status = CaptainAccountStatus.PENDING,
                isApproved = false,
                rejectionReason = rejectionReason,
                captainName = name,
                email = email
            )
        }

        val legacyStatus = readNonBlankString(docData, "status").lowercase()
        return when {
            legacyStatus == "rejected" -> CaptainApprovalSnapshot(
                status = CaptainAccountStatus.REJECTED,
                isApproved = false,
                rejectionReason = rejectionReason,
                captainName = name,
                email = email
            )
            legacyStatus == "suspended" -> CaptainApprovalSnapshot(
                status = CaptainAccountStatus.SUSPENDED,
                isApproved = false,
                rejectionReason = rejectionReason,
                captainName = name,
                email = email
            )
            legacyStatus in setOf("blocked", "banned", "disabled") -> CaptainApprovalSnapshot(
                status = CaptainAccountStatus.BLOCKED,
                isApproved = false,
                rejectionReason = rejectionReason,
                captainName = name,
                email = email
            )
            legacyStatus in LEGACY_APPROVED_STATUSES -> CaptainApprovalSnapshot(
                status = CaptainAccountStatus.APPROVED,
                isApproved = true,
                rejectionReason = "",
                captainName = name,
                email = email
            )
            legacyStatus.isBlank() && explicitIsApproved == true -> CaptainApprovalSnapshot(
                status = CaptainAccountStatus.APPROVED,
                isApproved = true,
                rejectionReason = "",
                captainName = name,
                email = email
            )
            else -> CaptainApprovalSnapshot(
                status = CaptainAccountStatus.PENDING,
                isApproved = false,
                rejectionReason = rejectionReason,
                captainName = name,
                email = email
            )
        }
    }

    fun networkFailure(
        message: String,
        fallbackEmail: String = "",
        fallbackName: String = ""
    ): CaptainApprovalSnapshot {
        return CaptainApprovalSnapshot(
            status = CaptainAccountStatus.PENDING,
            isApproved = false,
            captainName = fallbackName,
            email = fallbackEmail,
            checkedFromServer = false,
            networkError = message
        )
    }

    fun statusSummaryMessage(snapshot: CaptainApprovalSnapshot): String {
        if (!snapshot.networkError.isNullOrBlank()) {
            return snapshot.networkError
        }
        return when (snapshot.status) {
            CaptainAccountStatus.PENDING ->
                "حسابك قيد المراجعة من قبل فريق إدارة البطل إكسبريس."
            CaptainAccountStatus.APPROVED ->
                "تمت الموافقة على حسابك بنجاح! مرحباً بك في البطل إكسبريس."
            CaptainAccountStatus.REJECTED ->
                if (snapshot.rejectionReason.isNotBlank()) {
                    "تم رفض طلب انضمامك: ${snapshot.rejectionReason}"
                } else {
                    "تم رفض طلب تفعيل حسابك من قبل الإدارة."
                }
            CaptainAccountStatus.SUSPENDED ->
                if (snapshot.rejectionReason.isNotBlank()) {
                    "تم إيقاف حسابك مؤقتاً: ${snapshot.rejectionReason}"
                } else {
                    "تم إيقاف حسابك مؤقتاً من قبل الإدارة. يرجى التواصل مع الدعم."
                }
            CaptainAccountStatus.BLOCKED ->
                if (snapshot.rejectionReason.isNotBlank()) {
                    "تم حظر حسابك بقرار إداري: ${snapshot.rejectionReason}"
                } else {
                    "تم حظر حسابك بقرار إداري ولا يمكنك الدخول إلى التطبيق."
                }
        }
    }

    private fun readNonBlankString(docData: Map<String, Any?>, vararg keys: String): String {
        for (key in keys) {
            val raw = docData[key]?.toString()?.trim()
            if (!raw.isNullOrBlank() && raw != "null") {
                return raw
            }
        }
        return ""
    }
}
