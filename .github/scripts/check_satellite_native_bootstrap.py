#!/usr/bin/env python3
"""Check declared JNI Zero bootstrap methods in the actual optimized M150 APK DEX files.

This checks class/method definitions, not incidental strings or references. It does not execute
JNI or establish decoder/hardware compatibility; the Android ART native-load probe remains required.
"""
import argparse
from pathlib import Path
import re
import struct
import zipfile

# The reviewed SDK marks these entry points @org.jni_zero.CalledByNative.
REQUIRED = {
    b"Lorg/jni_zero/JniZero;": {
        (b"init", b"()[Ljava/lang/Object;"),
        (b"crashIfMultiplexingMisaligned", b"(JJ)V"),
    },
    b"Lorg/jni_zero/CommonApis;": {
        (b"mapToArray", b"(Ljava/util/Map;)[Ljava/lang/Object;"),
        (b"arrayToMap", b"([Ljava/lang/Object;)Ljava/util/Map;"),
    },
}
MAX_DEX_BYTES = 64 * 1024 * 1024


class InvalidDex(ValueError):
    pass


class Dex:
    """Minimal bounded standard-DEX declaration reader, deliberately independent of R8 mapping."""
    def __init__(self, data: bytes):
        self.data = data
        if len(data) < 112 or len(data) > MAX_DEX_BYTES or data[:4] != b"dex\n" or data[7] != 0:
            raise InvalidDex("Unsupported DEX header")
        if self.u32(32) != len(data) or self.u32(36) != 112 or self.u32(40) != 0x12345678:
            raise InvalidDex("Invalid DEX size or byte order")
        self.strings = self.table(56, 4)
        self.types = self.table(64, 4)
        self.protos = self.table(72, 12)
        self.methods = self.table(88, 8)
        self.classes = self.table(96, 32)

    def bounds(self, offset, count):
        if offset < 0 or count < 0 or offset + count > len(self.data):
            raise InvalidDex("DEX offset outside file")

    def u32(self, offset):
        self.bounds(offset, 4)
        return struct.unpack_from("<I", self.data, offset)[0]

    def table(self, offset, stride):
        count, start = self.u32(offset), self.u32(offset + 4)
        self.bounds(start, count * stride)
        return count, start

    def index(self, table, index, stride):
        if index >= table[0]:
            raise InvalidDex("DEX table index outside range")
        return table[1] + index * stride

    def leb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            self.bounds(offset, 1)
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                if value > 0xFFFFFFFF:
                    raise InvalidDex("DEX integer overflow")
                return value, offset
        raise InvalidDex("Unterminated DEX integer")

    def string(self, index):
        offset = self.u32(self.index(self.strings, index, 4))
        _, offset = self.leb(offset)
        end = self.data.find(b"\0", offset)
        if end < 0:
            raise InvalidDex("Unterminated DEX string")
        # Descriptors and entry-point names below are ASCII, so no MUTF-8 decoding is needed.
        return self.data[offset:end]

    def type(self, index):
        return self.string(self.u32(self.index(self.types, index, 4)))

    def method(self, index):
        offset = self.index(self.methods, index, 8)
        owner, proto, name = struct.unpack_from("<HHI", self.data, offset)
        proto_offset = self.index(self.protos, proto, 12)
        result = self.type(self.u32(proto_offset + 4))
        parameters = self.u32(proto_offset + 8)
        arguments = b""
        if parameters:
            size = self.u32(parameters)
            self.bounds(parameters + 4, size * 2)
            arguments = b"".join(self.type(struct.unpack_from("<H", self.data, parameters + 4 + i * 2)[0]) for i in range(size))
        return self.type(owner), self.string(name), b"(" + arguments + b")" + result

    def declarations(self):
        result = {}
        for i in range(self.classes[0]):
            offset = self.classes[1] + i * 32
            owner = self.type(self.u32(offset))
            if owner not in REQUIRED:
                continue
            declared = result.setdefault(owner, set())
            data_offset = self.u32(offset + 24)
            if not data_offset:
                continue
            counts = []
            for _ in range(4):
                count, data_offset = self.leb(data_offset)
                counts.append(count)
            if any(count > 65_535 for count in counts):
                raise InvalidDex("Implausible DEX member count")
            for _ in range(counts[0] + counts[1]):
                _, data_offset = self.leb(data_offset)
                _, data_offset = self.leb(data_offset)
            for count in counts[2:]:
                method_index = 0
                for _ in range(count):
                    delta, data_offset = self.leb(data_offset)
                    method_index += delta
                    access, data_offset = self.leb(data_offset)
                    code, data_offset = self.leb(data_offset)
                    method_owner, name, signature = self.method(method_index)
                    if method_owner != owner:
                        raise InvalidDex("DEX declaration owner mismatch")
                    if code:
                        self.bounds(code, 16)
                        size = self.u32(code + 12)
                        self.bounds(code + 16, size * 2)
                    # These exact bootstrap methods must have private static executable bodies.
                    if access & 0xA == 0xA and code and size > 0 and code % 4 == 0:
                        declared.add((name, signature))
        return result


def check_apk(apk: Path):
    if apk.stat().st_size > 256 * 1024 * 1024:
        raise ValueError("APK exceeds native bootstrap check limit")
    declared = {}
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        if len(entries) > 10_000:
            raise ValueError("APK has too many entries")
        dex_entries = [entry for entry in entries if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", entry.filename)]
        if not dex_entries:
            raise ValueError("APK has no standard DEX files")
        for entry in dex_entries:
            if entry.file_size > MAX_DEX_BYTES:
                raise ValueError("APK DEX exceeds bootstrap check limit")
            for owner, methods in Dex(archive.read(entry)).declarations().items():
                if owner in declared:
                    raise ValueError("Duplicate JNI bootstrap class definition")
                declared[owner] = methods
    missing = []
    for owner, methods in REQUIRED.items():
        if owner not in declared:
            missing.append(owner.decode("ascii"))
        else:
            missing.extend(owner.decode("ascii") + "::" + name.decode("ascii") + signature.decode("ascii")
                           for name, signature in sorted(methods - declared[owner]))
    if missing:
        raise ValueError("Native WebRTC bootstrap declarations missing: " + ", ".join(missing))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path)
    source.add_argument("--apk-dir", type=Path)
    args = parser.parse_args()
    apk = args.apk
    if args.apk_dir:
        matches = list(args.apk_dir.glob("*.apk"))
        if len(matches) != 1:
            parser.error("Expected exactly one release APK")
        apk = matches[0]
    try:
        check_apk(apk)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, str(error) + "\n")
    print("NATIVE_WEBRTC_BOOTSTRAP_DEFINITIONS_OK")


if __name__ == "__main__":
    main()
