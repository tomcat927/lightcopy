package com.tomcat927.lightcopy

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 单页主界面：无障碍开启状态 + 一键跳转 + 受限设置引导 + 三行使用说明 + 检查更新，无其他设置项。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val updateVm = ViewModelProvider(this)[UpdateViewModel::class.java]
        setContent {
            var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }

            // 从系统设置页返回时自动刷新状态
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        accessibilityEnabled = isAccessibilityServiceEnabled()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            // 启动静默检查更新：仅发现新版本时弹窗
            LaunchedEffect(Unit) { updateVm.autoCheckIfNeeded() }

            // 保活开启时，打开 app 顺带兜底恢复一次（不等 WorkManager 巡检）
            if (RootKeeper.isKeepAliveOn(this@MainActivity)) {
                lifecycleScope.launch { RootKeeper.ensureServiceEnabled(applicationContext) }
            }

            // Root 保活开关状态：默认关，打开时才请求 root（绝不启动时偷弹 su）
            var keepAliveOn by remember { mutableStateOf(RootKeeper.isKeepAliveOn(this@MainActivity)) }
            var keepAliveBusy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val appContext = applicationContext
            val onKeepAliveToggle: (Boolean) -> Unit = { want ->
                if (!keepAliveBusy) {
                    if (!want) {
                        keepAliveOn = false
                        RootKeeper.setKeepAliveOn(appContext, false)
                    } else {
                        keepAliveBusy = true
                        scope.launch {
                            val granted = RootKeeper.requestRoot()
                            keepAliveBusy = false
                            if (granted) {
                                keepAliveOn = true
                                RootKeeper.setKeepAliveOn(appContext, true)
                                RootKeeper.ensureServiceEnabled(appContext)
                                Toast.makeText(appContext, R.string.toast_keepalive_on, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(appContext, R.string.toast_keepalive_no_root, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }

            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF00796B))) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val updateState by updateVm.state.collectAsState()
                    LightCopyScreen(
                        enabled = accessibilityEnabled,
                        updateState = updateState,
                        currentVersionName = updateVm.currentVersionName,
                        keepAliveOn = keepAliveOn,
                        keepAliveBusy = keepAliveBusy,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onCheckUpdate = { updateVm.checkNow() },
                        onKeepAliveToggle = onKeepAliveToggle,
                    )
                    UpdateDialog(updateVm)
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean =
        CopyAccessibilityService.isSelfEnabled(this)
}

@Composable
private fun LightCopyScreen(
    enabled: Boolean,
    updateState: UpdateState,
    currentVersionName: String,
    keepAliveOn: Boolean,
    keepAliveBusy: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onCheckUpdate: () -> Unit,
    onKeepAliveToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (enabled) Color(0xFFE0F2F1) else Color(0xFFFFF3E0)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (enabled) Color(0xFF00796B) else Color(0xFFEF6C00),
                            shape = CircleShape,
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(
                            if (enabled) R.string.main_status_on else R.string.main_status_off
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            if (enabled) R.string.main_status_on_sub else R.string.main_status_off_sub
                        ),
                        fontSize = 13.sp,
                        color = Color(0xFF616161),
                    )
                }
            }
        }

        if (!enabled) {
            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.main_btn_open_settings))
            }
            // Android 13+ 侧载应用开无障碍可能弹「受限设置」
            if (Build.VERSION.SDK_INT >= 33) {
                Text(
                    text = stringResource(R.string.main_restricted_hint),
                    fontSize = 13.sp,
                    color = Color(0xFF8D6E63),
                )
            }
        }

        Text(
            text = stringResource(R.string.main_how_title),
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        Text(text = stringResource(R.string.main_how_1), fontSize = 15.sp)
        Text(text = stringResource(R.string.main_how_2), fontSize = 15.sp)
        Text(text = stringResource(R.string.main_how_3), fontSize = 15.sp)

        Text(
            text = stringResource(R.string.main_note),
            fontSize = 12.sp,
            color = Color(0xFF9E9E9E),
        )

        // Root 保活：唯一的开关
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F3F4))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.keepalive_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            if (keepAliveOn) R.string.keepalive_desc_on else R.string.keepalive_desc_off
                        ),
                        fontSize = 13.sp,
                        color = Color(0xFF616161),
                    )
                }
                Switch(
                    checked = keepAliveOn,
                    onCheckedChange = onKeepAliveToggle,
                    enabled = !keepAliveBusy,
                )
            }
        }

        // 底部唯一的「设置项」：检查更新
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.main_version_label, currentVersionName),
                fontSize = 13.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onCheckUpdate,
                enabled = updateState !is UpdateState.Downloading &&
                    updateState !is UpdateState.Checking,
            ) {
                Text(
                    text = stringResource(
                        if (updateState is UpdateState.Checking) R.string.main_checking
                        else R.string.main_check_update
                    )
                )
            }
        }
    }
}
