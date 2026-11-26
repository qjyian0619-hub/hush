# 地区隐私 / Region Guard 1.9.1

这是为当前连接的 Android 16 / arm64 / KernelSU / LSPosed 2.2.1 手机制作的应用级隐私模块。默认 **English (en-US) + America/Los_Angeles**，按真实时区数据库处理夏令时。手机整机仍使用原有的中文、上海时区；电话、移动数据、热点、FlClash 和系统服务没有被修改。安装和测试均未重启手机。

配置入口为 **KernelSU → 模块 → 地区隐私 → WebUI / 执行**。安装包为 `dist/regionguard-ksu-1.9.1.zip`。1.9.1 修复 LSPosed 配置服务断开后的恢复，后台返回错误后会自动退出，下次调用重新连接。1.9 将 Java 与原生入口迁至 libxposed API 102，配置通过 LSPosed 远程偏好同步。已加载 1.8 的 Zygisk 库时，覆盖安装 1.9.1 后重新打开作用域内的应用即可。1.8 新增 Zygisk 部分，修正 Chrome / Edge 冷启动后第一个网页的时区，**首次安装该部分需重启一次生效**（见下文「首屏时区」）。1.7 把「浏览器兼容」改名为「浏览器保护」，并让 Chrome / Edge 每次启动时由钩子重设网页语言、WebRTC 与定位配置，不再只靠电脑端写入一次。1.5、1.6 只更新 WebUI；1.6 精简了界面文字，并让时区面板滑入保持流畅。1.4 移除了内置网页入口和对应的浏览组件。

