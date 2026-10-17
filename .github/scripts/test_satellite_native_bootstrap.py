#!/usr/bin/env python3
"""Regression fixtures for actual DEX declarations; synthetic files are not ART execution tests."""
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib

SPEC = importlib.util.spec_from_file_location("bootstrap", Path(__file__).with_name("check_satellite_native_bootstrap.py"))
bootstrap = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bootstrap)


def leb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def dex_fixture(owners=None, declarations=True, rename=False, signature_ok=True, executable=True, is_static=True):
    owners = set(bootstrap.REQUIRED if owners is None else owners)
    # References deliberately remain even when a class/method definition is removed by the fixture.
    methods = []
    types = set(bootstrap.REQUIRED) | {b"[Ljava/lang/Object;", b"Ljava/util/Map;", b"J", b"V"}
    for owner, entries in bootstrap.REQUIRED.items():
        for name, signature in entries:
            args, result = signature[1:].split(b")")
            parameters = [] if not args else ([b"J", b"J"] if args == b"JJ" else [args])
            if not signature_ok and name == b"init":
                result = b"V"
            methods.append((owner, b"renamed" if rename and name == b"init" else name, tuple(parameters), result))
    strings = sorted(types | {m[1] for m in methods} | {b"L", b"LL", b"VJJ"})
    string_index = {value: i for i, value in enumerate(strings)}
    types = sorted(types, key=string_index.get)
    type_index = {value: i for i, value in enumerate(types)}
    protos = sorted({(m[2], m[3]) for m in methods})
    proto_index = {value: i for i, value in enumerate(protos)}
    methods.sort(key=lambda m: (type_index[m[0]], string_index[m[1]], proto_index[(m[2], m[3])]))
    class_names = sorted(owners, key=type_index.get)
    string_off = 112
    type_off = string_off + 4 * len(strings)
    proto_off = type_off + 4 * len(types)
    method_off = proto_off + 12 * len(protos)
    class_off = method_off + 8 * len(methods)
    data_start = class_off + 32 * len(class_names)
    data = bytearray(data_start)
    for i, value in enumerate(strings):
        struct.pack_into("<I", data, string_off + i * 4, len(data))
        data += leb(len(value)) + value + b"\0"
    for i, value in enumerate(types):
        struct.pack_into("<I", data, type_off + i * 4, string_index[value])
    for i, (parameters, result) in enumerate(protos):
        while len(data) % 4:
            data.append(0)
        parameter_off = 0
        if parameters:
            parameter_off = len(data)
            data += struct.pack("<I", len(parameters))
            data += b"".join(struct.pack("<H", type_index[p]) for p in parameters)
        shorty = (b"L" if result.startswith((b"L", b"[")) else result) + b"".join(
            b"L" if p.startswith((b"L", b"[")) else p for p in parameters)
        struct.pack_into("<III", data, proto_off + i * 12, string_index[shorty], type_index[result], parameter_off)
    code_offsets = []
    for i, (owner, name, parameters, result) in enumerate(methods):
        struct.pack_into("<HHI", data, method_off + i * 8, type_index[owner], proto_index[(parameters, result)], string_index[name])
        while len(data) % 4:
            data.append(0)
        code_offsets.append(len(data))
        words = sum(2 if p == b"J" else 1 for p in parameters)
        instructions = [0x000E] if result == b"V" else [0x0012, 0x0011]
        data += struct.pack("<HHHHII", max(1, words), words, 0, 0, 0, len(instructions))
        data += b"".join(struct.pack("<H", instruction) for instruction in instructions)
    for i, owner in enumerate(class_names):
        class_data = len(data)
        entries = [index for index, method in enumerate(methods) if method[0] == owner] if declarations else []
        data += b"\0\0" + leb(len(entries)) + b"\0"
        previous = 0
        for index in entries:
            data += leb(index - previous) + leb(0xA if is_static else 0x2) + leb(code_offsets[index] if executable else 0)
            previous = index
        struct.pack_into("<IIIIIIII", data, class_off + i * 32, type_index[owner], 1, 0xFFFFFFFF, 0, 0xFFFFFFFF, 0, class_data, 0)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<III", data, 32, len(data), 112, 0x12345678)
    for offset, count, start in [(56, len(strings), string_off), (64, len(types), type_off), (72, len(protos), proto_off),
                                 (88, len(methods), method_off), (96, len(class_names), class_off)]:
        struct.pack_into("<II", data, offset, count, start)
    struct.pack_into("<II", data, 104, len(data) - data_start, data_start)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    return bytes(data)


class NativeBootstrapTest(unittest.TestCase):
    def apk(self, folder, entries):
        result = Path(folder) / "release.apk"
        with zipfile.ZipFile(result, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        return result

    def test_actual_definitions_and_signatures_pass_across_dex_files(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = self.apk(folder, {"classes.dex": dex_fixture([b"Lorg/jni_zero/JniZero;"]),
                                    "classes10.dex": dex_fixture([b"Lorg/jni_zero/CommonApis;"])})
            bootstrap.check_apk(apk)

    def test_incidental_strings_and_method_references_do_not_count_as_classes(self):
        data = dex_fixture([])
        self.assertIn(b"Lorg/jni_zero/JniZero;", data)
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "bootstrap declarations missing"):
                bootstrap.check_apk(self.apk(folder, {"classes.dex": data}))

    def test_referenced_but_not_declared_bootstrap_methods_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, r"JniZero;::init"):
                bootstrap.check_apk(self.apk(folder, {"classes.dex": dex_fixture(declarations=False)}))

    def test_renamed_private_native_entry_method_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, r"JniZero;::init"):
                bootstrap.check_apk(self.apk(folder, {"classes.dex": dex_fixture(rename=True)}))

    def test_named_method_with_wrong_return_descriptor_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, r"JniZero;::init"):
                bootstrap.check_apk(self.apk(folder, {"classes.dex": dex_fixture(signature_ok=False)}))

    def test_instance_methods_cannot_satisfy_static_native_bootstrap(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, r"JniZero;::init"):
                bootstrap.check_apk(self.apk(folder, {"classes.dex": dex_fixture(is_static=False)}))

    def test_declaration_without_executable_code_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, r"JniZero;::init"):
                bootstrap.check_apk(self.apk(folder, {"classes.dex": dex_fixture(executable=False)}))

    def test_duplicate_class_definitions_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "Duplicate JNI bootstrap class"):
                bootstrap.check_apk(self.apk(folder, {"classes.dex": dex_fixture(), "classes2.dex": dex_fixture()}))

    def test_invalid_table_offsets_fail_without_accepting_string_literals(self):
        data = bytearray(dex_fixture())
        struct.pack_into("<I", data, 100, len(data) - 1)
        with self.assertRaises(bootstrap.InvalidDex):
            bootstrap.Dex(bytes(data))


if __name__ == "__main__":
    unittest.main()
