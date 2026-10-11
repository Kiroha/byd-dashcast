#!/usr/bin/env python3
"""Probe the real minified satellite server on an explicitly selected API 29 emulator."""
import argparse
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import uuid


def run(argv, **kwargs):
    return subprocess.run(argv, check=True, text=True, timeout=90, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--serial", required=True, help="Explicit emulator serial, e.g. emulator-5554")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or
                        os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--build-tools", default="36.0.0")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--load-only", action="store_true")
    modes.add_argument("--tls", action="store_true", help="Full real AndroidKeyStore pinned WSS regression")
    args = parser.parse_args()
    if not args.sdk or not args.serial.startswith("emulator-"):
        parser.error("An Android SDK and an explicit emulator serial are required")
    apk = args.apk.resolve(strict=True)
    mapping = args.mapping.resolve(strict=True).read_text()
    match = re.search(r"^com\.byd\.dashcast\.satellite\.SatelliteWebSocketServer -> ([^:]+):$",
                      mapping, re.MULTILINE)
    if not match:
        parser.error("Mapping does not contain the satellite server")
    server_class = match.group(1)
    def mapped_tls_member(signature):
        owner = None
        original = None
        for line in mapping.splitlines():
            header = re.match(r"^([^ ]+) -> ([^:]+):$", line)
            if header:
                original, owner = header.groups()
            if "com.byd.dashcast.satellite.SatelliteTls." + signature in line:
                return owner, line.rsplit(" -> ", 1)[1]
            if original == "com.byd.dashcast.satellite.SatelliteTls" and signature in line and " -> " in line:
                return owner, line.rsplit(" -> ", 1)[1]
        parser.error("Mapping lacks a callable TLS member: " + signature)

    tls_args = []
    if args.tls:
        context_class, context_method = mapped_tls_member("context()")
        fingerprint_class, fingerprint_method = mapped_tls_member("fingerprint()")
        singleton_class, singleton_field = mapped_tls_member("INSTANCE")
        if context_class != fingerprint_class:
            parser.error("TLS context and fingerprint were moved to separate classes")
        tls_args = [context_class, context_method, fingerprint_method, singleton_class, singleton_field]
    adb = [str(args.sdk / "platform-tools/adb"), "-s", args.serial]
    sdk = run(adb + ["shell", "getprop", "ro.build.version.sdk"], capture_output=True).stdout.strip()
    qemu = run(adb + ["shell", "getprop", "ro.kernel.qemu"], capture_output=True).stdout.strip()
    if sdk != "29" or qemu != "1":
        parser.error("The probe only runs on an API 29 Android emulator; physical devices are refused")
    javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if os.environ.get("JAVA_HOME") else shutil.which("javac")
    if not javac:
        parser.error("javac is required")
    source = Path(__file__).parent / "android/ReleaseSatelliteArtProbe.java"
    remote = "/data/local/tmp/satellite-art-" + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix="satellite-art-") as temporary:
        directory = Path(temporary)
        run([javac, "-source", "8", "-target", "8", "-cp",
             str(args.sdk / "platforms/android-36/android.jar"), "-d", str(directory), str(source)])
        jar = directory / "probe.jar"
        classes = sorted(str(path) for path in directory.glob("*.class"))
        run([str(args.sdk / "build-tools" / args.build_tools / "d8"), "--min-api", "28",
             "--lib", str(args.sdk / "platforms/android-36/android.jar"), "--output", str(jar), *classes])
        run(adb + ["shell", "mkdir", "-p", remote])
        try:
            run(adb + ["push", str(jar), remote + "/probe.jar"])
            run(adb + ["push", str(apk), remote + "/release.apk"])
            command = "CLASSPATH=" + shlex.quote(remote + "/probe.jar") + " " + shlex.join([
                "app_process", "/system/bin", "ReleaseSatelliteArtProbe", remote + "/release.apk",
                server_class, remote, "load" if args.load_only else "tls" if args.tls else "bind", "47832",
                *tls_args])
            run(adb + ["shell", command])
        finally:
            run(adb + ["shell", "rm", "-rf", remote])


if __name__ == "__main__":
    main()
