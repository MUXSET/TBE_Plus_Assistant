package com.muxset.tbeplus.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muxset.tbeplus.ui.MainViewModel
import com.muxset.tbeplus.ui.UiState
import com.muxset.tbeplus.ui.theme.LocalTbeColors
import java.time.YearMonth

@Composable
fun AccountsScreen(
    state: UiState,
    onSwitchAccount: (String) -> Unit,
    onRefreshAccount: (String) -> Unit,
    onClearCache: (String) -> Unit,
    onDeleteAccount: (Int) -> Unit,
    onAddOrUpdateAccount: (String, String) -> Unit,
) {
    val c = LocalTbeColors.current
    val currentMonth = YearMonth.now().toString()

    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var confirmDelete by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var confirmClearCache by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    text = "账号管理",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "每个账号的凭据、月度进度与本地缓存完全独立隔离",
                    fontSize = 12.sp,
                    color = c.textMuted,
                )
            }
        }

        if (state.config.accounts.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = c.bgCard,
                    border = BorderStroke(1.dp, c.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "暂无已配置账号，请在下方输入工号和密码添加。",
                        fontSize = 13.sp,
                        color = c.textMuted,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        } else {
            itemsIndexed(state.config.accounts, key = { _, a -> a.username }) { idx, acc ->
                val isActive = idx == state.config.activeAccount
                val cacheSize = state.cacheCounts[acc.username] ?: 0
                val monthly = acc.monthlyStats.takeIf { it.month == currentMonth }
                val progressText = if (monthly != null) {
                    "${monthly.monthlyLiked} / ${monthly.monthlyTotal}"
                } else {
                    "---"
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = c.bgCard,
                    border = BorderStroke(1.dp, if (isActive) c.emerald.copy(alpha = 0.5f) else c.border),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.accentSoft),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = acc.username.takeLast(2).uppercase(),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = c.accentText,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Text(
                                        text = acc.username,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = c.textPrimary,
                                    )
                                    if (isActive) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(c.emeraldSoft)
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "当前使用",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = c.emeraldText,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = if (acc.tbeaArtToken.isNotBlank()) "已缓存登录凭据" else "待登录验证",
                                    fontSize = 11.5.sp,
                                    color = c.textMuted,
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = c.bgInset,
                            border = BorderStroke(1.dp, c.borderSubtle),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                MetricCell("本月进度", progressText, Modifier.weight(1f))
                                MetricCell("已赞缓存", "$cacheSize 篇", Modifier.weight(1f))
                                MetricCell(
                                    "上次扫描",
                                    MainViewModel.formatRelativeTime(acc.sweepStats.lastSweepTime),
                                    Modifier.weight(1f),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (!isActive) {
                                FilledTonalButton(
                                    onClick = { onSwitchAccount(acc.username) },
                                    enabled = !state.isBusy,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("设为当前", fontSize = 11.5.sp) }
                            }
                            FilledTonalButton(
                                onClick = { onRefreshAccount(acc.username) },
                                enabled = !state.isBusy,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(8.dp),
                            ) { Text("刷新凭据", fontSize = 11.5.sp) }
                            FilledTonalButton(
                                onClick = {
                                    usernameInput = acc.username
                                    passwordInput = ""
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(8.dp),
                            ) { Text("改密", fontSize = 11.5.sp) }
                            FilledTonalButton(
                                onClick = { confirmClearCache = acc.username },
                                enabled = !state.isScanning,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(8.dp),
                            ) { Text("清缓存", fontSize = 11.5.sp) }

                            Spacer(modifier = Modifier.weight(1f))

                            TextButton(
                                onClick = { confirmDelete = idx to acc.username },
                                enabled = !state.isBusy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text("删除", fontSize = 11.5.sp, color = c.roseText)
                            }
                        }
                    }
                }
            }
        }

        // 添加 / 改密卡片
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.bgCard,
                border = BorderStroke(1.dp, c.border),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column {
                        Text(
                            text = "添加账号 / 更新密码",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "提交后将呼出内置浏览器完成登录验证，成功后自动保存凭据并切换为当前账号",
                            fontSize = 11.5.sp,
                            color = c.textMuted,
                        )
                    }

                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("工号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("登录密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = "切换密码可见",
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (!state.isBusy) {
                                    onAddOrUpdateAccount(usernameInput, passwordInput)
                                }
                            },
                        ),
                    )

                    Button(
                        onClick = { onAddOrUpdateAccount(usernameInput, passwordInput) },
                        enabled = !state.isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("验证并保存账号", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    confirmDelete?.let { (idx, name) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除账号") },
            text = { Text("确定删除账号 $name 及其本地已赞缓存吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        onDeleteAccount(idx)
                    },
                ) { Text("确认删除", color = c.roseText) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }

    confirmClearCache?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmClearCache = null },
            title = { Text("清空缓存") },
            text = { Text("确定清空账号 $name 的本地已赞缓存吗？下次扫描将重新核验服务端状态。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCache = null
                        onClearCache(name)
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier) {
    val c = LocalTbeColors.current
    Column(modifier = modifier) {
        Text(text = label, fontSize = 10.5.sp, color = c.textMuted)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
    }
}
