#!/system/bin/sh
MODDIR=${0%/*}
# Disable the backend and retain its data for a future reinstall.
sh "$MODDIR/bridge.sh" module-state false >/dev/null 2>&1
/system/bin/pm disable-user --user 0 dev.local.regionguard >/dev/null 2>&1
if [ -f /data/adb/regionguard/watch.pid ]; then
  pid=$(cat /data/adb/regionguard/watch.pid)
  case "$pid" in ''|*[!0-9]*) ;; *)
    if [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' <"/proc/$pid/cmdline" | grep -q '/regionguard/watch.sh'; then kill "$pid"; fi
    ;;
  esac
fi
