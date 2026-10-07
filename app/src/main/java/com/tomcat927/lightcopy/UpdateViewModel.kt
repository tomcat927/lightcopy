package com.tomcat927.lightcopy

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: Updater.UpdateInfo) : UpdateState
    data class Downloading(val received: Long, val total: Long) : UpdateState {
        val percent: Int
            get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else 0
    }
    data class Ready(val info: Updater.UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** 轻复制没有设置页：启动静默检查 + 主页「检查更新」按钮 + 镜像加速开关 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "LightCopy"
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersionName: String = Updater.currentVersionName(app)
    val currentVersionCode: Long = Updater.currentVersionCode(app)

    /** 每个进程只静默自动检查一次 */
    private var autoChecked = false

    /** 启动静默检查：只有发现新版本才弹窗，无更新或失败均不打扰 */
    fun autoCheckIfNeeded() {
        if (autoChecked) return
        autoChecked = true
        viewModelScope.launch {
            try {
                val info = Updater.checkForUpdate(getApplication(), Updater.isPreferMirror(getApplication()))
                if (info != null) {
                    RemoteLog.d(TAG, "update available: ${info.tagName}")
                    _state.value = UpdateState.Available(info)
                }
            } catch (e: Exception) {
                RemoteLog.w(TAG, "auto check failed: ${e.message}")
            }
        }
    }

    fun checkNow(preferMirror: Boolean) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val info = Updater.checkForUpdate(getApplication(), preferMirror)
                _state.value = if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: Exception) {
                RemoteLog.w(TAG, "check error ${e.javaClass.simpleName}: ${e.message}")
                _state.value = UpdateState.Failed(e.message ?: "检查更新失败")
            }
        }
    }

    fun download(info: Updater.UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(0, 0)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                // 主备 URL 按镜像开关排好序，且每条 URL 按归属自动选客户端，逐条尝试即可
                val apk = Updater.downloadAndVerify(app, info, onProgress = ::onProgress)
                RemoteLog.d(TAG, "download ok size=${apk.length()}")
                // 校验通过直接调起系统安装器（系统安装确认即唯一一次确认）；
                // 仅在首次缺「安装未知应用」权限时才落到 Ready 弹窗引导授权
                if (Updater.canInstall(app)) {
                    Updater.installApk(app, apk)
                    RemoteLog.d(TAG, "installer launched")
                    _state.value = UpdateState.Idle
                } else {
                    RemoteLog.d(TAG, "install permission missing, ask user to grant")
                    _state.value = UpdateState.Ready(info, apk)
                }
            } catch (e: Exception) {
                RemoteLog.w(TAG, "download fail ${e.javaClass.simpleName}: ${e.message}")
                _state.value = UpdateState.Failed(e.message ?: "下载失败")
            }
        }
    }

    private fun onProgress(received: Long, total: Long) {
        _state.value = UpdateState.Downloading(received, total)
    }

    fun dismiss() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Idle
    }

    /** 安装：无「安装未知应用」权限时先跳系统设置，返回是否已发起安装 */
    fun install(context: Context, apk: File): Boolean {
        return if (Updater.canInstall(context)) {
            Updater.installApk(context, apk)
            true
        } else {
            Updater.openInstallPermissionSettings(context)
            false
        }
    }
}
