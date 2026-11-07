# DSH 随行 (dsh-android-mobile)

Android 客户端，把手机变成 [DeepSeek Harness (DSH)](https://github.com/zhu1090093659/dsh-web) 桌面端的全权受控远程设备 —— 基于
[`@linxin666/dsh-remote-web-ui`](https://github.com/zhu1090093659/dsh-web/tree/main/packages/dsh-remote-web-ui) 插件的配对协议，
对 **三星 Galaxy Z Flip 7** 做了深度适配。

## 安装

构建产物（本仓库已构建好）：

- **推荐**：`app/build/outputs/apk/release/app-release.apk` —— 签名正式版（13.5MB），包名 `com.dsh.mobile`，
  桌面快捷方式指向正式包名。
- 调试版：`app/build/outputs/apk/debug/app-debug.apk`（20.2MB），包名 `com.dsh.mobile.debug`，
  可与正式版共存，便于对比测试。

安装：将 APK 传到手机（USB / 网盘 / 局域网均可），点击安装并允许"未知来源"。
构建方式：`./gradlew :app:assembleRelease`（签名配置见 `keystore/dsh-mobile.properties`，不入库，缺失时自动退回 debug 签名）。

## 使用

1. 桌面端打开 DSH → 侧边栏底部手机图标，进入"远程访问"面板；
2. 手机端点"扫码配对"，对准桌面端二维码；或在手机浏览器/聊天应用中直接点开配对链接（本 app 会自动接管）；
3. 配对成功后即为官方 Web GUI 完整界面，Z Flip 7 上可配合 Flex Mode 触控板使用。

## 功能

- **扫码配对**：对准桌面端“远程访问”面板（侧边栏底部手机图标）的二维码即可完成配对；
  也支持粘贴配对链接、32 位配对 Key + 服务器地址、以及从其他应用分享链接进来。
- **会话 = 官方 Web GUI**：配对成功后加载的是插件直接下发的官方 Web UI
  （`/pair-accept → /pair-app` 免浏览器凭据链路 + `dsh_pair` 设备 Cookie），
  与桌面端界面零偏差；所有 `/api` 调用自动走插件网关的 `/remote` 通道。
- **连接管理**：保存的连接实时显示 `/api/pair/status` 状态（等待配对 / 已连接 / 需开启局域网访问）。
- **Z Flip 7 深度优化**：
  - Flex Mode（半折叠）：下半屏自动变成 **FlexPad 触控板 + 快捷键条**（虚拟指针、双指滚动、
    Esc/Ctrl/Tab/方向键、输入框聚焦、发送），上半屏完整显示 Web GUI；
  - 折叠展开（cover ↔ main）时 Activity 重建，WebView 会话被保留，不丢页面状态；
  - 外屏（cover screen）小窗自适应布局；全面屏 edge-to-edge。

## 配对协议（与插件互通）

| 步骤 | 请求 | 说明 |
| --- | --- | --- |
| 1 | `GET {base}/pair-accept?pair={token}` | 303 → `/pair-app?grant=…`，Set-Cookie `dsh_pair=<deviceId>` |
| 2 | `GET {base}/pair-app?grant=…` | 官方 shell + `sessionStorage.dsh-remote-device` |
| 3 | 会话期 | 同源 `/api/*` 由页内改写为 `/remote/api/*`，凭 Cookie 网关放行 |

桌面端控制面（`/api/pair/issue|stop|revoke|rename|merge|lan-bind`）仅限本机回环 ——
手机端只使用公开的 `status / accept / heartbeat` 表面。

## 构建

```bash
# 需要 JDK 17 与 Android SDK (API 36)
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 桌面端：`dsh web` → 侧边栏手机图标 → 打开“局域网访问”或“自动公网隧道”→ 生成二维码。
2. 手机端：安装 APK → 扫码 → 自动配对并进入官方界面。
3. 断开后可从首页直接重连（设备 Cookie 仍然有效，直到桌面端“停止”或撤销该设备）。

## 安全

- 设备 Cookie 是**全权凭据**：仅保存在本机 WebView（`HttpOnly`，不跨应用），
  “清除本机凭据”会同时清掉 Cookie 与连接记录；云端备份已排除凭据存储。
- WebView 加固：禁用文件/内容访问、禁多窗口、禁地理定位、强制 Safe Browsing、
  禁混合内容；非会话源链接一律外部打开。

## License

Apache-2.0
