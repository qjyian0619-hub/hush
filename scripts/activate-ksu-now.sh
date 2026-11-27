#!/system/bin/sh
# This module has only user-space scripts/UI. Promote exactly its staged directory.
STAGE=/data/adb/modules_update/regionguard
LIVE=/data/adb/modules/regionguard
STATE=/data/adb/regionguard
fail() { echo "hush activation failed: $1" >&2; exit 1; }
mkdir -p "$STATE" || fail "cannot create module state directory"
chmod 0700 "$STATE"
if [ -d "$STAGE" ]; then
  [ ! -L "$STAGE" ] && [ "$(realpath "$STAGE")" = "$STAGE" ] || fail "unexpected staged path"
  grep -q '^id=regionguard$' "$STAGE/module.prop" || fail "unexpected staged module id"
  for boot_only in system system.prop post-fs-data.sh sepolicy.rule initrc; do
    [ ! -e "$STAGE/$boot_only" ] || fail "module requires boot-time work"
  done
  if [ -e "$LIVE" ]; then
    [ -d "$LIVE" ] && [ ! -L "$LIVE" ] && [ "$(realpath "$LIVE")" = "$LIVE" ] || fail "unexpected live path"
    grep -q '^id=regionguard$' "$LIVE/module.prop" || fail "unexpected live module id"
    previous="$STATE/previous-module-$(date +%s)-$$"
    [ ! -e "$previous" ] || fail "backup already exists"
    mv "$LIVE" "$previous" || fail "cannot retain previous module directory"
  fi
  mv "$STAGE" "$LIVE" || fail "cannot activate staged module"
  rm -f "$LIVE/update"
fi
[ -d "$LIVE" ] && grep -q '^id=regionguard$' "$LIVE/module.prop" || fail "module not found"
sh "$LIVE/service.sh" || fail "cannot start state synchronization"
sh "$LIVE/bridge.sh" module-state true || fail "cannot initialize backend"
echo "hush UI and state synchronization activated; no phone reboot performed."
echo "The Zygisk part (Chrome/Edge first-page time zone) loads with Zygisk Next at the next reboot."
