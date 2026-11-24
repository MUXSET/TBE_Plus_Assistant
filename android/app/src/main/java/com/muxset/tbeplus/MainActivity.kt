package com.muxset.tbeplus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxset.tbeplus.ui.MainViewModel
import com.muxset.tbeplus.ui.components.AuthWebViewSheet
import com.muxset.tbeplus.ui.screens.AccountsScreen
import com.muxset.tbeplus.ui.screens.DashboardScreen
import com.muxset.tbeplus.ui.screens.SettingsScreen
import com.muxset.tbeplus.ui.theme.LocalTbeColors
import com.muxset.tbeplus.ui.theme.TbeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TbePlusApp()
        }
    }
}

@Composable
private fun TbePlusApp(vm: MainViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    var onboardingUsername by rememberSaveable { mutableStateOf("") }
    var onboardingPassword by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.config.accounts.isNotEmpty()) {
        if (state.config.accounts.isNotEmpty()) {
            onboardingPassword = ""
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    fun askNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            vm.consumeSnackbar()
        }
    }

    TbeTheme(themeMode = state.config.appearanceMode) {
        val c = LocalTbeColors.current
        Scaffold(
            containerColor = c.bgCanvas,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    containerColor = c.bgCard,
                    tonalElevation = 0.dp,
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Rounded.Dashboard, contentDescription = "工作台") },
                        label = { Text("工作台") },
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Rounded.ManageAccounts, contentDescription = "账号") },
                        label = { Text("账号 (${state.config.accounts.size})") },
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { currentTab = 2 },
                        icon = { Icon(Icons.Rounded.Tune, contentDescription = "设置") },
                        label = { Text("设置") },
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (currentTab) {
                    0 -> DashboardScreen(
                        state = state,
                        onSelectPreset = vm::selectPreset,
                        onCustomDateChange = vm::setCustomDateRange,
                        onStartScan = vm::startScan,
                        onStopTask = vm::stopCurrentTask,
                        onRefreshToken = { vm.manualRefreshToken() },
                        onCheckToken = vm::checkTokenHealth,
                        onExportReport = vm::exportMonthlyReport,
                        onSwitchAccount = vm::switchAccount,
                        onClearLogs = vm::clearLogs,
                        onGoAccounts = { currentTab = 1 },
                        onShowToast = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )
                    1 -> AccountsScreen(
                        state = state,
                        onSwitchAccount = vm::switchAccount,
                        onRefreshAccount = { uname -> vm.manualRefreshToken(uname) },
                        onClearCache = vm::clearAccountCache,
                        onDeleteAccount = vm::removeAccount,
                        onAddOrUpdateAccount = vm::startAddOrUpdateAccount,
                    )
                    2 -> SettingsScreen(
                        state = state,
                        onThemeChange = vm::setThemeMode,
                        onSaveSettings = vm::saveSettings,
                        onRequestNotificationPermission = ::askNotificationIfNeeded,
                        onShowToast = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )
                }
            }

            // 首次无账号欢迎引导弹窗
            if (state.config.accounts.isEmpty() && state.pendingAuth == null) {
                OnboardingDialog(
                    username = onboardingUsername,
                    onUsernameChange = { onboardingUsername = it },
                    password = onboardingPassword,
                    onPasswordChange = { onboardingPassword = it },
                    errorMessage = state.lastAuthError,
                    onSubmit = { vm.startAddOrUpdateAccount(onboardingUsername, onboardingPassword) },
                )
            }

            // 自动认证 / 续期 WebView 弹窗
            state.pendingAuth?.let { req ->
                AuthWebViewSheet(
                    username = req.username,
                    password = req.password,
                    channels = req.channels,
                    onSuccess = vm::onWebViewAuthSuccess,
                    onError = vm::onWebViewAuthError,
                    onDismiss = vm::dismissWebViewAuth,
                )
            }
        }
    }
}

@Composable
private fun OnboardingDialog(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    errorMessage: String?,
    onSubmit: () -> Unit,
) {
    val c = LocalTbeColors.current
    var pwdVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        containerColor = c.bgCard,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF4338CA), c.accent))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "欢迎使用 TBE+ 阅赞助手",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "请验证并添加您的首个 TBE+ 账号以开启自动阅读与点赞",
                    fontSize = 12.5.sp,
                    color = c.textSecondary,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text("工号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    visualTransformation = if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pwdVisible = !pwdVisible }) {
                            Icon(
                                if (pwdVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        color = c.roseSoft,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = errorMessage,
                            fontSize = 12.sp,
                            color = c.roseText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White),
            ) {
                Text("验证并开始使用", fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
