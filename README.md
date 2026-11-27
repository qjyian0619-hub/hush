# hush

## 做了什么

- **应用** 在 LSPosed 勾选的应用中使用所选时区和对应语言，限制读取 SIM、运营商和定位信息，并对 Wi-Fi 标识脱敏。
- **浏览器** 开启浏览器保护后，Chrome 和 Edge 的网页也会使用上述语言和时区。模块会拦截网页定位，并限制 WebRTC 绕过代理发送 UDP。配合 Zygisk，冷启动的第一个网页也会使用所选时区。

洛杉矶、纽约和 UTC 使用美式英语，东京使用日语，台北使用繁体中文，新加坡使用新加坡英语，伦敦使用英式英语。

## 原理

KernelSU 提供 WebUI，配置通过 LSPosed 的远程偏好同步。LSPosed 在选中的应用进程里拦截系统接口，并在 Chrome 和 Edge 启动时应用浏览器设置。Zygisk 在网页渲染进程启动前应用时区。

## 如何安装

需要 arm64 Android 9 或更新版本、KernelSU，以及支持 libxposed API 102 的 Zygisk 版 LSPosed。Chrome 和 Edge 的首屏时区需要已启用的 Zygisk 加载器，例如 Zygisk Next。

1. 把 `dist/hush-ksu-1.1.zip` 复制到手机，在 **KernelSU → 模块** 中安装，然后手动重启。
2. 在 **LSPosed → 模块** 中启用 **hush**，勾选需要保护的应用和浏览器。
3. 打开 **KernelSU → 模块 → hush → WebUI**，选择时区，语言会自动匹配。开启应用保护，按需开启浏览器保护。保存后，强行停止并重新打开勾选的应用和浏览器。
