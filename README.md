# hush

hush 是一个 KernelSU + LSPosed 模块。它让**你勾选的应用**以为自己在另一个时区，界面语言也随之切换，同时读不到 SIM、运营商、定位和 Wi-Fi 信息。手机本身的语言、时区、通话和网络都不受影响。

当前版本 1.1.1，[下载 hush-ksu-1.1.1.zip][install-package]。

> hush 只改变应用在本机读到的内容，不改变你的出口 IP，也不能保证账号安全。

## 效果

| 在勾选的应用里 | 读到的值 |
| --- | --- |
| 时区 | 所选时区，按时区数据库处理夏令时（Java、ICU、`TZ`、系统属性） |
| 语言 | 跟随时区的语言（`Locale`、`LocaleList`、资源配置、系统属性） |
| SIM / 运营商 | 无卡；国家、MCC/MNC、运营商名、号码、订阅列表、基站信息均为空 |
| 定位 | 定位关闭，无最近位置，请求位置会被拒绝 |
| Wi-Fi | SSID 为 `<unknown ssid>`，BSSID 和 MAC 为 `02:00:00:00:00:00`，扫描结果为空 |

开启**浏览器保护**后，Chrome 和 Edge 中的网页还会：

- 使用所选语言（`navigator.language`、`Accept-Language`）和所选时区，冷启动打开的第一个网页也一样（需要 Zygisk）；
- 拒绝网页定位和传感器请求；
- WebRTC 只能经代理发送 UDP（`disable_non_proxied_udp`），不会绕过 VPN 暴露真实地址。

## 时区与语言

语言由时区决定，不单独设置：

| 时区 | 语言 |
| --- | --- |
| 洛杉矶 `America/Los_Angeles`（默认） | 美式英语 `en-US` |
| 纽约 `America/New_York` | 美式英语 `en-US` |
| UTC | 美式英语 `en-US` |
| 东京 `Asia/Tokyo` | 日语 `ja-JP` |
| 台北 `Asia/Taipei` | 繁体中文 `zh-TW` |
| 新加坡 `Asia/Singapore` | 新加坡英语 `en-SG` |
| 伦敦 `Europe/London` | 英式英语 `en-GB` |

## 安装

**需要：** arm64、Android 9 或更新版本、KernelSU，以及支持 libxposed API 102 的 Zygisk 版 LSPosed。想让 Chrome 和 Edge 冷启动后的第一个网页也使用所选时区，还需要已启用的 Zygisk 加载器（Zygisk Next、ReZygisk 或 NeoZygisk）。

1. 下载 [hush-ksu-1.1.1.zip][install-package] 并复制到手机，在 **KernelSU → 模块** 中安装，然后手动重启。安装器会同时以机主用户（user 0）身份装好后台应用 `dev.local.regionguard`，它没有桌面图标。
2. 在 **LSPosed → 模块** 中启用 **hush**，勾选要保护的应用。要保护网页，也勾选 Chrome 或 Edge。
3. 打开 **KernelSU → 模块 → hush → WebUI**（或点「执行」），选择时区，打开「应用保护」，按需打开「浏览器保护」，然后保存。
4. 强行停止并重新打开勾选的应用和浏览器。

已经通过 USB 调试连接的手机，也可以在电脑上运行 `scripts\install-ksu.ps1`。它调用 KernelSU 的正常安装器，然后立即启用 WebUI 和状态同步，不用重启。Zygisk 部分仍要等下次开机才会加载。这个脚本会核对 `backup/device-id.txt`，只用于事先准备好的那台手机。

## 日常使用

