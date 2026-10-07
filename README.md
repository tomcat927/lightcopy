# 轻复制 lightcopy

自用 Android 点选复制工具：复刻 Universal Copy 的核心能力，单一功能、无设置项。

- 下拉通知栏 → 点「复制模式」快捷瓦片进入选择模式（再点一次瓦片=取消）；无障碍未开启时瓦片会用 root/授权权限自动开启，无需去设置页
- 主页「添加『复制模式』到通知栏」按钮一键把瓦片加进快捷设置面板
- 无障碍服务读取当前屏幕文本节点（不截屏、不用 OCR），悬浮层给每个文本块画圆角框
- 点哪个块复制哪个块 → toast → 自动退回原应用；底部工具条只有「复制全部 / 关闭」
- 返回键、点空白处、30 秒超时、熄屏，四重兜底退出
- 可选 Root 保活：无障碍开关被 ROM 翻掉/服务被解绑时自动恢复（adb 授予的 WRITE_SECURE_SETTINGS 优先免 su，否则 root shell；合并追加绝不覆盖其他服务；15 分钟巡检 + 解绑后 20 秒快速恢复；强力停止场景属 V2 开机脚本范畴，明确不做）
- 可选远程日志（照 notion-app-android 的 OpenList 方案）：自配 OpenList 地址/账号，诊断日志以独立文件上传到 `{目录}/install-{设备ID}/`；只记事件与状态、绝不记复制内容，上传前统一脱敏；含未捕获崩溃捕获与即时上传；支持一键复制全部日志
- OCR 为二期，本次不实现、不留接口

## 工程

- `com.tomcat927.lightcopy`，单模块 :app，minSdk 26 / targetSdk 34
- 主页 Compose；悬浮选择层传统 View + Canvas
- 热更新：CI 每次 push main 自动发 Release 并生成 `latest.json` 清单，App 内「检查更新」自动下载 + SHA-256 校验 + 调起系统安装器；「镜像加速更新」开关控制 gh-proxy 优先（默认开）还是 GitHub 直连优先，失败自动互为兜底；发现新版本自动后台预下载（WorkManager 承载，关 app 不中断，每 6 小时静默检查），完成后发「点按安装」通知
- 本机不构建：push main → GitHub Actions 出签名 APK 并自动发 Release（tag 形如 `v0.1.0-时间戳`，保留最近 10 个）
- 签名 keystore 仅存 GitHub Secrets（KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD），严禁进仓库

## 已知限制

- 依赖应用暴露无障碍文本节点：自绘 Canvas、图片内的文字取不到（二期 OCR 解决）
- 选择模式是全屏直触层，期间原应用手势/滚动不可用（点空白即退出）
- 极少数强自绘界面节点树上无文本，同样依赖二期 OCR
