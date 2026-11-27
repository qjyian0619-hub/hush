"""Check the archived device measurements and the current KSU package."""
import json
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parent.parent
evidence = root / "evidence"
def read(name):
    folder = "browser" if name.startswith("browser-") else "probe" if name.startswith("ksu-probe-") else "system"
    return json.loads((evidence/folder/name).read_text(encoding="utf-8-sig"))
def on(value):
    return value is True or value == "true"

state = read("ksu-state-final.json")
assert state["version"] == "1.1" and state["enabled"] and state["managerEnabled"] and state["sharedPreferences"]
assert state["launcherEntries"] == 0 and state["zone"] == "America/Los_Angeles"
tokyo = read("ksu-probe-tokyo.json")
assert tokyo["active"] == "1.1" and tokyo["timezone"] == "Asia/Tokyo" and tokyo["webview"]["timezone"] == "Asia/Tokyo"
assert read("ksu-probe-disabled.json")["active"] == "off"
probe = read("ksu-probe-final.json")
assert probe["active"] == "1.1" and probe["timezone"] == "America/Los_Angeles"
assert probe["simCountry"] == probe["simOperator"] == probe["networkCountry"] == probe["networkOperator"] == ""
assert json.loads(probe["native"])["hookStatus"] == 15
browser = read("browser-ksu_release.json")
assert browser["rootBridgeExposed"] is False
for realm in ("main", "worker", "iframe"):
    assert browser[realm]["timezone"] == "America/Los_Angeles"
    assert browser[realm]["languages"] == ["en-US"]
    assert browser[realm]["winterOffset"] == 480 and browser[realm]["summerOffset"] == 420
assert browser["geolocation"]["denied"] and browser["webrtc"]["error"].startswith("NotSupportedError")
assert browser["requestAcceptLanguage"] == "en-US,en;q=0.9"
before = {module["id"]: module for module in read("ksu-modules-before.json")}
after = {module["id"]: module for module in read("ksu-modules-final.json")}
assert set(after)-set(before) == {"regionguard"}
for module_id, old in before.items():
    for key in ("enabled", "remove", "update"):
        assert after[module_id][key] == old[key], (module_id, key)
module = after["regionguard"]
assert on(module["enabled"]) and on(module["web"]) and on(module["action"]) and not on(module["update"])
metadata = (root / "ksu-module/module.prop").read_text(encoding="utf-8").splitlines()
version = next(line.removeprefix("version=") for line in metadata if line.startswith("version="))
with ZipFile(root / "dist" / f"quiet-meridian-ksu-{version}.zip") as package:
    assert package.testzip() is None
    assert package.read("backend.apk") == (root/"dist/regionguard.apk").read_bytes()
    assert package.read("zygisk/arm64-v8a.so") == (root/"dist/regionguard-zygisk-arm64-v8a.so").read_bytes()
    assert all(not name.startswith("/") and ".." not in Path(name).parts for name in package.namelist())
# 1.7 browser protection, measured with public STUN servers and an HTTP exit check.
for name in ("browser-v17_chrome.json", "browser-v17_edge.json", "browser-v17_edge_drift.json"):
    measured = read(name)
    # Chromium WebRTC policy: no candidates at all, even with STUN servers available.
    assert measured["webrtc"]["candidateCount"] == 0 and measured["webrtcStun"]["candidateCount"] == 0, name
    assert measured["main"]["languages"] == ["en-US", "en"] and measured["requestAcceptLanguage"] == "en-US,en;q=0.9", name
    assert measured["geolocation"]["denied"] is True, name
via = read("browser-v17_via.json")
# WebView cannot take the policy: candidates are only the VPN tunnel address and the VPN exit.
exit_ip = via["httpExit"]["ip"]
for candidate in via["webrtcStun"]["candidates"]:
    assert candidate["kind"] == "vpn-tun" or candidate["address"] == exit_ip, candidate
# 1.8 Zygisk part, after a reboot: the first script of the first page after a cold start already has the
# configured zone, at every sampled moment, in Chrome (native read) and Edge (Java read), and follows a zone change.
def first_page(name, zone):
    measured = read(name)
    marks = measured["zoneTimeline"]
    assert len(marks) == 5 and all(mark["zone"] == zone for mark in marks), (name, marks)
    assert measured["main"]["timezone"] == zone and measured["worker"]["timezone"] == zone, name
for name in [f"browser-v18r_chrome_cold{i}.json" for i in range(1, 6)] + [f"browser-v18r_edge_cold{i}.json" for i in range(1, 4)]:
    first_page(name, "America/Los_Angeles")
for name in ("browser-v18r_chrome_tokyo.json", "browser-v18r_edge_tokyo.json"):
    first_page(name, "Asia/Tokyo")
for name in ("browser-v18r_chrome_webrtc.json", "browser-v18r_edge_webrtc.json"):
    assert read(name)["webrtcStun"]["candidateCount"] == 0, name
print("PASS: 1.8 Chrome 5/5 and Edge 3/3 cold starts read the configured zone from the first script; Tokyo switch reaches the first page; WebRTC still blocked.")
print("PASS: KSU WebUI save, enable/disable propagation, zero launcher entries, final native hooks, isolated browser locale/timezone/WebRTC, package integrity; other modules unchanged.")
print("PASS: 1.7 Chrome/Edge settings re-applied at startup (incl. simulated drift), no WebRTC candidates with STUN; Via WebRTC exits only through the VPN.")
