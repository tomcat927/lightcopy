package com.tomcat927.lightcopy

import android.content.ComponentName
import android.content.Intent
import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

            // 一键把「复制模式」瓦片加进快捷设置面板（Android 13+ 系统弹窗；失败/低版本引导手动）
            val onAddTile: () -> Unit = { requestAddTile() }

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

            // 远程日志（OpenList）：默认关；日志作为独立诊断文件上传到自配 OpenList 目录
            var remoteLogOn by remember { mutableStateOf(RemoteLog.isEnabled(this@MainActivity)) }
            var showLogDialog by remember { mutableStateOf(false) }
            var logBaseUrl by remember { mutableStateOf("") }
            var logUsername by remember { mutableStateOf("") }
            var logPassword by remember { mutableStateOf("") }
            var logTargetPath by remember { mutableStateOf("") }
            val openLogDialog: () -> Unit = {
                logBaseUrl = RemoteLog.getBaseUrl(appContext)
                logUsername = RemoteLog.getUsername(appContext)
                logPassword = RemoteLog.getPassword(appContext)
                logTargetPath = RemoteLog.getTargetPath(appContext)
                showLogDialog = true
            }
            val onRemoteLogToggle: (Boolean) -> Unit = { want ->
                if (want && !RemoteLog.isConfigured(appContext)) {
                    Toast.makeText(appContext, R.string.remote_log_need_config, Toast.LENGTH_LONG).show()
                    openLogDialog()
                } else {
                    remoteLogOn = want
                    RemoteLog.setEnabled(appContext, want)
                }
            }
            val onUploadNow: () -> Unit = {
                RemoteLog.upload("manual") { result, path ->
                    val msg = when (result) {
                        RemoteLog.UploadResult.OK -> appContext.getString(R.string.remote_log_uploaded, path ?: "")
                        RemoteLog.UploadResult.NOT_CONFIGURED -> appContext.getString(R.string.remote_log_need_config)
                        RemoteLog.UploadResult.AUTH_FAILED -> appContext.getString(R.string.remote_log_auth_failed)
                        RemoteLog.UploadResult.FAILED -> appContext.getString(R.string.remote_log_upload_failed)
                    }
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                }
            }
            val onTestConnection: () -> Unit = {
                RemoteLog.testConnection(
                    logBaseUrl, logUsername, logPassword, logTargetPath,
                ) { result ->
                    val msg = when (result) {
                        RemoteLog.UploadResult.OK -> appContext.getString(R.string.remote_log_test_ok)
                        RemoteLog.UploadResult.NOT_CONFIGURED -> appContext.getString(R.string.remote_log_need_config)
                        RemoteLog.UploadResult.AUTH_FAILED -> appContext.getString(R.string.remote_log_auth_failed)
                        RemoteLog.UploadResult.FAILED -> appContext.getString(R.string.remote_log_upload_failed)
                    }
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                }
            }
            val onCopyLogs: () -> Unit = {
                val ok = RemoteLog.copyAllToClipboard(appContext)
                Toast.makeText(
                    appContext,
                    if (ok) R.string.remote_log_copied else R.string.remote_log_empty,
                    Toast.LENGTH_SHORT,
                ).show()
            }

            // 镜像加速更新下载（照 ncm-cloud-player）：默认 gh-proxy 优先，关闭后 GitHub 直连优先
            var mirrorOn by remember { mutableStateOf(Updater.isPreferMirror(this@MainActivity)) }
            val onMirrorToggle: (Boolean) -> Unit = { want ->
                mirrorOn = want
                Updater.setPreferMirror(appContext, want)
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
                        remoteLogOn = remoteLogOn,
                        mirrorOn = mirrorOn,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onAddTile = onAddTile,
                        onCheckUpdate = { updateVm.checkNow(mirrorOn) },
                        onKeepAliveToggle = onKeepAliveToggle,
                        onOpenLogDialog = openLogDialog,
                        onRemoteLogToggle = onRemoteLogToggle,
                        onUploadNow = onUploadNow,
                        onCopyLogs = onCopyLogs,
                        onMirrorToggle = onMirrorToggle,
                    )
                    UpdateDialog(updateVm)
                    if (showLogDialog) {
                        RemoteLogDialog(
                            baseUrl = logBaseUrl,
                            username = logUsername,
                            password = logPassword,
                            targetPath = logTargetPath,
                            onBaseUrlChange = { logBaseUrl = it },
                            onUsernameChange = { logUsername = it },
                            onPasswordChange = { logPassword = it },
                            onTargetPathChange = { logTargetPath = it },
                            onSave = {
                                RemoteLog.saveConfig(appContext, logBaseUrl, logUsername, logPassword, logTargetPath)
                                if (RemoteLog.isConfigured(appContext)) {
                                    remoteLogOn = true
                                    RemoteLog.setEnabled(appContext, true)
                                }
                                Toast.makeText(appContext, R.string.remote_log_saved, Toast.LENGTH_SHORT).show()
                                showLogDialog = false
                            },
                            onUploadNow = onUploadNow,
                            onTestConnection = onTestConnection,
                            onCopyLogs = onCopyLogs,
                            onDismiss = { showLogDialog = false },
                        )
                    }
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean =
        CopyAccessibilityService.isSelfEnabled(this)

    /**
     * 一键添加瓦片：requestAddTileService 是 SystemApi（编译期不可见、运行期存在），
     * 反射调用 TileService / StatusBarManager 两个落点；任何失败退回手动添加引导。
     * 结果码为 TileService.TILE_ADD_REQUEST_* 的隐藏常量值：3=SUCCESS，2=ALREADY_ADDED。
     */
    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(applicationContext, R.string.toast_tile_manual, Toast.LENGTH_LONG).show()
            return
        }
        val componentName = ComponentName(this, CopyModeTileService::class.java)
        val label = getString(R.string.tile_label)
        val icon = Icon.createWithResource(applicationContext, R.drawable.ic_tile)
        val executor = ContextCompat.getMainExecutor(this)
        val consumer = java.util.function.Consumer<Int> { result ->
            val msgRes = when (result) {
                3 -> R.string.toast_tile_added
                2 -> R.string.toast_tile_already
                else -> R.string.toast_tile_manual
            }
            Toast.makeText(applicationContext, msgRes, Toast.LENGTH_LONG).show()
        }
        val paramTypes = arrayOf(
            ComponentName::class.java,
            CharSequence::class.java,
            Icon::class.java,
            java.util.concurrent.Executor::class.java,
            java.util.function.Consumer::class.java,
        )
        val invoked = runCatching {
            val method = runCatching {
                TileService::class.java.getMethod("requestAddTileService", *paramTypes)
            }.getOrElse {
                StatusBarManager::class.java.getMethod("requestAddTileService", *paramTypes)
            }
            if (method.declaringClass == TileService::class.java) {
                method.invoke(null, componentName, label, icon, executor, consumer)
            } else {
                method.invoke(getSystemService(StatusBarManager::class.java), componentName, label, icon, executor, consumer)
            }
        }
        if (invoked.isFailure) {
            Toast.makeText(applicationContext, R.string.toast_tile_manual, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun RemoteLogDialog(
    baseUrl: String,
    username: String,
    password: String,
    targetPath: String,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTargetPathChange: (String) -> Unit,
    onSave: () -> Unit,
    onUploadNow: () -> Unit,
    onTestConnection: () -> Unit,
    onCopyLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_log_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.remote_log_dialog_hint),
                    fontSize = 13.sp,
                    color = Color(0xFF616161),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text(stringResource(R.string.remote_log_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        label = { Text(stringResource(R.string.remote_log_username)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.remote_log_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetPath,
                    onValueChange = onTargetPathChange,
                    label = { Text(stringResource(R.string.remote_log_target_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onTestConnection) { Text(stringResource(R.string.remote_log_test)) }
                    TextButton(onClick = onUploadNow) { Text(stringResource(R.string.remote_log_upload_now)) }
                    TextButton(onClick = onCopyLogs) { Text(stringResource(R.string.remote_log_copy_all)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.remote_log_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        },
    )
}

@Composable
private fun LightCopyScreen(
    enabled: Boolean,
    updateState: UpdateState,
    currentVersionName: String,
    keepAliveOn: Boolean,
    keepAliveBusy: Boolean,
    remoteLogOn: Boolean,
    mirrorOn: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onAddTile: () -> Unit,
    onCheckUpdate: () -> Unit,
    onKeepAliveToggle: (Boolean) -> Unit,
    onOpenLogDialog: () -> Unit,
    onRemoteLogToggle: (Boolean) -> Unit,
    onUploadNow: () -> Unit,
    onCopyLogs: () -> Unit,
    onMirrorToggle: (Boolean) -> Unit,
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

        // 一键添加通知栏瓦片
        OutlinedButton(
            onClick = onAddTile,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.main_add_tile))
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

        // 远程日志卡片：点卡片改 URL/上传/复制，开关直接切
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F3F4)),
            modifier = Modifier.clickable(onClick = onOpenLogDialog),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remote_log_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            if (remoteLogOn) R.string.remote_log_desc_on else R.string.remote_log_desc_off
                        ),
                        fontSize = 13.sp,
                        color = Color(0xFF616161),
                    )
                }
                Switch(
                    checked = remoteLogOn,
                    onCheckedChange = onRemoteLogToggle,
                )
            }
        }

        // 镜像加速更新下载开关
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F3F4))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mirror_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            if (mirrorOn) R.string.mirror_desc_on else R.string.mirror_desc_off
                        ),
                        fontSize = 13.sp,
                        color = Color(0xFF616161),
                    )
                }
                Switch(
                    checked = mirrorOn,
                    onCheckedChange = onMirrorToggle,
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
