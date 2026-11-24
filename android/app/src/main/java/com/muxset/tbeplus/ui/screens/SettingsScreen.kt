package com.muxset.tbeplus.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muxset.tbeplus.data.ChannelConfig
import com.muxset.tbeplus.data.DEFAULT_CHANNELS
import com.muxset.tbeplus.data.ThemeMode
import com.muxset.tbeplus.ui.UiState
import com.muxset.tbeplus.ui.theme.LocalTbeColors

private val INTERVAL_CHOICES = listOf(
    0.25 to "15分钟",
    0.5 to "30分钟",
    1.0 to "1小时",
    2.0 to "2小时",
    6.0 to "6小时",
)

@Composable
fun SettingsScreen(
    state: UiState,
    onThemeChange: (ThemeMode) -> Unit,
    onSaveSettings: (
        scanIntervalHours: Double,
        autoBackgroundEnabled: Boolean,
        autoCheckMonthlyTotal: Boolean,
        disableSystemProxy: Boolean,
        multiAccountAuto: Boolean,
        channels: List<ChannelConfig>,
    ) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onShowToast: (String) -> Unit,
) {
    val c = LocalTbeColors.current
    val context = LocalContext.current
    val cfg = state.config

    var scanInterval by remember { mutableDoubleStateOf(cfg.scanIntervalHours) }
    var autoBg by remember { mutableStateOf(cfg.autoBackgroundEnabled) }
    var autoCheck by remember { mutableStateOf(cfg.autoCheckMonthlyTotal) }
    var disableProxy by remember { mutableStateOf(cfg.disableSystemProxy) }
    var multiAuto by remember { mutableStateOf(cfg.multiAccountAuto) }
    var channels by remember { mutableStateOf(cfg.channels) }

    LaunchedEffect(cfg) {
        scanInterval = cfg.scanIntervalHours
        autoBg = cfg.autoBackgroundEnabled
        autoCheck = cfg.autoCheckMonthlyTotal
        disableProxy = cfg.disableSystemProxy
        multiAuto = cfg.multiAccountAuto
        channels = cfg.channels
    }

    var showAddChannel by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "偏好设置",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    Text(
                        text = "后台挂机、代理直连、主题外观与频道列表",
                        fontSize = 12.sp,
                        color = c.textMuted,
                    )
                }
                Button(
                    onClick = {
                        onSaveSettings(
                            scanInterval,
                            autoBg,
                            autoCheck,
                            disableProxy,
                            multiAuto,
                            channels,
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.accent,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 1) 外观主题
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.bgCard,
                border = BorderStroke(1.dp, c.border),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "外观主题",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.bgInset)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            ThemeMode.SYSTEM to "跟随系统",
                            ThemeMode.DARK to "深色",
                            ThemeMode.LIGHT to "浅色",
                        ).forEach { (mode, label) ->
                            val selected = cfg.appearanceMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onThemeChange(mode) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) c.bgCard else Color.Transparent,
                                border = if (selected) BorderStroke(1.dp, c.border) else null,
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) c.textPrimary else c.textMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2) 后台定时挂机 (WorkManager)
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.bgCard,
                border = BorderStroke(1.dp, c.border),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "后台自动定时挂机",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    SwitchRow(
                        title = "开启后台定时自动扫描 (WorkManager)",
                        desc = "在网络连接状态下由系统定期静默唤醒补赞；发现新点赞或凭据过期时推送通知提醒",
                        checked = autoBg,
                        onCheckedChange = { enabled ->
                            autoBg = enabled
                            if (enabled) onRequestNotificationPermission()
                        },
                    )

                    HorizontalDivider(color = c.borderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "扫描间隔周期",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        INTERVAL_CHOICES.forEach { (hrs, label) ->
                            val selected = kotlin.math.abs(scanInterval - hrs) < 0.01
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) c.accent else c.bgInset,
                                border = BorderStroke(1.dp, if (selected) c.accent else c.border),
                                modifier = Modifier.clickable { scanInterval = hrs },
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    fontSize = 12.sp,
                                    color = if (selected) Color.White else c.textSecondary,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = c.borderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                    SwitchRow(
                        title = "多账号依次轮询挂机",
                        desc = "开启后，后台每次定时唤醒将按顺序自动遍历全部已添加账号",
                        checked = multiAuto,
                        onCheckedChange = { multiAuto = it },
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                context.startActivity(intent)
                            }.onFailure {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }.onFailure {
                                    onShowToast("请在系统设置 -> 应用省电策略中设为「无限制」")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("允许后台运行（加入电池优化白名单）", fontSize = 12.sp)
                    }
                }
            }
        }

        // 3) 网络与自检策略
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.bgCard,
                border = BorderStroke(1.dp, c.border),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "网络与自检策略",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SwitchRow(
                        title = "启动时自动统计本月文章总数",
                        desc = "进入应用或切换账号时后台静默统计当月总文章数，若凭据过期自动续期",
                        checked = autoCheck,
                        onCheckedChange = { autoCheck = it },
                    )
                    HorizontalDivider(color = c.borderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                    SwitchRow(
                        title = "强制直连（绕过手机系统代理 / VPN）",
                        desc = "直连平台接口，避免手机端挂了代理规则后导致无法连通或连接重置",
                        checked = disableProxy,
                        onCheckedChange = { disableProxy = it },
                    )
                }
            }
        }

        // 4) 目标监控频道
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.bgCard,
                border = BorderStroke(1.dp, c.border),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "目标监控频道",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    channels = DEFAULT_CHANNELS
                                    onShowToast("已恢复默认频道（点击右上角保存生效）")
                                },
                            ) {
                                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("默认", fontSize = 12.sp)
                            }
                            TextButton(onClick = { showAddChannel = true }) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("添加", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        channels.forEachIndexed { idx, ch ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = c.bgInset,
                                border = BorderStroke(1.dp, c.borderSubtle),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ch.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.textPrimary,
                                        )
                                        Text(
                                            text = ch.id,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = c.textMuted,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (channels.size <= 1) {
                                                onShowToast("请至少保留一个监控频道")
                                            } else {
                                                channels = channels.toMutableList().also { it.removeAt(idx) }
                                            }
                                        },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = "删除频道",
                                            tint = c.textMuted,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddChannel) {
        var chName by remember { mutableStateOf("") }
        var chId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddChannel = false },
            title = { Text("添加目标频道") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = chName,
                        onValueChange = { chName = it },
                        label = { Text("频道名称") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = chId,
                        onValueChange = { chId = it },
                        label = { Text("频道 ID (如 XT-xxx)") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = chName.trim()
                        val id = chId.trim()
                        if (n.isNotBlank() && id.isNotBlank()) {
                            if (channels.none { it.id == id }) {
                                channels = channels + ChannelConfig(n, id)
                            }
                            showAddChannel = false
                        } else {
                            onShowToast("请填写完整频道名称和 ID")
                        }
                    },
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddChannel = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = LocalTbeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = desc, fontSize = 11.5.sp, color = c.textMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
