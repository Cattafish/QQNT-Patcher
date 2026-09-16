#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Native SO 二进制指令修补器 (原位 Bypass 校验跳转)"""

import os
import zipfile
import struct

def patch_native_so(input_apk, work_dir, log_func=None):
    """
    扫描 arm64-v8a 底层 libcodecwrapperV2.so
    精确定位 mov w23, #1 (37 00 80 52) 并在存在 cbz 窗口时全量原位替换为 mov w23, #0 (17 00 80 52)
    """
    patched_files = []
    target_entries = ["lib/arm64-v8a/libcodecwrapperV2.so"]

    with zipfile.ZipFile(input_apk, 'r') as zf:
        namelist = zf.namelist()
        for entry in target_entries:
            if entry in namelist:
                raw_bytes = bytearray(zf.read(entry))
                match_count = 0
                pos = 0

                while True:
                    idx = raw_bytes.find(b"\x37\x00\x80\x52", pos)
                    if idx == -1:
                        break

                    window = raw_bytes[idx : min(len(raw_bytes), idx + 32)]
                    has_cbz = False
                    for w_idx in range(0, len(window) - 3, 4):
                        insn = struct.unpack_from('<I', window, w_idx)[0]
                        if (insn & 0x7F00001F) == 0x34000017:
                            has_cbz = True
                            break

                    if has_cbz:
                        raw_bytes[idx : idx + 4] = b"\x17\x00\x80\x52"
                        match_count += 1
                        if log_func:
                            log_func("OK", f"-> Native SO 查签拦截命中 [{match_count}]: {entry} @ 0x{idx:X}")
                            print(f"   \033[31m[-] 原指令: 37 00 80 52 (mov w23, #1)\033[0m")
                            print(f"   \033[32m[+] 新指令: 17 00 80 52 (mov w23, #0)\033[0m")

                    pos = idx + 4

                if match_count > 0:
                    out_path = os.path.join(work_dir, entry)
                    os.makedirs(os.path.dirname(out_path), exist_ok=True)
                    with open(out_path, "wb") as f:
                        f.write(raw_bytes)
                    patched_files.append((out_path, entry))
                    if log_func:
                        log_func("OK", f"-> {entry} 修补完成，共处理 {match_count} 处特征点")
                else:
                    if log_func:
                        log_func("WARN", f"-> 未在 {entry} 中定位到特征跳转，跳过 Native 修补")
    return patched_files