#!/usr/bin/env python3
"""Surgically reroute Infinity+ Add account from AppAuthLoginActivity to LoginActivity.

This operates on an existing Infinity+ APK rather than rebuilding upstream source, so
all existing package/resource/ReVanced changes are preserved. The old APK signature
is removed because any byte change invalidates it; sign the output APK afterward.
"""

from pathlib import Path
import hashlib
import re
import struct
import sys
import zipfile
import zlib

APP_AUTH = "Lml/docilealligator/infinityforreddit/activities/AppAuthLoginActivity;"
WEBVIEW_LOGIN = "Lml/docilealligator/infinityforreddit/activities/LoginActivity;"
MAIN_CALLBACK = "Lml/docilealligator/infinityforreddit/activities/MainActivity$d;"


def uleb(data, pos):
    value = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        value |= (b & 0x7F) << shift
        if b < 0x80:
            return value, pos
        shift += 7


def parse_dex(data):
    string_size, string_off = struct.unpack_from("<II", data, 0x38)
    type_size, type_off = struct.unpack_from("<II", data, 0x40)
    method_size, method_off = struct.unpack_from("<II", data, 0x58)
    class_size, class_off = struct.unpack_from("<II", data, 0x60)

    string_offsets = [struct.unpack_from("<I", data, string_off + 4 * i)[0] for i in range(string_size)]
    strings = []
    for off in string_offsets:
        _, p = uleb(data, off)
        end = data.find(b"\0", p)
        strings.append(data[p:end].decode("utf-8", "replace"))

    types = [strings[struct.unpack_from("<I", data, type_off + 4 * i)[0]] for i in range(type_size)]
    methods = []
    for i in range(method_size):
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", data, method_off + 8 * i)
        methods.append((types[class_idx], strings[name_idx], proto_idx))

    encoded_methods = []
    for ci in range(class_size):
        class_def = struct.unpack_from("<IIIIIIII", data, class_off + 32 * ci)
        class_data_off = class_def[6]
        if not class_data_off:
            continue
        p = class_data_off
        static_fields, p = uleb(data, p)
        instance_fields, p = uleb(data, p)
        direct_methods, p = uleb(data, p)
        virtual_methods, p = uleb(data, p)
        for _ in range(static_fields + instance_fields):
            _, p = uleb(data, p)
            _, p = uleb(data, p)
        for count in (direct_methods, virtual_methods):
            method_idx = 0
            for _ in range(count):
                diff, p = uleb(data, p)
                _, p = uleb(data, p)
                code_off, p = uleb(data, p)
                method_idx += diff
                if code_off:
                    encoded_methods.append((method_idx, methods[method_idx], code_off))
    return types, encoded_methods


def patch_dex(raw):
    data = bytearray(raw)
    types, methods = parse_dex(data)
    if APP_AUTH not in types or WEBVIEW_LOGIN not in types:
        raise SystemExit("Expected Infinity 8.2.1 login activity types are missing")
    app_auth_idx = types.index(APP_AUTH)
    webview_idx = types.index(WEBVIEW_LOGIN)

    candidates = []
    for _, (class_name, method_name, _), code_off in methods:
        insns_size = struct.unpack_from("<I", data, code_off + 12)[0]
        start = code_off + 16
        end = start + insns_size * 2
        for pos in range(start, end - 3, 2):
            if data[pos] != 0x1C:  # const-class, format 21c
                continue
            if struct.unpack_from("<H", data, pos + 2)[0] != app_auth_idx:
                continue
            if class_name == MAIN_CALLBACK:
                candidates.append((class_name, method_name, pos))

    if len(candidates) != 1:
        raise SystemExit(f"Expected one MainActivity Add-account AppAuth reference, found {candidates}")

    _, _, pos = candidates[0]
    struct.pack_into("<H", data, pos + 2, webview_idx)

    # Recompute DEX signature/checksum after the one code-unit edit.
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    return bytes(data), pos, app_auth_idx, webview_idx


def copy_info(info):
    out = zipfile.ZipInfo(info.filename, info.date_time)
    for attr in ("comment", "extra", "create_system", "create_version", "extract_version",
                 "flag_bits", "volume", "internal_attr", "external_attr"):
        try:
            setattr(out, attr, getattr(info, attr))
        except Exception:
            pass
    out.compress_type = info.compress_type
    return out


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_existing_infinity_plus_apk.py INPUT.apk OUTPUT-unsigned.apk")
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])

    with zipfile.ZipFile(source, "r") as zin:
        if "classes3.dex" not in zin.namelist():
            raise SystemExit("Expected classes3.dex is missing")
        patched, pos, old_idx, new_idx = patch_dex(zin.read("classes3.dex"))
        old_signatures = {"META-INF/REVANCED.SF", "META-INF/REVANCED.RSA", "META-INF/MANIFEST.MF"}
        with zipfile.ZipFile(output, "w", allowZip64=True) as zout:
            for info in zin.infolist():
                if info.filename in old_signatures:
                    continue
                payload = patched if info.filename == "classes3.dex" else zin.read(info.filename)
                zout.writestr(copy_info(info), payload, compress_type=info.compress_type,
                              compresslevel=9 if info.compress_type == zipfile.ZIP_DEFLATED else None)

    print(f"Patched MainActivity DEX offset {pos}: type@{old_idx} -> type@{new_idx}")
    print("Old APK signing metadata removed. Sign the output APK before installation.")


if __name__ == "__main__":
    main()
