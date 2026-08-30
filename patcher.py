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
import urllib.request
import rules

TOOLS_DIR = os.path.abspath("./tools")
BAKSMALI_JAR = os.path.join(TOOLS_DIR, "baksmali.jar")
SMALI_JAR = os.path.join(TOOLS_DIR, "smali.jar")

def log(tag, msg):
    colors = {
        "INFO": "\033[1;34m[INFO]\033[0m",
        "OK": "\033[1;32m[SUCCESS]\033[0m",
        "WARN": "\033[1;33m[WARN]\033[0m",
        "ERR": "\033[1;31m[ERROR]\033[0m"
    }
    print(f"{colors.get(tag, '[*]')} {msg}")

def run_cmd(cmd, cwd=None):
    ret = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=cwd)
    if ret.returncode != 0:
        log("ERR", f"命令执行失败: {cmd}")
        log("ERR", ret.stderr.decode('utf-8', errors='ignore'))
        log("ERR", ret.stdout.decode('utf-8', errors='ignore'))
        sys.exit(1)
    return ret.stdout.decode('utf-8', errors='ignore')

def download_file(url, path):
    log("INFO", f"正在下载组件: {os.path.basename(path)} ...")
    headers = {'User-Agent': 'Mozilla/5.0'}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response, open(path, 'wb') as out_file:
        shutil.copyfileobj(response, out_file)

def ensure_smali_jars():
    os.makedirs(TOOLS_DIR, exist_ok=True)
    urls = {
        BAKSMALI_JAR: [
            "https://ghproxy.net/https://github.com/baksmali/smali/releases/download/v3.0.8/baksmali-3.0.8-fat.jar",
            "https://github.com/baksmali/smali/releases/download/v3.0.8/baksmali-3.0.8-fat.jar",
            "https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar"
        ],
        SMALI_JAR: [
            "https://ghproxy.net/https://github.com/baksmali/smali/releases/download/v3.0.8/smali-3.0.8-fat.jar",
            "https://github.com/baksmali/smali/releases/download/v3.0.8/smali-3.0.8-fat.jar",
            "https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar"
        ]
    }
    for jar_path, mirrors in urls.items():
        if not os.path.exists(jar_path):
            success = False
            for url in mirrors:
                try:
                    download_file(url, jar_path)
                    if os.path.exists(jar_path) and os.path.getsize(jar_path) > 100000:
                        success = True
                        log("OK", f"下载完成: {os.path.basename(jar_path)}")
                        break
                except Exception:
                    if os.path.exists(jar_path):
                        os.remove(jar_path)
            if not success:
                log("ERR", f"自动下载 {os.path.basename(jar_path)} 失败，请检查网络或手动下载放入 {TOOLS_DIR} 目录")
                sys.exit(1)

def get_baksmali_cmd():
    if shutil.which("baksmali"):
        return "baksmali"
    ensure_smali_jars()
    return f"java -jar {shlex.quote(BAKSMALI_JAR)}"

def get_smali_cmd():
    if shutil.which("smali"):
        return "smali"
    ensure_smali_jars()
    return f"java -jar {shlex.quote(SMALI_JAR)}"

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
            while dex_bytes[p] & 0x80:
                p += 1
            p += 1

            end = dex_bytes.find(b'\x00', p)
            if end != -1:
                classes.add(dex_bytes[p:end].decode('utf-8', errors='ignore'))
        return classes
    except Exception:
        return set()

def compile_helper_dex(work_dir):
    src_dir = "./src"
    if not os.path.exists(src_dir):
        log("WARN", "未检测到 src/ 源码目录，跳过 Helper Dex 编译")
        return None

    log("INFO", "1. 正在编译 src/ 目录下的 Java 源码...")
    bin_dir = os.path.join(work_dir, "bin")
    dex_out = os.path.join(work_dir, "dex_out")
    os.makedirs(bin_dir, exist_ok=True)
    os.makedirs(dex_out, exist_ok=True)

    java_files = []
    for root, _, files in os.walk(src_dir):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))

    if not java_files:
        log("WARN", "src/ 下没有 .java 文件，跳过编译")
        return None

    quoted_java = [shlex.quote(f) for f in java_files]
    run_cmd(f"javac -d {shlex.quote(bin_dir)} " + " ".join(quoted_java))
    
    patch_classes = []
    for root, _, files in os.walk(os.path.join(bin_dir, "com/tencent/qqnt/patch")):
        for f in files:
            if f.endswith(".class"):
                patch_classes.append(os.path.join(root, f))

    if not patch_classes:
        patch_classes = [bin_dir]

    quoted_classes = [shlex.quote(f) for f in patch_classes]
    run_cmd(f"d8 --output {shlex.quote(dex_out)} " + " ".join(quoted_classes))
    
    target_dex = os.path.join(dex_out, "classes.dex")
    if os.path.exists(target_dex):
        log("OK", "Helper Dex 编译成功！")
        return target_dex
    return None

