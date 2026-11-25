"""Validate measured on-device results, not simulated API responses."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent / "evidence"
probe = json.loads((root / "probe" / "probe-final.json").read_text(encoding="utf-8-sig"))
assert probe["active"] in ("1.0", "1.1")
assert all(probe[k] == "" for k in ("simCountry", "simOperator", "simName", "networkCountry", "networkOperator"))
assert probe["simState"] == 1 and probe["hasSim"] is False and probe["modems"] == 0
assert probe["subscriptions"] == "[]" and probe["cellInfo"] == "[]"
assert probe["locationEnabled"] is False and probe["lastLocation"] == "null"
assert probe["ssid"] == "<unknown ssid>" and probe["bssid"] == "02:00:00:00:00:00" and probe["wifiScans"] == 0
assert probe["locale"] == "en-US" and probe["resourceMcc"] == 0 and probe["resourceMnc"] == 0
assert all(probe[k] == "America/Los_Angeles" for k in ("timezone", "zoneId", "icuTimezone"))
native = json.loads(probe["native"])
assert native["hookStatus"] == 15 and native["simCountry"] == "" and native["timezone"] == "America/Los_Angeles"
for filename in ("browser-private_cold.json", "browser-private_final.json"):
    browser = json.loads((root / "browser" / filename).read_text(encoding="utf-8"))
    for realm in ("main", "worker", "iframe"):
        measured = browser[realm]
        assert measured["timezone"] == "America/Los_Angeles", (filename, realm)
        assert measured["language"] == "en-US" and measured["languages"] == ["en-US"]
        assert measured["winterOffset"] == 480 and measured["summerOffset"] == 420
    assert browser["geolocation"]["denied"] is True
    assert browser["webrtc"]["error"].startswith("NotSupportedError")
    assert browser["requestAcceptLanguage"] == "en-US,en;q=0.9"
log = (root / "probe" / "app-startup.log").read_text(encoding="utf-8-sig")
for package in ("com.anthropic.claude", "com.anthropic.aibo"):
    last = [line for line in log.splitlines() if f"package={package} process={package} " in line][-1]
    assert 'zone=America/Los_Angeles locale=en-US' in last and '"hookStatus":15' in last
print("PASS: measured SIM, operator, location, Wi-Fi, native/Java locale+timezone, webpage first script/iframe/worker, DST, English headers, blocked geolocation+WebRTC, both Claude app startup hooks.")
