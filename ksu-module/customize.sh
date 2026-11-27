#!/system/bin/sh
[ "$KSU" = true ] || abort "! This package requires KernelSU."
[ "$ARCH" = arm64 ] || abort "! This build requires arm64 Android."
[ "$API" -ge 28 ] || abort "! Android 9 or newer is required."
[ -f /data/adb/modules/zygisk_lsposed/module.prop ] || abort "! Install and enable LSPosed first."
ui_print "- Installing the backend for owner user 0"
backend_apk="$MODPATH/backend.apk"
[ -f "$backend_apk" ] || abort "! Backend APK is missing."
apk_size=$(stat -c %s "$backend_apk") || abort "! Cannot read the backend APK size."
[ "$apk_size" -gt 0 ] || abort "! Backend APK is empty."
# Keep the APK file accessible to the installer and hand Package Manager a pipe.
install_output=$(
  { cat "$backend_apk" | /system/bin/pm install --user 0 -r -S "$apk_size"; } 2>&1
) || abort "! Backend install failed: $install_output"
/system/bin/pm enable --user 0 dev.local.regionguard >/dev/null 2>&1
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/bridge.sh" 0 0 0755
set_perm "$MODPATH/watch.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
zygisk_found=false
for zygisk_module in zygisksu rezygisk neozygisk; do [ -d "/data/adb/modules/$zygisk_module" ] && zygisk_found=true; done
[ "$zygisk_found" = true ] || ui_print "! No supported Zygisk loader detected for browser protection"
if [ "$KSU_HAS_HOT_INSTALL" = true ]; then
  MODULE_HOT_INSTALL_REQUEST=true
  MODULE_HOT_RUN_SCRIPT=service.sh
  ui_print "- Requested immediate activation without a reboot"
else
  ui_print "- Installer stages this module; activate using the supplied PC installer or your next manual reboot"
fi
ui_print "- Reboot after installing or updating the Zygisk component"
ui_print "- No launcher icons. Open hush from KernelSU."
