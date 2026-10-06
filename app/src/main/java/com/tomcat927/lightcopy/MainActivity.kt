package com.tomcat927.lightcopy

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
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
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF00796B))) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val updateState by updateVm.state.collectAsState()
                    LightCopyScreen(
                        enabled = accessibilityEnabled,
                        updateState = updateState,
                        currentVersionName = updateVm.currentVersionName,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onCheckUpdate = { updateVm.checkNow() },
                    )
                    UpdateDialog(updateVm)
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, CopyAccessibilityService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (entry.equals(component.flattenToString(), ignoreCase = true) ||
                entry.equals(component.flattenToShortString(), ignoreCase = true)
            ) return true
        }
        return false
    }
}

@Composable
private fun LightCopyScreen(
    enabled: Boolean,
    updateState: UpdateState,
    currentVersionName: String,
    onOpenAccessibilitySettings: () -> Unit,
    onCheckUpdate: () -> Unit,
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
