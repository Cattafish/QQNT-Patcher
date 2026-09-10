#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import shutil
import zipfile
import subprocess
import re
import shlex
import struct
import time
import hashlib
import rules

TOOLS_DIR = os.path.abspath("./tools")
BAKSMALI_JAR = os.path.join(TOOLS_DIR, "baksmali.jar")
SMALI_JAR = os.path.join(TOOLS_DIR, "smali.jar")
DEXLIB2_JAR = os.path.join(TOOLS_DIR, "dexlib2.jar")
GUAVA_JAR = os.path.join(TOOLS_DIR, "guava.jar")
BSH_JAR = os.path.join(TOOLS_DIR, "bsh.jar")
DX_JAR = os.path.join(TOOLS_DIR, "dx.jar")
PROTOBUF_JAR = os.path.join(TOOLS_DIR, "protobuf.jar")
FIXED_KEYSTORE = os.path.join(TOOLS_DIR, "debug.keystore")

JAVA_OPTS = "-Xms256m -Xmx768m -XX:+UseParallelGC"

REQUIRED_JARS = [
    ("baksmali.jar", BAKSMALI_JAR),
    ("smali.jar", SMALI_JAR),
    ("dexlib2.jar", DEXLIB2_JAR),
    ("guava.jar", GUAVA_JAR),
    ("bsh.jar", BSH_JAR),
    ("dx.jar", DX_JAR),
    ("protobuf.jar", PROTOBUF_JAR)
]

def log(tag, msg):
    colors = {
        "INFO": "\033[1;34m[INFO]\033[0m",
        "OK": "\033[1;32m[SUCCESS]\033[0m",
        "WARN": "\033[1;33m[WARN]\033[0m",
        "ERR": "\033[1;31m[ERROR]\033[0m",
        "TIME": "\033[1;35m[TIME]\033[0m"
    }
    print(f"{colors.get(tag, '[*]')} {msg}")

def run_cmd(cmd, cwd=None):
    ret = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=cwd)
    out_msg = ret.stdout.decode('utf-8', errors='ignore').strip()
    err_msg = ret.stderr.decode('utf-8', errors='ignore').strip()
    if err_msg:
        for line in err_msg.split("\n"):
            if "[WARN]" in line:
                log("WARN", line)
            elif "[ERROR]" in line or "Exception" in line:
                log("ERR", line)
    if ret.returncode != 0:
        log("ERR", f"命令执行失败 (Exit code {ret.returncode}): {cmd}")
        if err_msg: log("ERR", err_msg)
        if out_msg: log("WARN", out_msg)
        return ""
    return ret.stdout.decode('utf-8', errors='ignore')

def run_cmd_stream(cmd, cwd=None):
    """实时流式执行器：逐行刷新终端，实时查看进度"""
    p = subprocess.Popen(
        cmd,
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        cwd=cwd,
        universal_newlines=True,
        bufsize=1
    )
    for line in p.stdout:
        line_str = line.strip()
        if not line_str:
            continue
        if "[WARN]" in line_str:
            log("WARN", line_str)
        elif "[ERROR]" in line_str:
            log("ERR", line_str)
        elif "[DexPatcher]" in line_str:
            print(f"\033[1;32m[*] {line_str}\033[0m")
        else:
            print(f"    {line_str}")
    p.wait()
    return p.returncode

def ensure_smali_jars():
    """依赖检查：只要文件不存在或小于等于 1KB，直接 ERROR 并退出"""
    missing_jars = []
    for name, path in REQUIRED_JARS:
        if not os.path.exists(path):
            missing_jars.append(f"{name} (文件不存在)")
        elif os.path.getsize(path) <= 1024:
            missing_jars.append(f"{name} (文件小于等于 1KB: {os.path.getsize(path)} 字节)")

    if missing_jars:
        log("ERR", "=" * 60)
        log("ERR", "缺少必要的 tools 依赖 Jar 包，无法继续构建：")
        for item in missing_jars:
            log("ERR", f"  -> tools/{item}")
        log("ERR", "=" * 60)
        sys.exit(1)

