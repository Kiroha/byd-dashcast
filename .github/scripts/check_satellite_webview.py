#!/usr/bin/env python3
"""Execute a release's exact bundled video page in a stock API 29 emulator WebView."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import time
import zipfile


def run(argv, check=True, timeout=90, **kwargs):
    return subprocess.run([str(item) for item in argv], check=check, text=True, timeout=timeout, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--serial", required=True, help="Dedicated stock API 29 emulator only")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--expect-broken", action="store_true", help="Confirm the old syntax/layout regression")
    args = parser.parse_args()
    if not args.sdk or not args.serial.startswith("emulator-"):
        parser.error("An SDK and explicit emulator serial are required")
    adb = [args.sdk / "platform-tools/adb", "-s", args.serial]
    for prop, expected in (("ro.build.version.sdk", "29"), ("ro.kernel.qemu", "1")):
        if run(adb + ["shell", "getprop", prop], capture_output=True).stdout.strip() != expected:
            parser.error("Only a stock API 29 emulator is supported; physical devices are refused")
    package = "com.byd.dashcast.webviewprobe"
    if run(adb + ["shell", "pm", "list", "packages", package], capture_output=True).stdout.strip():
        parser.error("A probe package is already installed; refusing to replace it")
    jdk = Path(os.environ["JAVA_HOME"]) / "bin"
    build = args.sdk / "build-tools" / args.build_tools
    android = args.sdk / "platforms/android-36/android.jar"
    source = Path(__file__).parent / "android/SatelliteWebViewProbeActivity.java"
    with tempfile.TemporaryDirectory(prefix="satellite-webview-") as temporary:
        folder = Path(temporary)
        assets = folder / "assets"
        assets.mkdir()
        with zipfile.ZipFile(args.apk) as archive:
            (assets / "satellite_receiver.html").write_bytes(archive.read("assets/satellite_receiver.html"))
        manifest = folder / "AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="' + package + '"><uses-permission android:name="android.permission.INTERNET"/>'
            '<application android:theme="@android:style/Theme.Material.NoActionBar">'
            '<activity android:name=".SatelliteWebViewProbeActivity" android:exported="true"/>'
            '</application></manifest>')
        run([jdk / "javac", "-source", "8", "-target", "8", "-cp", android, "-d", folder, source],
            capture_output=True)
        dex = folder / "dex"
        dex.mkdir()
        run([build / "d8", "--min-api", "28", "--lib", android, "--output", dex,
             *sorted(folder.rglob("*.class"))], capture_output=True)
        apk = folder / "probe.apk"
        run([build / "aapt2", "link", "-I", android, "--manifest", manifest, "-A", assets,
             "--min-sdk-version", "28", "--target-sdk-version", "29", "-o", apk], capture_output=True)
        with zipfile.ZipFile(apk, "a") as archive:
            archive.write(dex / "classes.dex", "classes.dex")
        key = folder / "probe.jks"
        run([jdk / "keytool", "-genkeypair", "-keystore", key, "-storepass", "test-fixture-only",
             "-keypass", "test-fixture-only", "-alias", "probe", "-dname", "CN=Local test probe",
             "-keyalg", "RSA", "-validity", "2"], capture_output=True)
        run([build / "apksigner", "sign", "--ks", key, "--ks-pass", "pass:test-fixture-only", apk],
            capture_output=True)
        installed = False
        try:
            run(adb + ["install", apk], capture_output=True)
            installed = True
            run(adb + ["shell", "am", "start", "-W", "-n", package + "/.SatelliteWebViewProbeActivity"],
                capture_output=True)
            deadline = time.monotonic() + 45
            pid_deadline = time.monotonic() + 5
            output = ""
            pid = ""
            while time.monotonic() < deadline:
                if not pid:
                    remaining = pid_deadline - time.monotonic()
                    if remaining <= 0:
                        raise SystemExit("Fixture process startup deadline expired")
                    try:
                        result = run(adb + ["shell", "pidof", package], check=False,
                            timeout=min(1, remaining), capture_output=True)
                    except subprocess.TimeoutExpired:
                        continue
                    if result.returncode not in (0, 1):
                        raise SystemExit("Unable to locate the fixture process")
                    pid = result.stdout.strip() if result.returncode == 0 else ""
                    if not pid:
                        if time.monotonic() >= pid_deadline:
                            raise SystemExit("Fixture process startup deadline expired")
                        time.sleep(0.5)
                        continue
                    if not pid.isascii() or not pid.isdigit():
                        raise SystemExit("Invalid fixture process identifier")
                # Read only this fixture process/tag. Do not clear or collect the application's logs.
                output = run(adb + ["logcat", "-d", "-v", "brief", "--pid=" + pid, "SatelliteWebViewProbe:I", "*:S"],
                    capture_output=True).stdout
                if "BROWSER_WAITING_" in output:
                    break
                time.sleep(0.5)
            print(output.strip())
            expected = ("BROWSER_BOOTSTRAP_NOT_READY", "BROWSER_SCRIPT_SYNTAX_ERROR", "BROWSER_WAITING_OFFSCREEN") \
                if args.expect_broken else ("BROWSER_BOOTSTRAP_READY", "BROWSER_WAITING_VISIBLE")
            if not all(marker in output for marker in expected):
                raise SystemExit("Real WebView bootstrap/layout check failed")
        finally:
            if installed:
                run(adb + ["uninstall", package], capture_output=True)


if __name__ == "__main__":
    main()
