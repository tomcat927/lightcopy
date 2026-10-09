package com.tomcat927.lightcopy

import android.app.Application
import android.util.Log

class LightCopyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RemoteLog.sessionStart(this)

        // 崩溃捕获：落盘 + 尽力上传，再交回系统默认处理
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            RemoteLog.e("CRASH", "未捕获异常 thread=${thread.name}", throwable)
            RemoteLog.upload("crash", null)
            try {
                Thread.sleep(1500)   // 给上传线程一点时间，失败也已有本地文件兜底
            } catch (_: InterruptedException) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 启动时把上次会话遗留的待传日志发出去
        RemoteLog.upload("app-start", null)
        // 进程启动快照：排查"无障碍 bind 被启动窗口事务打断"时，
        // 必须能把 `AccessibilityManagerService: wait for adding window timeout: <pid>`
        // 与我们自己的 pid 对齐，并知道这是不是一次冷启动（对照 processStartAt）。
        RemoteLog.i(
            "LightCopy",
            "app process start pid=${android.os.Process.myPid()} " +
                "bindEnabled=${CopyAccessibilityService.isSelfEnabled(this)} " +
                "instance=${CopyAccessibilityService.instance != null}",
        )
        Log.d("LightCopy", "app process start")

        // 每 6 小时后台检查并预下载更新（关掉 app 也在跑）
        runCatching {
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                UpdateWorker.WORK_PERIODIC,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                androidx.work.PeriodicWorkRequestBuilder<UpdateWorker>(6, java.util.concurrent.TimeUnit.HOURS)
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .build(),
            )
        }
    }
}
