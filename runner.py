"""BUILD + RUN + VERIFY. Runs real toolchains; reports 'skipped' when one is not installed. Never fakes a pass.

Apple (ios / macos): apple/project.yml -> `xcodegen generate` -> `xcodebuild test` on an auto-picked
iPhone simulator or on this Mac. Android (optional): gradlew assembleDebug testDebugUnitTest.
"""
import json, os, platform, re, shutil, subprocess
from pathlib import Path
from tools import safe_env

APPLE = ("ios", "macos")
TAIL = 6000


def _run(cmd, cwd, timeout=1800):
    """(ok, output). ok is None when the program is not installed."""
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                           timeout=timeout, env=safe_env(), shell=False)
        return r.returncode == 0, r.stdout + r.stderr
    except FileNotFoundError:
        return None, f"tool not found: {cmd[0]}"
    except subprocess.TimeoutExpired:
        return False, f"timeout after {timeout}s: {' '.join(cmd[:3])}"


def summarize(output, limit=TAIL):
    """Puts the lines that matter (compiler errors, failed tests) first, then the tail of the log."""
    keys = re.compile(r"(error:|: error|failed|FAILED|XCTAssert|fatal|\*\* (BUILD|TEST) (FAILED|SUCCEEDED) \*\*)")
    important = list(dict.fromkeys(l.strip() for l in output.splitlines() if keys.search(l)))[:60]
    head = ("KEY LINES:\n" + "\n".join(important) + "\n\nLOG TAIL:\n") if important else ""
    return (head + output[-(limit - len(head)):])[-limit:] if len(head) < limit else head[:limit]


# ---------------------------------------------------------------- Apple helpers
def pick_simulator(devices_json):
    """From `xcrun simctl list devices available -j`: newest iOS runtime, first iPhone. Returns UDID or None."""
    try:
        devices = json.loads(devices_json).get("devices", {})
    except (ValueError, AttributeError):
        return None
    best = None
    for runtime, devs in devices.items():
        m = re.search(r"iOS-(\d+)-(\d+)", runtime)
        if not m:
            continue
        ver = (int(m.group(1)), int(m.group(2)))
        for d in devs:
            if d.get("isAvailable", True) and str(d.get("name", "")).startswith("iPhone"):
                if best is None or ver > best[0]:
                    best = (ver, d["udid"])
                break
    return best[1] if best else None


def pick_scheme(list_json, plat):
    """From `xcodebuild -list -json`: the scheme ending in -iOS / -macOS."""
    try:
        schemes = json.loads(list_json)["project"]["schemes"]
    except (ValueError, KeyError, TypeError):
        return None
    suffix = "-iOS" if plat == "ios" else "-macOS"
    match = [s for s in schemes if s.endswith(suffix)]
    return match[0] if match else None


def _json_part(out):
    i = out.find("{")
    return out[i:] if i >= 0 else ""


def apple_build_and_test(apple_dir, plat):
    if platform.system() != "Darwin":
        return "skipped", "Apple builds need macOS + Xcode (this machine is not a Mac)."
    d = Path(apple_dir)
    if (d / "project.yml").exists():
        if not shutil.which("xcodegen"):
            return "skipped", "XcodeGen is not installed. Run:  brew install xcodegen"
        ok, out = _run(["xcodegen", "generate", "--quiet"], d, timeout=300)
        if not ok:
            return ("skipped" if ok is None else "fail"), "xcodegen generate failed:\n" + summarize(out)
    projects = sorted(d.glob("*.xcodeproj"))
    if not projects:
        return "fail", "No .xcodeproj and no project.yml found in " + str(d)
    proj = projects[0].name
    if not shutil.which("xcodebuild"):
        return "skipped", "Xcode command line tools not found. Install Xcode, then run:  sudo xcode-select -s /Applications/Xcode.app"

    scheme = os.getenv("IRIS_IOS_SCHEME" if plat == "ios" else "IRIS_MACOS_SCHEME") or (os.getenv("IOS_SCHEME") if plat == "ios" else None)
    if not scheme:
        ok, out = _run(["xcodebuild", "-list", "-json", "-project", proj], d, timeout=120)
        scheme = pick_scheme(_json_part(out), plat) if ok else None
        if not scheme:
            return "fail", f"No *-{'iOS' if plat == 'ios' else 'macOS'} scheme in {proj}:\n" + summarize(out)

    extra = []
    if plat == "ios":
        dest = os.getenv("IRIS_IOS_DESTINATION") or os.getenv("IOS_DESTINATION")
        if not dest:
            ok, out = _run(["xcrun", "simctl", "list", "devices", "available", "-j"], d, timeout=120)
            udid = pick_simulator(_json_part(out)) if ok else None
            if not udid:
                return "skipped", "No iPhone simulator found. Open Xcode > Settings > Components and install an iOS simulator."
            dest = f"platform=iOS Simulator,id={udid}"
        extra = ["CODE_SIGNING_ALLOWED=NO"]          # simulator builds need no signing
    else:
        dest = "platform=macOS"

    cmd = ["xcodebuild", "test", "-project", proj, "-scheme", scheme, "-destination", dest,
           "-derivedDataPath", ".build/DerivedData", "-skipPackagePluginValidation", *extra]
    ok, out = _run(cmd, d)
    if ok is None:
        return "skipped", out
    if ok and "** TEST SUCCEEDED **" not in out:
        return "fail", "xcodebuild exited 0 but did not report TEST SUCCEEDED (no tests ran?):\n" + summarize(out)
    return ("pass" if ok else "fail"), summarize(out)


# ---------------------------------------------------------------- Android (optional)
def android_build_and_test(project_dir):
    p = Path(project_dir)
    win = platform.system() == "Windows"
    gradle = p / ("gradlew.bat" if win else "gradlew")
    if gradle.exists():
        if not win:
            gradle.chmod(0o755)
        cmd = [str(gradle), "assembleDebug", "testDebugUnitTest", "--console=plain"]
    elif shutil.which("gradle"):
        cmd = ["gradle", "assembleDebug", "testDebugUnitTest", "--console=plain"]
    else:
        return "skipped", "No gradle/gradlew found (install Android Studio + JDK 17)."
    ok, out = _run(cmd, p)
    return ("skipped" if ok is None else "pass" if ok else "fail"), summarize(out)


def build_and_test(project_dir, plat):
    """Returns (status, log) with status in {'pass','fail','skipped'}."""
    if plat in APPLE:
        return apple_build_and_test(project_dir, plat)
    if plat == "android":
        return android_build_and_test(project_dir)
    return "skipped", f"unknown platform {plat}"
