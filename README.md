# 轻复制 lightcopy

自用 Android 点选复制工具：复刻 Universal Copy 的核心能力，单一功能、无设置项。

- 下拉通知栏 → 点「复制模式」快捷瓦片进入选择模式（再点一次瓦片=取消）
- 无障碍服务读取当前屏幕文本节点（不截屏、不用 OCR），悬浮层给每个文本块画圆角框
- 点哪个块复制哪个块 → toast → 自动退回原应用；底部工具条只有「复制全部 / 关闭」
- 返回键、点空白处、30 秒超时、熄屏，四重兜底退出
- OCR 为二期，本次不实现、不留接口

## 工程

- `com.tomcat927.lightcopy`，单模块 :app，minSdk 26 / targetSdk 34
- 主页 Compose；悬浮选择层传统 View + Canvas
- 热更新：CI 每次 push main 自动发 Release 并生成 `latest.json` 清单，App 内「检查更新」自动下载 + SHA-256 校验 + 调起系统安装器（gh-proxy 加速优先，失败回退 GitHub 直连，再回退 GitHub API）
- 本机不构建：push main → GitHub Actions 出签名 APK 并自动发 Release（tag 形如 `v0.1.0-时间戳`，保留最近 10 个）
- 签名 keystore 仅存 GitHub Secrets（KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD），严禁进仓库

## 已知限制

- 依赖应用暴露无障碍文本节点：自绘 Canvas、图片内的文字取不到（二期 OCR 解决）
- 选择模式是全屏直触层，期间原应用手势/滚动不可用（点空白即退出）
- 极少数强自绘界面节点树上无文本，同样依赖二期 OCR
