# -*- coding: utf-8 -*-
"""
Patch 规则定义文件
"""

import zipfile
import os
import subprocess
import re
import shutil
import struct
import shlex

RULES = [
    # 规则 1：防撤回核心拦截
    {
        "name": "防撤回核心拦截 (onMsfPush)",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;",
        "target_method": "onMsfPush(Ljava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V",
        "type": "REPLACE",
        "smali": """
.method public onMsfPush(Ljava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V
    .registers 10

    # === [AntiRevoke Patch] ===
    invoke-static {p0, p1, p2}, Lcom/tencent/qqnt/patch/AntiRevokeHelper;->handleMsfPush(Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession;Ljava/lang/String;[B)[B
    move-result-object p2

    if-nez p2, :cond_pass
    return-void

    :cond_pass
    iget-wide v1, p0, Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;->nativeRef:J
    move-object v0, p0
    move-object v3, p1
    move-object v4, p2
    move-object v5, p3
    invoke-direct/range {v0 .. v5}, Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;->native_onMsfPush(JLjava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V
    return-void
.end method
"""
    },
    # 规则 2：QQ 原生二级设置页面挂载
    {
        "name": "QQ 原生二级设置页面挂载",
        "target_class": "Lcom/tencent/mobileqq/setting/generalSetting/GeneralSettingFragment;",
        "target_method": "onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    # === [Zzz Native Setting Hook] ===
    move-object/16 v0, p0
    move-object/16 v1, p1
    move-object/16 v2, p2
    invoke-static {v0, v1, v2}, Lcom/tencent/qqnt/patch/ZzzSettingFragment;->onHijackViewCreated(Ljava/lang/Object;Landroid/view/View;Landroid/os/Bundle;)Z
    move-result v0
    if-eqz v0, :cond_orig_general
    return-void
    :cond_orig_general
"""
    },
    # 规则 3：喵喵助手 (发送消息拦截与文字替换)
    {
        "name": "喵喵助手 (sendMsg)",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgService$CppProxy;",
        "target_method": "sendMsg(JLcom/tencent/qqnt/kernelpublic/nativeinterface/Contact;Ljava/util/ArrayList;Ljava/util/HashMap;Lcom/tencent/qqnt/kernel/nativeinterface/IOperateCallback;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    # === [Meow Helper Hook] ===
    move-object/16 v0, p4
    invoke-static {v0}, Lcom/tencent/qqnt/patch/MeowHelper;->handleSendMsg(Ljava/util/ArrayList;)V
"""
    }
]

def find_main_setting_config_class(dex_bytes):
    if len(dex_bytes) < 0x70 or dex_bytes[:4] != b'dex\n':
        return None
    if b'Lcom/tencent/mobileqq/setting/processor/SettingConfigProvider;' not in dex_bytes:
        return None

    try:
        string_ids_off = struct.unpack_from('<I', dex_bytes, 0x3C)[0]
        type_ids_size, type_ids_off = struct.unpack_from('<II', dex_bytes, 0x40)
        class_defs_size, class_defs_off = struct.unpack_from('<II', dex_bytes, 0x60)

        def get_type_str(type_idx):
            if type_idx >= type_ids_size: return ""
            desc_idx = struct.unpack_from('<I', dex_bytes, type_ids_off + type_idx * 4)[0]
            str_off = struct.unpack_from('<I', dex_bytes, string_ids_off + desc_idx * 4)[0]
            p = str_off
            while dex_bytes[p] & 0x80:
                p += 1
            p += 1
            end = dex_bytes.find(b'\x00', p)
            return dex_bytes[p:end].decode('utf-8', errors='ignore')

        target_super = "Lcom/tencent/mobileqq/setting/processor/SettingConfigProvider;"
        for i in range(class_defs_size):
            super_idx = struct.unpack_from('<I', dex_bytes, class_defs_off + i * 32 + 8)[0]
            if super_idx < type_ids_size and get_type_str(super_idx) == target_super:
                class_idx = struct.unpack_from('<I', dex_bytes, class_defs_off + i * 32)[0]
                cls_name = get_type_str(class_idx)
                if cls_name.startswith("Lcom/tencent/mobileqq/setting/main/"):
                    return cls_name
    except Exception:
        pass
    return None

def get_dynamic_setting_rule(apk_path, baksmali_bin, work_dir):
    config_dex = None
    config_class = None
    item_dex = None

    with zipfile.ZipFile(apk_path, 'r') as zf:
        dex_files = [f for f in zf.namelist() if re.match(r'^classes\d*\.dex$', f)]
        for dex in dex_files:
            data = zf.read(dex)
            
            if not config_class:
                found_cls = find_main_setting_config_class(data)
                if found_cls:
                    config_class = found_cls
                    config_dex = dex
            
            if not item_dex and b'SimpleItemProcessor' in data:
                item_dex = dex

    if not config_class or not config_dex or not item_dex:
        return None

    scan_dir = os.path.join(work_dir, "dynamic_setting_scan")
    os.makedirs(scan_dir, exist_ok=True)

    target_dexes = set([config_dex, item_dex])
    with zipfile.ZipFile(apk_path, 'r') as zf:
        for dex in target_dexes:
            dex_out_path = os.path.join(scan_dir, dex)
            with open(dex_out_path, "wb") as f:
                f.write(zf.read(dex))
            smali_out = os.path.join(scan_dir, f"smali_{dex}")
            subprocess.run(
                f"{baksmali_bin} d {shlex.quote(dex_out_path)} -o {shlex.quote(smali_out)}",
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=True
            )

    target_method = None
    item_class = None

    for root, _, files in os.walk(scan_dir):
        for f in files:
            if not f.endswith('.smali'):
                continue
            path = os.path.join(root, f)
            with open(path, 'r', encoding='utf-8') as file_obj:
                content = file_obj.read()

                if 'SimpleItemProcessor' in content and not item_class:
                    m = re.search(r'\.class.+?(L[\w/]+;)', content)
                    if m:
                        item_class = m.group(1).replace('/', '.')[1:-1]

                cls_descriptor = config_class[1:-1] + ".smali"
                if path.endswith(cls_descriptor) and not target_method:
                    m_method = re.search(r'\.method.+?(\w+)\(Landroid/content/Context;\)Ljava/util/List;', content)
                    if m_method:
                        target_method = f"{m_method.group(1)}(Landroid/content/Context;)Ljava/util/List;"

    shutil.rmtree(scan_dir, ignore_errors=True)

    if config_class and target_method and item_class:
        smali_hook = f"""
    move-object/16 v0, \\1
    move-object/16 v1, p1
    const-string v2, "{item_class}"
    invoke-static {{v1, v0, v2}}, Lcom/tencent/qqnt/patch/SettingInjector;->inject(Landroid/content/Context;Ljava/util/List;Ljava/lang/String;)V
    return-object v0"""

        return {
            "name": f"设置中心动态注入 ({config_class})",
            "target_class": config_class,
            "target_method": target_method,
            "type": "REGEX_REPLACE",
            "regex": r"return-object\s+([vp]\d+)(?=\s*(?:\.end\s+method|$))",
            "smali": smali_hook
        }

    return None