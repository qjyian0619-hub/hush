import json
import pathlib
import re
import subprocess


def adb(*args):
    return subprocess.check_output(["adb", *args], encoding="utf-8", errors="replace")


def audit():
    output = adb("shell", "dumpsys", "connectivity")
    records = re.split(r"(?=  NetworkAgentInfo\{)", output)
    active = [r.split("\n\n")[0] for r in records if "NetworkAgentInfo{" in r and "VPN CONNECTED" in r]
    if not active:
        return {"activeVpn": False}
    record = active[0]
    iface = re.search(r"InterfaceName:\s*(\S+)", record)
    ranges_match = re.search(r"Uids:\s*<\{([^}]+)\}>", record)
    ranges = []
    if ranges_match:
        for item in ranges_match[1].split(","):
            bounds = item.strip().split("-")
            ranges.append((int(bounds[0]), int(bounds[-1])))
    packages = {}
    for package in ("com.anthropic.claude", "com.anthropic.aibo", "dev.local.regionguard", "com.android.chrome", "com.microsoft.emmx", "mark.via", "com.quark.browser"):
        package_output = adb("shell", "cmd", "package", "list", "packages", "-U", package)
        match = re.search(r"package:" + re.escape(package) + r"\s+uid:(\d+)", package_output)
        if match:
            uid = int(match[1])
            packages[package] = {"uid": uid, "includedInVpn": any(lo <= uid <= hi for lo, hi in ranges)}
    return {
        "activeVpn": True,
        "provider": "FlClash" if "com.follow.clash" in record else "other",
        "interface": iface[1] if iface else None,
        "tunUp": bool(iface and "UP" in adb("shell", "ip", "link", "show", iface[1])),
        "ipv6BlockedRoute": "::/0 unreachable" in record,
        "appsCanRequestBypass": "bypassable=true" in record,
        "alwaysOnVpn": adb("shell", "settings", "get", "secure", "always_on_vpn_app").strip(),
        "lockdown": adb("shell", "settings", "get", "secure", "always_on_vpn_lockdown").strip(),
        "packages": packages,
    }


if __name__ == "__main__":
    report = audit()
    text = json.dumps(report, ensure_ascii=False, indent=2)
    output = pathlib.Path(__file__).resolve().parent.parent / "evidence" / "system" / "vpn-audit.json"
    output.write_text(text, encoding="utf-8")
    print(text)