def apply_patch_to_smali(smali_path, rule):
    with open(smali_path, "r", encoding="utf-8") as f:
        code = f.read()

    method_name = rule["target_method"]
    patch_type = rule["type"]
    patch_smali = rule["smali"].strip()

    # 支持精确方法名或者多构造函数全量匹配
    if "(" in method_name:
        escaped_method = re.escape(method_name)
        pattern = re.compile(rf'(\.method[^\n]*\s+{escaped_method}\s*?\n.*?\.end method)', re.DOTALL)
    elif method_name == "<init>":
        pattern = re.compile(r'(\.method[^\n]*\s+<init>\([^\n]*\)\w*?\s*?\n.*?\.end method)', re.DOTALL)
    else:
        escaped_method = re.escape(method_name)
        pattern = re.compile(rf'(\.method[^\n]*\s+{escaped_method}\b.*?\.end method)', re.DOTALL)

    if not pattern.search(code):
        log("ERR", f"在 {os.path.basename(smali_path)} 中未找到目标方法: {method_name}")
        return False

    if patch_type == "REPLACE":
        code = pattern.sub(patch_smali, code)
    elif patch_type == "INSERT_BEFORE":
        def repl(match):
            m_body = match.group(1)
            header_match = re.search(r'(\.registers\s+\d+|\.locals\s+\d+)', m_body)
            if header_match:
                idx = header_match.end()
                return m_body[:idx] + "\n" + patch_smali + "\n" + m_body[idx:]
            return m_body
        code = pattern.sub(repl, code)
    elif patch_type == "REGEX_REPLACE":
        def repl_regex(match):
            m_body = match.group(1)
            return re.sub(rule["regex"], patch_smali, m_body)
        code = pattern.sub(repl_regex, code)

    with open(smali_path, "w", encoding="utf-8") as f:
        f.write(code)
    
    log("OK", f"  ✔ 成功应用规则: [{rule['name']}]")
    return True