WebUI 首页是状态卡和分组设置。状态卡显示已保存的语言、时区、该时区当地时间与浏览器保护开关，界面只用中文。配色为 Geist 式中性灰阶，配一种蓝色强调色和绿 / 黄 / 红状态色。布局采用 SwiftUI 式大标题与分组列表。刷新按钮使用玻璃材质：CSS 绘制模糊、色调与高光边缘；在 Chromium WebView 中，`glass.js` 用本地画布生成位移贴图，加上边缘折射和轻微色散。时区面板保留高光边缘与阴影，但不做背景模糊：在这台 120 Hz、3.5 倍密度的手机上，面板滑入时实时模糊背景每帧多约 5 ms，会掉帧。开启“减少透明度”或“高对比度”时改用实色底。时区面板标出当前所选项和 UTC 偏移，选好后在设置页保存。浅色和深色随 WebView 外观切换，旧版 WebView 可通过系统选择器选时区。界面参考 [Geist](https://vercel.com/geist)、[SwiftUI.js](https://github.com/swiftuijs/ui) 和 [Liquid Glass React](https://github.com/rdev/liquid-glass-react)，未复制其代码。

底层依赖支持 libxposed API 102 且已启用的 LSPosed（已核对 2.2.1）。ZIP 内含提供应用进程钩子和配置入口的后台 APK，可在 Android 应用管理和 LSPosed 列表里找到。KSU WebUI 只加载本地文件。

在这台已准备好的手机上，通过电脑安装并免重启激活：

```powershell
& 'C:\codex\模块\claude-region-guard\scripts\install-ksu.ps1'
```

此脚本会核对设备标识，调用 KSU 正常安装器，再仅对不涉及系统挂载的本模块即时激活。当前 KSU 版本不支持原生热安装；从 KSU 手动刷 ZIP 时，若显示等待重启，可使用上述脚本完成即时激活，或留待用户自行重启。脚本不重启手机。所有 APK 安装都限定机主用户 0，访客空间不再安装副本。

## 目录结构

| 路径 | 内容 |
| --- | --- |
| `ksu-module/` | KSU 模块：安装与生命周期脚本，WebUI 在 `webroot/` |
| `app/` | 后台 APK 源码：Java（`src/`）、原生属性钩子与 Zygisk 部分（`native/`）、清单与资源 |
| `scripts/` | 电脑端脚本：`install-ksu.ps1` 安装到手机（不重启）、`build.ps1` 构建 APK、`package_outputs.py` 打包、`setup.ps1` 设置 / 还原手机；`activate-ksu-now.sh` 由安装脚本推到手机执行 |
| `tools/` | 只读检查：`audit_vpn.py`、`verify_evidence.py`、`verify_ksu_evidence.py`；`diagnostic/` 是本地浏览器测试页 |
| `dist/` | 当前产物：KSU ZIP、后台 APK、检测 APK、源码 ZIP、还原 ZIP 和 `SHA256SUMS.txt`；旧版本在 `dist/archive/` |
| `backup/` | 这台手机的设备标识与原始设置，还原时必需；另有桌面数据库的早期备份 |
| `evidence/` | 实测记录：`probe/` 离线检测、`browser/` 浏览器测试、`system/` 模块与 VPN 状态、`screenshots/` 截图、`ui-dumps/` 界面结构 |
| `deps/` | libxposed API、service 与 Binder interface 构件，另有 KernelSU JS SDK；`deps/unused/` 存放旧下载 |
| `signing/` | 本地签名密钥 |

## 日常使用

1. 在 **KSU → 模块 → 地区隐私** 打开 WebUI（或点执行），选择加州、纽约、东京、伦敦或 UTC，点击保存。英语固定为 en-US。
2. Claude 原生应用可以正常从原图标打开。修改时区后需要强行停止、重新打开这些应用。
3. 用常用浏览器访问网页时，由 VPN 接管网页流量。要保护 Chrome、Edge、Via 或夸克打开的网页，在 LSPosed 勾选浏览器，并在 WebUI 开启并保存「浏览器保护」。

KSU 模块开关由一个轻量本地状态监视进程同步到 LSPosed 远程偏好，通常在 2 秒内生效；作用域内的应用仍需重新打开才能改变已加载钩子的状态。从 1.8 或更早版本首次迁移到远程配置时，后台会导入已发布到 `ksu-state.json` 的时区和保护开关，KSU 状态由监视进程重新同步。只有冷启动尚未收到 Binder 时最多等待 5 秒。连接断开或远程提交失败时，控制接口返回错误，后台退出后由下次调用冷启动。原生设置页会在退出前提示，并在重新打开时恢复未保存的选择。配置绑定本次启动标记，读取失败或本次启动未同步时，应用保持未保护状态。标记由后台在应用进程内读取（优先 `ro.runtime.firstboot`，在这台手机上实际为内核 `boot_id`），与被注入应用的读法一致；1.1–1.7 使用的 `ro.boottime.init` 受 SELinux 限制，普通应用读不到，导致重启后所有应用的保护静默失效（1.8 修复，`bridge.sh status` 的 `bootSource` 显示所用来源）。被注入应用因此跳过保护时，LSPosed 日志会记录 `inactive package=…`。LSPosed 的地区隐私也需要保持启用。

1.1 的升级验证覆盖 KSU WebUI 实际加载、通过页面保存东京时区、离线应用/网页同步切换、KSU 停用后新进程不加载保护、重新启用后恢复、桌面入口数量为 0。已恢复英语和加州配置。相关证据为 `evidence/screenshots/ksu-webui.png` 以及 `evidence/probe/` 下的 `ksu-probe-tokyo.json`、`ksu-probe-disabled.json` 与 `ksu-probe-final.json`。

## 实现范围

- SIM / 网络国家、MCC/MNC、运营商名、SIM 状态、卡数量、订阅列表、号码和常见设备电话标识接口，向作用域内应用提供空值或无卡状态。
- 隐藏旧订阅对象中的国家、运营商和号码；返回空的基站列表。
- 拦截定位读取和位置更新请求；按应用设置位置、Wi-Fi 扫描、电话状态、电话号码和蓝牙扫描 AppOps 为 ignore。
- 屏蔽 Wi-Fi SSID、BSSID、MAC 和扫描列表。
- 应用内 Java Locale、LocaleList、资源 Configuration 的语言、MCC/MNC、Java / ICU 时区、系统属性读取。
- 使用通过 `META-INF/xposed/native_init.list` 注册的 LSPosed 原生钩子拦截 libc 的 `__system_property_get`、`__system_property_read`、`__system_property_read_callback`，覆盖作用域内的 SIM、地区、语言和时区属性。
- 浏览器保护：浏览器进程内的语言、时区与定位钩子；Chrome / Edge 每次启动时，在浏览器读取配置前重设 `intl.accept_languages` / `intl.selected_languages` 为英语、`webrtc.ip_handling_policy` 为 `disable_non_proxied_udp`（WebRTC 不收集任何本地或 STUN 候选）、网页定位与传感器为拒绝。写入在浏览器自身进程完成，保持原有属主和 SELinux 标签。关闭开关只停止重设，已写入的值保留，原值由 `setup.ps1 -Restore` 还原。

当前作用域：

| 包名 | 状态 |
| --- | --- |
| com.anthropic.claude | 已启用；真实进程启动、原生钩子和地区配置验证通过 |
| com.anthropic.aibo | 已启用；真实进程启动、原生钩子和地区配置验证通过 |
| com.android.chrome | 浏览器保护已启用；1.7 实测 WebRTC 带 STUN 也无候选；首屏时区由 Zygisk 部分修正 |
| com.microsoft.emmx | 浏览器保护已启用；1.7 实测 WebRTC 带 STUN 也无候选。旧语言或 WebRTC 设置被改回后，下次启动自动重设（已模拟验证）。**旧语言的来源是 Edge 与微软账户的设置同步**（`Sync Data/LevelDB` 含 zh-CN、pt-BR）：同步会在会话中途把账户里的语言列表写回，`Accept-Language` 头随之带上中文。需在 Edge 中关闭「设置」类同步，或只用 Chrome。 |
| mark.via | 浏览器保护已启用；WebView 无法设置 WebRTC 策略，候选只有 VPN 隧道地址与 VPN 出口（与网页出口一致）；**冷启动网页仍出现上海时区** |
| com.quark.browser | 已在作用域；**当前被 FlClash 排除在 VPN 外，全部流量走真实 IP，不应用来访问 Claude**；未测试 |
| dev.local.regionguard.probe | 离线检测应用，未声明 INTERNET 权限 |

普通浏览器按应用包名纳入作用域，设置会影响它访问的各个网站。具体覆盖取决于浏览器的渲染进程：渲染进程是隔离进程，LSPosed 不注入，它在启动时自行读取系统时区，稍后才收到浏览器进程推送的设定时区，因此冷启动后的第一个网页可能读到上海时区，刷新或再打开即恢复。Worker 与后续页面实测均为设定时区。

**首屏时区（1.8，Zygisk 部分）**：Chrome 和 Edge 的渲染进程都由浏览器自己的应用 zygote（`com.android.chrome_zygote`、`com.microsoft.emmx_zygote`）分叉。模块 ZIP 内的 `zygisk/arm64-v8a.so` 由 Zygisk Next 加载，只在这两个应用 zygote 里生效：把存放 `persist.sys.timezone` 的那一页属性内存换成写入设定时区的私有副本（Chrome 的渲染进程在原生层读取该属性，Edge 经 Java 读取，两者都读这一页），并设置 Java 默认时区与 `TZ`；之后分叉的每个渲染进程都继承这些设置，第一个脚本就读到设定时区。系统其他进程看到的仍是真实时区。其他进程加载后立即卸载，不做任何修改。时区由 Zygisk 的 root 伴随进程读取 `/data/adb/regionguard/renderer-zone`；该文件由 `bridge.sh` 在保存、刷新和 KSU 开关变化时写入，仅当应用保护、浏览器保护和 KSU 模块均开启时存在，并绑定本次启动标记。Zygisk Next 只在开机时加载模块，因此安装或更新 Zygisk 部分后需重启一次。应用 zygote 不随浏览器强行停止而退出，所以改时区或开关时 `bridge.sh` 会结束这两个应用 zygote，下一个渲染进程由新的应用 zygote 产生；正在运行的页面不受影响，改完照常强行停止浏览器即可。重启后实测：Chrome 冷启动 5/5、Edge 3/3，第一个网页从首个脚本起就是洛杉矶时区；切换东京后两者首屏均为东京（`browser-v18r_*.json`，`verify_ksu_evidence.py` 复核）。Via 等 WebView 浏览器的渲染进程归系统 WebView 所有、无法区分宿主应用，不在修正范围内。其他用户或分身空间需要单独启用作用域，当前只验证用户 0。

## 实测与边界

离线检测覆盖：SIM 国家 / 运营商为空、无卡状态、空订阅与基站、定位不可用、SSID/BSSID 脱敏、Java/ICU/原生属性语言时区、网页和 Worker 的语言时区。洛杉矶冬季 `getTimezoneOffset()` 为 480，夏季 420。选项切换东京后，应用与网页同时为 Asia/Tokyo；测试完成已恢复加州。

证据文件存于 `evidence/`。`probe/probe-final.json` 记录离线接口检测，`browser/` 下的 `browser-chrome_cold2.json`、`browser-edge_settings_after.json` 和 `browser-via2.json` 记录普通浏览器测试，`browser-v17_*.json` 记录 1.7 带 STUN 的 WebRTC 与出口测试（测试页加 `network=1` 时才联系公共 STUN 与 IP 回显服务），`system/vpn-audit.json` 记录当时的 VPN 接管检查。运行 `python tools\verify_evidence.py` 和 `python tools\verify_ksu_evidence.py` 可复核这些记录与当前安装包。

本机 FlClash 的 **tun0 已启用**，两个 Claude 应用、Chrome、Edge、Via 均在 VPN 的 UID 范围内；IPv6 存在 `::/0 unreachable` 路由。夸克不在范围内。2026-11-26 起 FlClash 已关闭「允许应用绕过」（`bypassable=false`），系统始终开启 VPN / 断连阻断未设置。FlClash 的 WebRTC 策略组跟随主节点选择器。1.7 用公共 STUN 服务器实测：Via 的 WebRTC 公网地址与网页 HTTP 出口一致，本地候选只有 tun0 地址。此检查仍不能单独证明所有域名、DNS 都使用相同出口。

截图的 SIM API 能力属实，但仅凭调用接口不能证明某次封号原因。官方说明会结合 IP 和其他信号判断粗略地区，并未公开全部判定规则：[Anthropic 隐私说明](https://privacy.claude.com/en/articles/11186740-does-claude-use-my-location)、[Android TelephonyManager 文档](https://developer.android.com/reference/android/telephony/TelephonyManager)。

本模块不能保证无封号或绝对匿名。未覆盖服务端已有记录、账号 / 支付 / Google Play 国家、应用历史缓存、对话或文件中的地名、照片 EXIF / 画面中的位置线索、全部硬件与字体指纹，或恶意绕过接口的代码。未伪造整机硬件型号、未隐藏 root，也未改变 Play Integrity。应用更新后应复测。

## 停用和还原

1. 在 KSU 中关闭 **地区隐私**，等待状态同步后重新打开受影响的应用。也可在 LSPosed 中关闭它。正式卸载脚本会停用后台 APK，并保留其私有数据。关闭「浏览器保护」或整个模块后，Chrome 和 Edge 不再被重设，但已写入的配置保留，可通过下一步还原。
2. 如需恢复额外的应用权限、应用语言、Chrome / Edge 原配置，在连接同一手机且 VPN 已开启时运行：

```powershell
& 'C:\codex\模块\claude-region-guard\scripts\setup.ps1' -Restore
```

此命令停止 Chrome / Edge 来安全恢复文件中的少数配置键；不清除登录、浏览记录或其他应用数据，不重启手机，不启动 Claude。它核对 `backup/device-id.txt` 确认连接的是同一手机，恢复 `backup/settings-backup.json` 和 `backup/browser-backup.json` 保存的原值。请保留 `backup/` 文件夹。还原后可卸载 `dev.local.regionguard` 和检测包 `dev.local.regionguard.probe`。`dist/regionguard-recovery.zip` 包含还原脚本、工具 APK 和这台手机的备份；源码 ZIP 不含设备资料或签名私钥。

## 源码与重建

`app/src/` 为 Java 代码，`app/native/` 为原生属性钩子。`scripts/build.ps1` 使用本机 Android SDK 36、Build Tools 36.0.0、NDK 29.0.14206865、JDK 17，按 javac → D8 → aapt → zipalign → apksigner 构建。原生库支持 16 KiB 页大小。中间文件放在项目内的 `build/`，构建结束后自动删除。没有下载并执行第三方“防封模块”。

```powershell
& 'C:\codex\模块\claude-region-guard\scripts\build.ps1'
```

构建需要现有的 `signing/local.jks`，以便在手机上覆盖安装。源码分发 ZIP 不包含此私钥。设备辅助脚本为 `scripts/setup.ps1`，只在人工运行时执行，不设置开机服务。`tools/audit_vpn.py` 只读审计；`tools/diagnostic/diagnostic_server.py` 只绑定电脑回环地址，配合临时 ADB reverse 做本地浏览器测试。

`ksu-module/` 包含 KSU 的 WebUI、入口、安装及生命周期脚本。`service.sh` 启动本模块自身的状态同步进程，不配置网络或系统挂载。修改 WebUI 或重建 APK 后运行 `python scripts\package_outputs.py`，即可重新打包 KSU ZIP、源码 ZIP 和还原 ZIP。Java 入口、原生入口、API 版本和推荐作用域均从 APK 内的 `META-INF/xposed/` 读取。

构建使用 Maven Central 发布的 libxposed API、service、Binder interface 102.0.0，以及 KernelSU JS SDK 3.3.0。API 由框架在运行时提供，service 与 Binder interface 随后台 APK 打包。依赖下载地址、SHA256 和许可证见 `THIRD_PARTY_NOTICES.txt`，后台 APK 内也附有许可说明。接口文档见 [LSPosed 现代模块 API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)、[LSPosed 原生钩子](https://github.com/LSPosed/LSPosed/wiki/Native-Hook)、[libxposed service](https://github.com/libxposed/service) 和 [KernelSU WebUI](https://kernelsu.org/guide/module-webui.html)。