- **更换时区**：在 WebUI 中选好后保存，再强行停止并重新打开受影响的应用和浏览器。已在运行的应用不会自动切换。
- **临时关闭**：在 KernelSU 中关闭 hush 模块，约 2 秒内同步到后台；此后新启动的应用不再受保护，已在运行的应用要重新打开。也可以在 LSPosed 中关闭。
- **只关浏览器**：关闭「浏览器保护」后，Chrome 和 Edge 不再被改写。已经写进浏览器配置的语言、WebRTC 和定位设置会保留，要恢复原值见[停用与还原](#停用与还原)。
- **更新模块**：覆盖安装新 ZIP。如果 Zygisk 部分有改动，需要重启一次。

## 工作原理

```
KernelSU WebUI ──► bridge.sh ──► 后台应用（ContentProvider）──► LSPosed 远程偏好
                       │                                          │
                       ▼                                          ▼
       /data/adb/regionguard/renderer-zone          勾选的应用进程（GuardHook）
                       │                          Java 钩子 + libc 属性钩子
                       ▼
   Zygisk：Chrome / Edge 的应用 zygote → 网页渲染进程
```

**配置。** WebUI 只加载本地文件，通过 `bridge.sh` 调用后台应用的 ContentProvider。后台再通过 libxposed service 把配置写进 LSPosed 的远程偏好，被注入的应用从那里读取。`watch.sh` 每 2 秒检查一次 KernelSU 中的模块开关，变化时同步到后台。

**应用进程。** `GuardHook` 由 LSPosed 注入勾选的应用。它设置默认时区和语言，并拦截电话、订阅、定位、Wi-Fi、`Settings` 和 `SystemProperties` 的相关接口。原生库 `libregionguard.so` 通过 LSPosed 原生钩子接管 libc 的 `__system_property_get` / `read` / `read_callback`，使原生代码读到的时区、语言、SIM 和地区属性也一致。系统 UI、设置和 UID 小于 10000 的进程一律不注入。

**浏览器。** Chrome 和 Edge 每次启动时，hook 在浏览器读取配置之前，从浏览器自己的进程中改写 `app_chrome/Default/Preferences` 里的五个键：两组网页语言、WebRTC 策略、网页定位和传感器权限。改写由浏览器自己的进程完成，文件的属主和 SELinux 标签保持不变。

**首屏时区（Zygisk）。** 网页渲染进程是隔离进程，LSPosed 不会注入。它们由浏览器的应用 zygote（`com.android.chrome_zygote`、`com.microsoft.emmx_zygote`）分叉，启动时自己读取系统时区。ZIP 中的 `zygisk/arm64-v8a.so` 只在这两个 zygote 中工作：把存放 `persist.sys.timezone` 的那一页属性内存换成写入所选时区的私有副本，并设置 Java 默认时区和 `TZ`。之后分叉出的每个渲染进程都会继承这些设置，其他进程看到的仍是真实时区。所选时区由 `bridge.sh` 写入 `renderer-zone` 文件，只有应用保护、浏览器保护和 KernelSU 模块都开启时才存在。保存新时区时，`bridge.sh` 会结束这两个 zygote，让下一个渲染进程使用新设置。

## 检查是否生效

- **LSPosed 日志**：每个生效的进程会记录 `active package=… zone=… locale=…`，未生效的记录 `inactive` 及原因。
- **检测应用** `dist/regionguard-probe.apk`（`dev.local.regionguard.probe`）：离线读取上面提到的各个接口，不申请网络权限。在 LSPosed 中勾选它，打开即可看到应用实际读到的值。
- **网页**：`tools/diagnostic/` 是一个本地测试页，用 `diagnostic_server.py` 在电脑的回环地址上运行，配合 `adb reverse` 在手机浏览器中打开，可以看到网页读到的语言、时区和 WebRTC 候选地址。

## 停用与还原

1. 在 KernelSU 中关闭或卸载 hush，再重新打开受影响的应用。卸载时会停用后台应用并保留它的数据，方便以后重装。
2. 如果之前运行过 `scripts\setup.ps1`，或者用过浏览器保护，可以连上同一台手机后运行：

   ```powershell
   .\scripts\setup.ps1 -Restore
   ```

   它会恢复备份中各应用原来的 AppOps 权限、应用语言，以及 Chrome / Edge 配置里那五个键的原值。期间会强行停止 Chrome 和 Edge，但不会清除登录状态、浏览记录或其他数据，也不会重启手机。备份保存在 `backup/` 中，请勿删除。
3. 之后可以卸载 `dev.local.regionguard` 和 `dev.local.regionguard.probe`。

`dist/regionguard-recovery.zip` 打包了还原脚本、工具 APK 和这台手机的备份，可以单独保存。

[install-package]: https://github.com/qjyian0619-hub/hush/releases/download/v1.1.1/hush-ksu-1.1.1.zip