def main():
    input_apk = sys.argv[1] if len(sys.argv) > 1 else "QQ.apk"
    output_apk = sys.argv[2] if len(sys.argv) > 2 else "QQ_Patched.apk"

    if not os.path.exists(input_apk):
        log("ERR", f"未找到输入 APK 文件: {input_apk}")
        sys.exit(1)

    work_dir = "./build_cache"
    if os.path.exists(work_dir):
        shutil.rmtree(work_dir)
    os.makedirs(work_dir, exist_ok=True)

    baksmali_bin = get_baksmali_cmd()
    smali_bin = get_smali_cmd()

    helper_dex_path = compile_helper_dex(work_dir)

    log("INFO", f"2. 正在扫描 {input_apk} 结构与 Dex 分包...")
    dex_list = []
    with zipfile.ZipFile(input_apk, 'r') as zf:
        for name in zf.namelist():
            if re.match(r'^classes\d*\.dex$', name):
                dex_list.append(name)

    def dex_index(name):
        if name == "classes.dex": return 1
        m = re.match(r'classes(\d+)\.dex', name)
        return int(m.group(1)) if m else 0

    dex_list.sort(key=dex_index)
    max_idx = dex_index(dex_list[-1])
    next_dex_name = f"classes{max_idx + 1}.dex"
    log("INFO", f"-> 现有 {len(dex_list)} 个 Dex (最大为 classes{max_idx}.dex)，新增 Dex 分配为: {next_dex_name}")

    log("INFO", "3. 正在动态探测与定位修改规则...")
    all_rules = list(rules.RULES)
    
    dyn_setting_rule = rules.get_dynamic_setting_rule(input_apk, baksmali_bin, work_dir)
    if dyn_setting_rule:
        all_rules.append(dyn_setting_rule)
        log("OK", f"-> 成功动态生成规则: [{dyn_setting_rule['name']}]")
    else:
        log("WARN", "-> 未检测到设置中心特征")

    dex_to_rules = {}
    with zipfile.ZipFile(input_apk, 'r') as zf:
        for dex_name in dex_list:
            dex_bytes = zf.read(dex_name)
            defined_classes = get_defined_classes_in_dex(dex_bytes)
            
            for rule in all_rules:
                if rule["target_class"] in defined_classes:
                    dex_to_rules.setdefault(dex_name, []).append(rule)

    if not dex_to_rules:
        log("ERR", "所有规则中的目标类均未在 APK 中找到！")
        sys.exit(1)

    for d_name, r_list in dex_to_rules.items():
        log("INFO", f"-> 分包 [{d_name}] 精准命中 {len(r_list)} 条修改规则")

    modified_dex_files = []
    with zipfile.ZipFile(input_apk, 'r') as zf:
        for dex_name, r_list in dex_to_rules.items():
            log("INFO", f"4. 正在处理分包: {dex_name} ...")
            dex_raw_path = os.path.join(work_dir, dex_name)
            with open(dex_raw_path, "wb") as f:
                f.write(zf.read(dex_name))

            smali_out = os.path.join(work_dir, f"smali_{dex_name}")
            run_cmd(f"{baksmali_bin} d {shlex.quote(dex_raw_path)} -o {shlex.quote(smali_out)}")

            for rule in r_list:
                cls_path = rule["target_class"][1:-1] + ".smali"
                full_smali_path = os.path.join(smali_out, cls_path)
                if os.path.exists(full_smali_path):
                    apply_patch_to_smali(full_smali_path, rule)

            patched_dex = os.path.join(work_dir, f"patched_{dex_name}")
            run_cmd(f"{smali_bin} a {shlex.quote(smali_out)} -o {shlex.quote(patched_dex)}")
            
            if not os.path.exists(patched_dex):
                log("ERR", f"汇编分包 {dex_name} 失败，未生成 Dex 文件！")
                sys.exit(1)
                
            modified_dex_files.append((patched_dex, dex_name))

    log("INFO", "5. 正在注入已修补的 Dex、扩展 Dex 与自定义 Assets ...")
    shutil.copyfile(input_apk, output_apk)

    inject_dir = os.path.join(work_dir, "inject")
    os.makedirs(inject_dir, exist_ok=True)

    zip_args = []
    for local_path, in_zip_name in modified_dex_files:
        target_in_dir = os.path.join(inject_dir, in_zip_name)
        shutil.copyfile(local_path, target_in_dir)
        zip_args.append(shlex.quote(in_zip_name))

    if helper_dex_path:
        target_helper = os.path.join(inject_dir, next_dex_name)
        shutil.copyfile(helper_dex_path, target_helper)
        zip_args.append(shlex.quote(next_dex_name))

    custom_icon_path = "assets/zzz_icon.png"
    if os.path.exists(custom_icon_path):
        target_assets_dir = os.path.join(inject_dir, "assets")
        os.makedirs(target_assets_dir, exist_ok=True)
        shutil.copyfile(custom_icon_path, os.path.join(target_assets_dir, "zzz_icon.png"))
        zip_args.append(shlex.quote("assets/zzz_icon.png"))
        log("OK", "已附加自定义图标资源: assets/zzz_icon.png")

    abs_output_apk = os.path.abspath(output_apk)
    run_cmd(f"cd {shlex.quote(inject_dir)} && zip -q -u {shlex.quote(abs_output_apk)} " + " ".join(zip_args))

    log("INFO", "6. 正在对 APK 进行签名 ...")
    keystore = os.path.join(work_dir, "debug.keystore")
    if not os.path.exists(keystore):
        run_cmd(f"keytool -genkey -v -keystore {shlex.quote(keystore)} -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'")

    run_cmd(f"apksigner sign --ks {shlex.quote(keystore)} --ks-pass pass:android --key-pass pass:android {shlex.quote(output_apk)}")

    shutil.rmtree(work_dir)
    log("OK", f"🎉 全部完成！输出文件: {output_apk}")

if __name__ == "__main__":
    main()