def ensure_fixed_keystore():
    os.makedirs(TOOLS_DIR, exist_ok=True)
    if not os.path.exists(FIXED_KEYSTORE):
        log("INFO", "正在初始化固定签名证书 (仅首次生成)...")
        run_cmd(f"keytool -genkey -v -keystore {shlex.quote(FIXED_KEYSTORE)} -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'")

def extract_original_apk_metadata(input_apk):
    """全自动提取输入官方原版 APK 的全量 MD5 和证书 MD5 指纹"""
    log("INFO", "0. 正在提取官方原包特征指纹...")
    
    h = hashlib.md5()
    with open(input_apk, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    orig_apk_md5 = h.hexdigest().lower()
    log("OK", f"  -> 原版 APK MD5 : \033[36m{orig_apk_md5}\033[0m")

    orig_sig_md5 = ""
    try:
        cert_out = subprocess.getoutput(f"apksigner verify --print-certs {shlex.quote(input_apk)}")
        m = re.search(r"certificate MD5 digest:\s*([0-9a-fA-F]{32})", cert_out)
        if m:
            orig_sig_md5 = m.group(1).lower()
        else:
            kt_out = subprocess.getoutput(f"keytool -printcert -jarfile {shlex.quote(input_apk)}")
            m2 = re.search(r"MD5:\s*([0-9a-fA-F:]{47})", kt_out)
            if m2:
                orig_sig_md5 = m2.group(1).replace(":", "").lower()
    except Exception:
        pass

    if orig_sig_md5:
        log("OK", f"  -> 原版 签名 MD5: \033[36m{orig_sig_md5}\033[0m")
    return orig_apk_md5, orig_sig_md5

def compile_helper_dex_incremental(work_dir):
    src_dir = "./src"
    if not os.path.exists(src_dir):
        return None

    bin_dir = os.path.join(work_dir, "bin")
    dex_out = os.path.join(work_dir, "dex_out")
    target_dex = os.path.join(dex_out, "classes.dex")

    java_files = [os.path.join(r, f) for r, _, fs in os.walk(src_dir) for f in fs if f.endswith(".java")]
    if not java_files:
        return None

    latest_src_mtime = max(os.path.getmtime(f) for f in java_files)
    if os.path.exists(target_dex) and os.path.getmtime(target_dex) >= latest_src_mtime:
        return target_dex

    os.makedirs(bin_dir, exist_ok=True)
    os.makedirs(dex_out, exist_ok=True)

    quoted_java = [shlex.quote(f) for f in java_files]
    run_cmd(f"javac -d {shlex.quote(bin_dir)} " + " ".join(quoted_java))

    patch_classes = [os.path.join(r, f) for r, _, fs in os.walk(bin_dir) for f in fs if f.endswith(".class") and ("com/tencent/qqnt/patch" in r or "me/yxp" in r)]
    if not patch_classes:
        log("ERR", "编译 helper java 失败！")
        return None

    quoted_classes = [shlex.quote(f) for f in patch_classes]
    run_cmd(f"d8 --min-api 26 --output {shlex.quote(dex_out)} " + " ".join(quoted_classes))

    return target_dex if os.path.exists(target_dex) else None

def compile_bsh_to_asset_dex(work_dir):
    bsh_dex_dir = os.path.join(work_dir, "bsh_dex")
    target_bsh_dex = os.path.join(bsh_dex_dir, "classes.dex")
    final_bsh_dex = os.path.join(work_dir, "bsh.dex")

    dep_mtime = max(
        os.path.getmtime(BSH_JAR),
        os.path.getmtime(DX_JAR) if os.path.exists(DX_JAR) else 0,
        os.path.getmtime(PROTOBUF_JAR) if os.path.exists(PROTOBUF_JAR) else 0
    )
    if os.path.exists(final_bsh_dex) and os.path.getmtime(final_bsh_dex) >= dep_mtime:
        return final_bsh_dex

    os.makedirs(bsh_dex_dir, exist_ok=True)
    d8_inputs = f"{shlex.quote(BSH_JAR)} {shlex.quote(DX_JAR)} {shlex.quote(PROTOBUF_JAR)}"
    run_cmd(f"d8 --min-api 26 --output {shlex.quote(bsh_dex_dir)} {d8_inputs}")

    if os.path.exists(target_bsh_dex):
        shutil.copyfile(target_bsh_dex, final_bsh_dex)
        return final_bsh_dex
    return None

def build_dex_patcher_engine_incremental(work_dir):
    engine_src = "./DexPatcher.java"
    if not os.path.exists(engine_src):
        log("ERR", "未找到 DexPatcher.java！")
        sys.exit(1)

    engine_bin = os.path.join(work_dir, "patcher_bin")
    engine_class = os.path.join(engine_bin, "com/tencent/qqnt/patcher/DexPatcher.class")

    if os.path.exists(engine_class) and os.path.getmtime(engine_class) >= os.path.getmtime(engine_src):
        return engine_bin

    os.makedirs(engine_bin, exist_ok=True)
    cp = f"{shlex.quote(DEXLIB2_JAR)}:{shlex.quote(SMALI_JAR)}:{shlex.quote(BAKSMALI_JAR)}"
    run_cmd(f"javac -cp {cp} -d {shlex.quote(engine_bin)} {shlex.quote(engine_src)}")
    return engine_bin

def get_defined_classes_in_dex(dex_bytes):
    if len(dex_bytes) < 0x70 or dex_bytes[:4] != b'dex\n':
        return set()
    try:
        string_ids_off = struct.unpack_from('<I', dex_bytes, 0x3C)[0]
        type_ids_off = struct.unpack_from('<I', dex_bytes, 0x44)[0]
        class_defs_size, class_defs_off = struct.unpack_from('<II', dex_bytes, 0x60)

        classes = set()
        for i in range(class_defs_size):
            class_idx = struct.unpack_from('<I', dex_bytes, class_defs_off + i * 32)[0]
            desc_idx = struct.unpack_from('<I', dex_bytes, type_ids_off + class_idx * 4)[0]
            str_off = struct.unpack_from('<I', dex_bytes, string_ids_off + desc_idx * 4)[0]

            p = str_off
            while dex_bytes[p] & 0x80: p += 1
            p += 1

            end = dex_bytes.find(b'\x00', p)
            if end != -1:
                classes.add(dex_bytes[p:end].decode('utf-8', errors='ignore'))
        return classes
    except Exception:
        return set()

def dump_batch_tasks(dex_tasks, batch_file):
    with open(batch_file, "w", encoding="utf-8") as f:
        for dex_in, dex_out, r_list in dex_tasks:
            f.write("===DEX_TASK_SPLIT===\n")
            f.write(f"DEX_IN={dex_in}\n")
            f.write(f"DEX_OUT={dex_out}\n")
            for r in r_list:
                f.write("===RULE_SPLIT===\n")
                f.write(f"NAME={r.get('name', '未命名规则')}\n")
                f.write(f"TARGET_CLASS={r['target_class']}\n")
                f.write(f"TARGET_METHOD={r['target_method']}\n")
                f.write(f"TYPE={r['type']}\n")
                if "regex" in r:
                    f.write(f"REGEX={r['regex']}\n")
                f.write("---SMALI_START---\n")
                f.write(r['smali'].strip() + "\n")
                f.write("---SMALI_END---\n")

def print_patch_details(dex_name, rule_list):
    """打印 Patch 内容详情"""
    print(f"\n\033[1;36m{'='*25} [{dex_name}] 补丁明细 ({len(rule_list)} 项) {'='*25}\033[0m")
    for idx, r in enumerate(rule_list, 1):
        name = r.get("name", "未命名规则")
        target_cls = r.get("target_class", "")
        target_m = r.get("target_method", "")
        p_type = r.get("type", "")

        print(f"\033[1;33m[{idx:02d}] {name}\033[0m")
        print(f"  ├─ 目标类: \033[32m{target_cls}\033[0m")
        print(f"  ├─ 目标方法: \033[35m{target_m}\033[0m")
        print(f"  ├─ 修补类型: \033[36m{p_type}\033[0m")

        if p_type == "REGEX_REPLACE" and "regex" in r:
            print(f"  ├─ 匹配正则: \033[90m{r['regex']}\033[0m")
            print("  └─ 替换内容:")
        else:
            print("  └─ 注入 Smali 代码:")

        for sl in r.get("smali", "").strip().split("\n"):
            print(f"     \033[90m│\033[0m {sl}")
        print()

def patch_native_so(input_apk, work_dir):
    patched_files = []
    target_entries = ["lib/arm64-v8a/libcodecwrapperV2.so"]

    with zipfile.ZipFile(input_apk, 'r') as zf:
        namelist = zf.namelist()
        for entry in target_entries:
            if entry in namelist:
                raw_bytes = bytearray(zf.read(entry))
                patched = False

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
                        patched = True
                        log("OK", f"-> Native SO 查签拦截成功 (特征对齐): {entry} @ 0x{idx:X}")
                        print(f"   \033[31m[-] 原指令: 37 00 80 52 (mov w23, #1)\033[0m")
                        print(f"   \033[32m[+] 新指令: 17 00 80 52 (mov w23, #0)\033[0m")
                        break

                    pos = idx + 4

                if patched:
                    out_path = os.path.join(work_dir, entry)
                    os.makedirs(os.path.dirname(out_path), exist_ok=True)
                    with open(out_path, "wb") as f:
                        f.write(raw_bytes)
                    patched_files.append((out_path, entry))
                else:
                    log("WARN", f"-> 未在 {entry} 中定位到特征跳转，跳过 Native 修补")
    return patched_files

def main():
    t_start = time.time()

    # 1. 启动前校验 tools 依赖
    ensure_smali_jars()

    args = sys.argv[1:]
    no_sign = False

    if "--no-sign" in args:
        no_sign = True
        args.remove("--no-sign")
    if "-n" in args:
        no_sign = True
        args.remove("-n")

    input_apk = args[0] if len(args) > 0 else "QQ.apk"
    output_apk = args[1] if len(args) > 1 else "QQ_Patched.apk"

    if not os.path.exists(input_apk):
        log("ERR", f"未找到输入 APK 文件: {input_apk}")
        sys.exit(1)

    # 2. 提取官方原包特征指纹
    orig_apk_md5, orig_sig_md5 = extract_original_apk_metadata(input_apk)

    work_dir = "./build_cache"
    os.makedirs(work_dir, exist_ok=True)

    t0 = time.time()
    log("INFO", "1. 正在准备构建环境与扩展 Dex...")
    engine_bin = build_dex_patcher_engine_incremental(work_dir)
    helper_dex_path = compile_helper_dex_incremental(work_dir)
    bsh_standalone_dex = compile_bsh_to_asset_dex(work_dir)
    if not helper_dex_path:
        log("ERR", "扩展 Dex 编译失败！")
        sys.exit(1)
    if not no_sign:
        ensure_fixed_keystore()
    t_phase1 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 1 耗时: {t_phase1}s")

    t0 = time.time()
    log("INFO", "2. 正在提取 Dex 并动态推导安全风控穿透规则...")
    dex_data_dict = {}
    with zipfile.ZipFile(input_apk, 'r') as zf:
        for name in zf.namelist():
            if re.match(r'^classes\d*\.dex$', name):
                dex_data_dict[name] = zf.read(name)

    def dex_index(name):
        if name == "classes.dex": return 1
        m = re.match(r'classes(\d+)\.dex', name)
        return int(m.group(1)) if m else 0

    dex_list = sorted(dex_data_dict.keys(), key=dex_index)
    max_idx = dex_index(dex_list[-1])
    next_dex_name = f"classes{max_idx + 1}.dex"

    all_rules = list(rules.RULES)

    # 动态推导安全规则
    dynamic_sec_rules = rules.get_dynamic_security_rules(
        dex_data_dict,
        orig_apk_md5=orig_apk_md5,
        orig_sig_md5=orig_sig_md5
    )
    all_rules.extend(dynamic_sec_rules)
    for r in dynamic_sec_rules:
        log("OK", f"-> 安全穿透规则生成: [{r['name']}]")

    dyn_setting_rule = rules.get_dynamic_setting_rule_fast(dex_data_dict)
    if dyn_setting_rule:
        all_rules.append(dyn_setting_rule)
        log("OK", f"-> 设置入口匹配: [{dyn_setting_rule['name']}]")
        
    dyn_tablet_rule = rules.get_dynamic_tablet_rule_fast(dex_data_dict)
    if dyn_tablet_rule:
        all_rules.append(dyn_tablet_rule)
        log("OK", f"-> 平板模式动态匹配: [{dyn_tablet_rule['name']}]")

    dex_to_rules = {}
    matched_rule_names = set()

    for dex_name in dex_list:
        defined_classes = get_defined_classes_in_dex(dex_data_dict[dex_name])
        for rule in all_rules:
            if rule["target_class"] in defined_classes:
                dex_to_rules.setdefault(dex_name, []).append(rule)
                matched_rule_names.add(rule["name"])

    for rule in all_rules:
        if rule["name"] not in matched_rule_names:
            log("WARN", f"-> 规则未命中当前包: [{rule['name']}]")

    for d_name, r_list in dex_to_rules.items():
        log("INFO", f"-> 分包 [{d_name}] 装载 {len(r_list)} 条修改规则")
        print_patch_details(d_name, r_list)

    t_phase2 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 2 耗时: {t_phase2}s")

    t0 = time.time()
    log("INFO", f"3. 正在执行 Dex 字节码内存 AST 重构 ({len(dex_to_rules)} 个分包)...")
    dex_tasks = []
    modified_dex_files = []

    for dex_name, r_list in dex_to_rules.items():
        dex_raw_path = os.path.join(work_dir, dex_name)
        with open(dex_raw_path, "wb") as f:
            f.write(dex_data_dict[dex_name])

        patched_dex = os.path.join(work_dir, f"patched_{dex_name}")
        dex_tasks.append((dex_raw_path, patched_dex, r_list))
        modified_dex_files.append((patched_dex, dex_name))

    batch_cfg_path = os.path.join(work_dir, "batch_tasks.txt")
    dump_batch_tasks(dex_tasks, batch_cfg_path)

    if engine_bin:
        cp = f"{shlex.quote(engine_bin)}:{shlex.quote(GUAVA_JAR)}:{shlex.quote(DEXLIB2_JAR)}:{shlex.quote(SMALI_JAR)}:{shlex.quote(BAKSMALI_JAR)}"
        cmd = f"java {JAVA_OPTS} -cp {cp} com.tencent.qqnt.patcher.DexPatcher {shlex.quote(batch_cfg_path)}"
        run_cmd_stream(cmd)

    log("INFO", "3.1 正在扫描底层 Native SO 安全探针...")
    patched_so_files = patch_native_so(input_apk, work_dir)

    del dex_data_dict
    t_phase3 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 3 耗时: {t_phase3}s")

    t0 = time.time()
    log("INFO", "4. 正在打包 APK (复制原包并注入修改文件)...")
    if shutil.which("cp"):
        run_cmd(f"cp -f {shlex.quote(input_apk)} {shlex.quote(output_apk)}")
    else:
        shutil.copyfile(input_apk, output_apk)

    inject_dir = os.path.join(work_dir, "inject")
    os.makedirs(inject_dir, exist_ok=True)

    zip_args = []
    for local_path, in_zip_name in modified_dex_files:
        if os.path.exists(local_path):
            target_in_dir = os.path.join(inject_dir, in_zip_name)
            shutil.copyfile(local_path, target_in_dir)
            zip_args.append(shlex.quote(in_zip_name))

    for local_so, in_zip_so in patched_so_files:
        if os.path.exists(local_so):
            target_so_dir = os.path.join(inject_dir, os.path.dirname(in_zip_so))
            os.makedirs(target_so_dir, exist_ok=True)
            shutil.copyfile(local_so, os.path.join(inject_dir, in_zip_so))
            zip_args.append(shlex.quote(in_zip_so))

    if helper_dex_path and os.path.exists(helper_dex_path):
        target_helper = os.path.join(inject_dir, next_dex_name)
        shutil.copyfile(helper_dex_path, target_helper)
        zip_args.append(shlex.quote(next_dex_name))

    if bsh_standalone_dex and os.path.exists(bsh_standalone_dex):
        target_assets_dir = os.path.join(inject_dir, "assets")
        os.makedirs(target_assets_dir, exist_ok=True)
        shutil.copyfile(bsh_standalone_dex, os.path.join(target_assets_dir, "bsh.dex"))
        zip_args.append(shlex.quote("assets/bsh.dex"))

    # ★ 核心优化：自动全量打包 assets/ 目录下的所有文件 (zzz_icon.png, script_icon.png 等)
    assets_src_dir = "assets"
    if os.path.exists(assets_src_dir):
        target_assets_dir = os.path.join(inject_dir, "assets")
        os.makedirs(target_assets_dir, exist_ok=True)
        for f in os.listdir(assets_src_dir):
            src_f = os.path.join(assets_src_dir, f)
            if os.path.isfile(src_f):
                shutil.copyfile(src_f, os.path.join(target_assets_dir, f))
                zip_args.append(shlex.quote(f"assets/{f}"))

    abs_output_apk = os.path.abspath(output_apk)
    if zip_args:
        run_cmd(f"cd {shlex.quote(inject_dir)} && zip -q -1 -u {shlex.quote(abs_output_apk)} " + " ".join(zip_args))

    if no_sign:
        subprocess.run(f"zip -q -d {shlex.quote(abs_output_apk)} 'META-INF/*' 2>/dev/null", shell=True)

    zipalign_bin = shutil.which("zipalign")
    if zipalign_bin:
        aligned_apk = os.path.join(work_dir, "aligned_temp.apk")
        run_cmd(f"{shlex.quote(zipalign_bin)} -p -f 4 {shlex.quote(abs_output_apk)} {shlex.quote(aligned_apk)}")
        if os.path.exists(aligned_apk) and os.path.getsize(aligned_apk) > 0:
            shutil.move(aligned_apk, abs_output_apk)

    t_phase4 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 4 耗时: {t_phase4}s")

    t_phase5 = 0.0
    if not no_sign:
        t0 = time.time()
        log("INFO", "5. 正在对 APK 进行固定证书签名...")
        if not os.path.exists(FIXED_KEYSTORE):
            ensure_fixed_keystore()

        run_cmd(f"apksigner sign --ks {shlex.quote(FIXED_KEYSTORE)} --ks-pass pass:android --key-pass pass:android {shlex.quote(output_apk)}")
        t_phase5 = round(time.time() - t0, 2)
        log("TIME", f"  -> 阶段 5 耗时: {t_phase5}s")
    else:
        log("INFO", "5. 跳过 APK 签名 (--no-sign)")

    for f in os.listdir(work_dir):
        p = os.path.join(work_dir, f)
        if f not in ["patcher_bin", "dex_out", "bin"]:
            if os.path.isdir(p): shutil.rmtree(p, ignore_errors=True)
            else: os.remove(p)

    t_cost = round(time.time() - t_start, 2)
    log("OK", f"构建完成，耗时: {t_cost}s, 输出: {output_apk}")

if __name__ == "__main__":
    main()