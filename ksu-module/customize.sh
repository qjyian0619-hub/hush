#!/system/bin/sh
[ "$KSU" = true ] || abort "! This package requires KernelSU."
[ "$ARCH" = arm64 ] || abort "! This build requires arm64 Android."
[ "$API" -ge 28 ] || abort "! Android 9 or newer is required."
[ -f /data/adb/modules/zygisk_lsposed/module.prop ] || abort "! Install and enable LSPosed first."
ui_print "- Updating the existing backend for owner user 0 only"
apk_tmp="/data/local/tmp/regionguard-ksu-install-$$.apk"
cp "$MODPATH/backend.apk" "$apk_tmp" || abort "! Cannot stage backend APK"
chmod 0644 "$apk_tmp"
install_output=$(/system/bin/pm install --user 0 -r "$apk_tmp" 2>&1)
install_result=$?
rm -f "$apk_tmp"
[ "$install_result" -eq 0 ] || abort "! Backend install failed: $install_output"
/system/bin/pm enable --user 0 dev.local.regionguard >/dev/null 2>&1
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/bridge.sh" 0 0 0755
set_perm "$MODPATH/watch.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
zygisk_found=false
for zygisk_module in zygisksu rezygisk neozygisk; do [ -d "/data/adb/modules/$zygisk_module" ] && zygisk_found=true; done
[ "$zygisk_found" = true ] || ui_print "! No Zygisk implementation found: the Chrome/Edge first-page time zone fix stays inactive"
if [ "$KSU_HAS_HOT_INSTALL" = true ]; then
  MODULE_HOT_INSTALL_REQUEST=true
  MODULE_HOT_RUN_SCRIPT=service.sh
  ui_print "- Requested immediate activation without a reboot"
else
  ui_print "- Installer stages this module; activate using the supplied PC installer or your next manual reboot"
fi
ui_print "- Chrome/Edge first-page time zone fix (Zygisk) starts after the next reboot"
ui_print "- No launcher icons. Open Region Guard from KernelSU."
