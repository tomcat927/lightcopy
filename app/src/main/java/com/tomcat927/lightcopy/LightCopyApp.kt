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
        Log.d("LightCopy", "app process start")
    }
}
