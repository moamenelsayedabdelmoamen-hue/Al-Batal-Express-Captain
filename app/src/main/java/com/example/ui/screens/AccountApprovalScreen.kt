package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.CaptainAccountStatus
import com.example.data.CaptainApprovalSnapshot
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.HeroYellowDark

@Composable
fun AccountApprovalScreen(
    approval: CaptainApprovalSnapshot?,
    isRefreshing: Boolean,
    statusFeedback: String? = null,
    onRefreshStatus: () -> Unit,
    onLogout: () -> Unit
) {
    val snapshot = approval ?: CaptainApprovalSnapshot(
        status = CaptainAccountStatus.PENDING,
        isApproved = false
    )
    val isNetworkError = !snapshot.networkError.isNullOrBlank()

    val accentColor: Color
    val statusIcon: ImageVector
    val titleText: String
    val bodyText: String
    val badgeLabel: String

    when {
        isNetworkError -> {
            accentColor = HeroYellowDark
            statusIcon = Icons.Filled.Warning
            titleText = stringResource(R.string.approval_network_error_title)
            bodyText = snapshot.networkError
                ?: stringResource(R.string.approval_network_error_body)
            badgeLabel = stringResource(R.string.approval_badge_network_error)
        }
        snapshot.status == CaptainAccountStatus.REJECTED -> {
            accentColor = ExpressRed
            statusIcon = Icons.Filled.Close
            titleText = stringResource(R.string.approval_rejected_title)
            bodyText = stringResource(R.string.approval_rejected_body)
            badgeLabel = stringResource(R.string.approval_badge_rejected)
        }
        snapshot.status == CaptainAccountStatus.SUSPENDED -> {
            accentColor = HeroYellowDark
            statusIcon = Icons.Filled.Warning
            titleText = stringResource(R.string.approval_suspended_title)
            bodyText = stringResource(R.string.approval_suspended_body)
            badgeLabel = stringResource(R.string.approval_badge_suspended)
        }
        snapshot.status == CaptainAccountStatus.BLOCKED -> {
            accentColor = ExpressRed
            statusIcon = Icons.Filled.Lock
            titleText = stringResource(R.string.approval_blocked_title)
            bodyText = stringResource(R.string.approval_blocked_body)
            badgeLabel = stringResource(R.string.approval_badge_blocked)
        }
        else -> {
            accentColor = HeroYellow
            statusIcon = Icons.Filled.Info
            titleText = stringResource(R.string.approval_pending_title)
            bodyText = stringResource(R.string.approval_pending_body)
            badgeLabel = stringResource(R.string.approval_badge_pending)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("account_approval_screen"),
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DarkBackground,
                            DarkSurface,
                            DarkBackground
                        )
                    )
                )
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // El-Batal Express Brand Logo
                Surface(
                    shape = CircleShape,
                    color = DarkSurfaceVariant,
                    border = BorderStroke(1.5.dp, HeroYellow.copy(alpha = 0.55f)),
                    modifier = Modifier.size(88.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.el_batal_logo_1784764128367),
                        contentDescription = stringResource(R.string.approval_logo_desc),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.app_name),
                    color = HeroYellow,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Main Status Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.14f))
                                .border(1.5.dp, accentColor.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = titleText,
                                tint = accentColor,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Current Status Badge
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = accentColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                            modifier = Modifier.testTag("approval_status_badge")
                        ) {
                            Text(
                                text = stringResource(R.string.approval_current_status_prefix, badgeLabel),
                                color = accentColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = titleText,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("approval_title_text")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = bodyText,
                            color = Color(0xFFD0D0DC),
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("approval_body_text")
                        )

                        if (snapshot.rejectionReason.isNotBlank() && !isNetworkError && snapshot.status != CaptainAccountStatus.PENDING) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("approval_rejection_reason_box"),
                                shape = RoundedCornerShape(14.dp),
                                color = ExpressRed.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, ExpressRed.copy(alpha = 0.35f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(R.string.approval_reason_label),
                                        color = ExpressRed,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = snapshot.rejectionReason,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        if (snapshot.captainName.isNotBlank() || snapshot.email.isNotBlank()) {
                            Spacer(modifier = Modifier.height(18.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = DarkSurfaceVariant
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (snapshot.captainName.isNotBlank()) {
                                        Text(
                                            text = snapshot.captainName,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (snapshot.email.isNotBlank()) {
                                        Text(
                                            text = snapshot.email,
                                            color = Color(0xFFA0A0B2),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        if (!isNetworkError && snapshot.status == CaptainAccountStatus.PENDING) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.approval_auto_refresh_hint),
                                color = HeroYellow.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        if (!statusFeedback.isNullOrBlank() && statusFeedback != bodyText) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = statusFeedback,
                                color = HeroYellow,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("approval_feedback_text")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Primary Action: Refresh Account Status
                Button(
                    onClick = onRefreshStatus,
                    enabled = !isRefreshing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ExpressRed,
                        contentColor = Color.White,
                        disabledContainerColor = ExpressRed.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("approval_refresh_button")
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.approval_refreshing_label),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.approval_refresh_button),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.approval_refresh_button),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Secondary Action: Logout
                OutlinedButton(
                    onClick = onLogout,
                    enabled = !isRefreshing,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("approval_logout_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = stringResource(R.string.approval_logout_button),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.approval_logout_button),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
