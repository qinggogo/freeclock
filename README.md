# FreeClock · 翻页时钟（Android）

一款低电量、可常驻屏保的安卓翻页时钟，参考「极简时钟」样式实现。

## 功能

- 三种主题：翻页 / 大号数字 / 极简，可随时切换
- 6 种数字字体可选（纤窄、黑体、衬线、等宽等），字号与间距随字体自适应
- 翻页拟物动画 + 可开关的翻页音效（含试听）
- 屏保模式：三档亮度、像素微移防烧屏、可设等待时间
- 省电模式、屏幕常亮、电量显示、全屏隐藏按钮
- 闹钟：精确响铃、开机自恢复、稍后提醒、可点通知停止
- 计时工具：番茄钟 / 正计时秒表（含计次）/ 倒计时
  - 倒计时时长可用时/分/秒滚轮精确自选，到点响铃+振动，通知可停止
- 手势：左右滑切换模式、上滑开设置、双指上下调亮度、单击/双击控制计时

## 技术

- Kotlin + 原生 Android View（Canvas 绘制），无第三方依赖
- 翻页动画用 `Camera.rotateX()` + `Matrix` 实现上下半页翻转
- 闹钟用 `AlarmManager` 精确调度，`BroadcastReceiver` 直接播放铃声与振动
- minSdk 23 / targetSdk 35，Gradle 8.9 + AGP 8.7.3 + Kotlin 1.9.24

## 构建

用 Android Studio 打开本目录，或用已安装的 Gradle：

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

> 注意：首次构建需 Android SDK 35 与 build-tools 35.0.1，并配置 `local.properties` 中的 `sdk.dir`（或在环境变量中设置 `ANDROID_HOME`）。

## 许可证

MIT License，详见 [LICENSE](LICENSE)。
