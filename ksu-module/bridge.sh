#!/system/bin/sh
STATE=/data/adb/regionguard
BB=/data/adb/ksu/bin/busybox
umask 077
mkdir -p "$STATE" || exit 1
if [ "$1" != --locked ]; then
  exec "$BB" flock -x "$STATE/control.lock" /system/bin/sh "$0" --locked "$@"
fi
shift
state_file=/data/user/0/dev.local.regionguard/files/ksu-state.json
error() { echo '{"ok":false,"error":"Backend communication failed. Check that LSPosed and hush are enabled."}'; exit 1; }
valid_zone() {
  case "$1" in
    America/Los_Angeles|America/New_York|Asia/Tokyo|Asia/Taipei|Asia/Singapore|Europe/London|UTC) return 0 ;;
    *) return 1 ;;
  esac
}
call_backend() {
  if result=$(/system/bin/content call --user 0 --uri content://dev.local.regionguard.control "$@" 2>&1); then
    case "$result" in *'ok=true'*) return 0 ;; esac
  fi
  backend_pid=$(printf '%s\n' "$result" | sed -n 's/.*RegionGuard restarting pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
  if [ -n "$backend_pid" ]; then
    # Keep the control lock until the retiring process has had time to exit after replying.
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
      case " $(pidof dev.local.regionguard) " in
        *" $backend_pid "*) sleep 0.1 ;;
        *) break ;;
      esac
    done
    echo '{"ok":false,"error":"LSPosed configuration is unavailable. The backend will reconnect on the next request."}'
    exit 1
  fi
  error
}
# Prints the backend state. Also publishes the renderer time zone that the Zygisk companion hands to
# Chrome and Edge, only while protection, browser protection and the KSU module are on this boot.
publish() {
  state=$(cat "$state_file") || error
  zone=$(printf '%s' "$state" | sed -n 's/.*"zone":"\([^"]*\)".*/\1/p' | sed 's#\\/#/#g')
  boot=$(/system/bin/getprop ro.boottime.init)
  active=true
  for key in enabled browsers managerEnabled; do
    case "$state" in *"\"$key\":true"*) ;; *) active=false ;; esac
  done
  valid_zone "$zone" || active=false
  case "$boot" in ''|*[!0-9]*) active=false ;; esac
  previous=$(cat "$STATE/renderer-zone" 2>/dev/null)
  if [ "$active" = true ]; then
    printf '%s %s\n' "$zone" "$boot" >"$STATE/renderer-zone.tmp" && mv -f "$STATE/renderer-zone.tmp" "$STATE/renderer-zone"
  else
    rm -f "$STATE/renderer-zone"
  fi
  if [ "$previous" != "$(cat "$STATE/renderer-zone" 2>/dev/null)" ]; then
    # Browser app zygotes outlive a force-stop and keep the zone they started with. End them so the
    # next renderer comes from a new one; renderers already running are not affected.
    for zygote in com.android.chrome_zygote com.microsoft.emmx_zygote; do
      for pid in $(pidof "$zygote"); do kill "$pid" 2>/dev/null; done
    done
  fi
  printf '%s\n' "$state"
}
case "${1:-status}" in
  status)
    call_backend --method status
    publish
    ;;
  save)
    valid_zone "$2" || error
    case "$3:$4" in true:true|true:false|false:true|false:false) ;; *) error ;; esac
    call_backend --method save --extra "zone:s:$2" --extra "enabled:b:$3" --extra "browsers:b:$4"
    publish
    ;;
  module-state)
    case "$2" in true|false) ;; *) error ;; esac
    boot=$(/system/bin/getprop ro.boottime.init)
    case "$boot" in ''|*[!0-9]*) error ;; esac
    call_backend --method module-state --extra "boot:s:$boot" --extra "enabled:b:$2"
    publish
    ;;
  native-ui)
    /system/bin/am start --user 0 -n dev.local.regionguard/.MainActivity >/dev/null 2>&1 || error
    echo '{"ok":true}'
    ;;
  *) error ;;
esac
