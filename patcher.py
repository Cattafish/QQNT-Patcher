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
    if ret.returncode != 0:
        log("WARN", f"命令执行异常: {cmd}")
        err_msg = ret.stderr.decode('utf-8', errors='ignore').strip()
        out_msg = ret.stdout.decode('utf-8', errors='ignore').strip()
        if err_msg: log("WARN", err_msg)
        if out_msg: log("WARN", out_msg)
        return ""
    return ret.stdout.decode('utf-8', errors='ignore')

def ensure_smali_jars():
    required = [BAKSMALI_JAR, SMALI_JAR, DEXLIB2_JAR, GUAVA_JAR, BSH_JAR, DX_JAR, PROTOBUF_JAR]
    if not all(os.path.exists(f) and os.path.getsize(f) > 50000 for f in required):
        log("WARN", "未检测到完整的 tools 依赖 (请检查 baksmali/smali/dexlib2/guava/bsh/dx/protobuf.jar)！")

def ensure_fixed_keystore():
    os.makedirs(TOOLS_DIR, exist_ok=True)
    if not os.path.exists(FIXED_KEYSTORE):
        log("INFO", "正在初始化固定签名证书 (仅首次生成)...")
        run_cmd(f"keytool -genkey -v -keystore {shlex.quote(FIXED_KEYSTORE)} -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'")

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
    ensure_smali_jars()
    engine_src = "./DexPatcher.java"
    if not os.path.exists(engine_src):
        log("WARN", "未找到 DexPatcher.java！")
        return None

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
                f.write(f"TARGET_CLASS={r['target_class']}\n")
                f.write(f"TARGET_METHOD={r['target_method']}\n")
                f.write(f"TYPE={r['type']}\n")
                if "regex" in r:
                    f.write(f"REGEX={r['regex']}\n")
                f.write("---SMALI_START---\n")
                f.write(r['smali'].strip() + "\n")
                f.write("---SMALI_END---\n")

def main():
    t_start = time.time()

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
        log("WARN", f"未找到输入 APK 文件: {input_apk}")
        sys.exit(1)

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
    log("INFO", "2. 正在扫描 Dex 分包与匹配规则...")
    dex_data_dict = {}
    with zipfile.ZipFile(input_apk, 'r') as zf:
        for name in zf.namelist():
            if re.match(r'^classes\d*\.dex$', name):
                dex_data_dict[name] = zf.read(name)

    # === [诊断探测：打印目标 AIODelegate 的所有声明方法] ===
    for d_name, d_bytes in dex_data_dict.items():
        if b'Lcom/tencent/qqnt/aio/activity/AIODelegate;' in d_bytes:
            log("INFO", f"正在分析 AIODelegate (位于 {d_name})...")
            p = rules.FastDexParser(d_bytes)
            if p.valid:
                for i in range(p.class_defs_size):
                    c_idx = struct.unpack_from('<I', p.data, p.class_defs_off + i * 32)[0]
                    c_name = p.get_type_str(c_idx)
                    if c_name == "Lcom/tencent/qqnt/aio/activity/AIODelegate;":
                        c_data_off = struct.unpack_from('<I', p.data, p.class_defs_off + i * 32 + 24)[0]
                        if c_data_off == 0: continue
                        pos = c_data_off
                        s_f, pos = p.read_uleb128(pos)
                        i_f, pos = p.read_uleb128(pos)
                        d_m, pos = p.read_uleb128(pos)
                        v_m, pos = p.read_uleb128(pos)
                        for _ in range((s_f + i_f) * 2): _, pos = p.read_uleb128(pos)
                        m_idx = 0
                        for _ in range(d_m + v_m):
                            diff, pos = p.read_uleb128(pos)
                            m_idx += diff
                            _, pos = p.read_uleb128(pos)
                            _, pos = p.read_uleb128(pos)
                            _, proto_idx, name_idx = struct.unpack_from('<HHI', p.data, p.method_ids_off + m_idx * 8)
                            m_name = p.get_string(name_idx)
                            proto_desc = p.get_proto_desc(proto_idx)
                            if any(k in m_name.lower() for k in ["show", "hide", "contact", "aio"]):
                                log("OK", f"  -> AIODelegate 声明方法: {m_name}{proto_desc}")

    def dex_index(name):
        if name == "classes.dex": return 1
        m = re.match(r'classes(\d+)\.dex', name)
        return int(m.group(1)) if m else 0

    dex_list = sorted(dex_data_dict.keys(), key=dex_index)
    max_idx = dex_index(dex_list[-1])
    next_dex_name = f"classes{max_idx + 1}.dex"

    all_rules = list(rules.RULES)
    dyn_setting_rule = rules.get_dynamic_setting_rule_fast(dex_data_dict)
    if dyn_setting_rule:
        all_rules.append(dyn_setting_rule)
        log("OK", f"-> 动态规则匹配: [{dyn_setting_rule['name']}]")
    else:
        log("WARN", "-> 未检测到设置中心特征，跳过动态设置注入")

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
            log("WARN", f"-> 未命中规则: [{rule['name']}]")

    if not dex_to_rules:
        log("WARN", "未在 APK 中匹配到任何规则目标类！")
        sys.exit(1)

    for d_name, r_list in dex_to_rules.items():
        log("INFO", f"-> 分包 [{d_name}] 命中 {len(r_list)} 条规则")

    t_phase2 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 2 耗时: {t_phase2}s")

    t0 = time.time()
    log("INFO", f"3. 正在处理 Dex 分包 ({len(dex_to_rules)} 个)...")
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
        run_cmd(cmd)

    del dex_data_dict
    t_phase3 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 3 耗时: {t_phase3}s")

    t0 = time.time()
    log("INFO", "4. 正在打包 APK...")
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

    if helper_dex_path and os.path.exists(helper_dex_path):
        target_helper = os.path.join(inject_dir, next_dex_name)
        shutil.copyfile(helper_dex_path, target_helper)
        zip_args.append(shlex.quote(next_dex_name))

    if bsh_standalone_dex and os.path.exists(bsh_standalone_dex):
        target_assets_dir = os.path.join(inject_dir, "assets")
        os.makedirs(target_assets_dir, exist_ok=True)
        shutil.copyfile(bsh_standalone_dex, os.path.join(target_assets_dir, "bsh.dex"))
        zip_args.append(shlex.quote("assets/bsh.dex"))

    custom_icon_path = "assets/zzz_icon.png"
    if os.path.exists(custom_icon_path):
        target_assets_dir = os.path.join(inject_dir, "assets")
        os.makedirs(target_assets_dir, exist_ok=True)
        shutil.copyfile(custom_icon_path, os.path.join(target_assets_dir, "zzz_icon.png"))
        zip_args.append(shlex.quote("assets/zzz_icon.png"))

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
