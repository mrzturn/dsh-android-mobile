# Z Flip 7 折叠姿态模拟器演练手册

真机 Flex 模式验证前的模拟器回归步骤（Android SDK + 画像 59 "6.7" Horizontal Fold-in"）。

## 创建折叠 AVD（API 34 default 镜像；35/36.1 在 headless 下无法启动）
```
JAVA_HOME=<jdk17> avdmanager create avd -n Flip_Phone \
  -k "system-images;android-34;default;arm64-v8a" -d 59 --force
```

## 启动后启用姿态 overlay（一次性，缺少它 AndroidX Window 永远报 FLAT）
```bash
adb shell cmd overlay enable com.android.internal.emulation.pixel_fold
```
overlay 映射表为 deviceState→posture：["0:1","1:2","2:3","3:1"]，
因此 console `posture` 命令语义与 AndroidX HALF_OPENED 不对应：
- console posture 2 (HALF_OPENED) → committed state 2 → AndroidX OPENED/FLAT
- 需要 AndroidX HALF_OPENED 时应令 committed state = 1

## 进入 Flex 模式（App 可测 HALF_OPENED + 水平 hinge）
```bash
adb shell cmd device_state state 1   # 强制 committed state 1
```
hinge: `hinge-[0,1318,1080,1319]`（水平）。

## 验证
```bash
adb logcat -d -s DshFold   # (演练时可临时注入 Log) 应见 state=HALF_OPENED
adb shell uiautomator dump # FlexPad 快捷键 Esc/CTRL/Tab/↑/↓/输入框/发送⏎ 应出现于底部
```
FlexPad 交互链路：触摸板 move 序列、sendKey('Escape')、focusComposer() 均经
FlexPadBridge.evaluateJavascript 注入 WebView 页内 `DshMobileFlexpad`。

## 配对演练
与直板 AVD 相同：launchctl 启动模拟器 → adb reverse tcp:3080 →
桌面端 POST /api/pair/issue 取链接 → adb am start ACTION_SEND 分享进 app。
