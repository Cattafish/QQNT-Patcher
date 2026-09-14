# -*- coding: utf-8 -*-
"""rules 包入口：无缝对接 patcher.py"""

import struct
from .base_rules import BASE_RULES
from .security_rules import build_security_rules
from .parser import FastDexParser

RULES = list(BASE_RULES)

def get_dynamic_security_rules(dex_data_dict, orig_apk_md5="", orig_sig_md5=""):
    """提取全套动态安全穿透规则"""
    return build_security_rules(dex_data_dict, orig_apk_md5=orig_apk_md5, orig_sig_md5=orig_sig_md5)

def get_dynamic_setting_rule_fast(dex_data_dict):
    config_class, target_method, item_class = None, None, None
    for _, dex_bytes in dex_data_dict.items():
        if not (config_class and target_method) and b'SettingConfigProvider' in dex_bytes:
            p = FastDexParser(dex_bytes)
            if p.valid:
                cls, m = p.find_setting_config_info()
                if cls and m: config_class, target_method = cls, m

        if not item_class and b'SimpleItemProcessor' in dex_bytes:
            p = FastDexParser(dex_bytes)
            if p.valid:
                item = p.find_simple_item_class()
                if item: item_class = item

        if config_class and target_method and item_class: break

    if config_class and target_method and item_class:
        return {
            "name": f"设置中心动态挂载 ({config_class})",
            "target_class": config_class,
            "target_method": target_method,
            "type": "REGEX_REPLACE",
            "regex": r"return-object\s+([vp]\d+)(?=\s*(?:\.end\s+method|$))",
            "smali": f"""
    move-object/16 v0, \\1
    move-object/16 v1, p1
    const-string v2, "{item_class}"
    invoke-static {{v1, v0, v2}}, Lcom/tencent/qqnt/patch/SettingInjector;->inject(Landroid/content/Context;Ljava/util/List;Ljava/lang/String;)V
    return-object v0"""
        }
    return None

def get_dynamic_tablet_rule_fast(dex_data_dict):
    """全动态嗅探 PadUtil 设备类型判断类与方法"""
    for _, dex_bytes in dex_data_dict.items():
        if b"initDeviceType type = " in dex_bytes or b"key_common_split_switch" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue

            candidate_classes = p.find_classes_referencing_string_strictly("initDeviceType type = ")
            if not candidate_classes:
                candidate_classes = p.find_classes_referencing_string_strictly("key_common_split_switch")

            for cls_name, cls_idx in candidate_classes:
                methods = p.get_class_methods(cls_idx)
                for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                    is_static = bool(access_flags & 0x0008)
                    if is_static and proto_desc.startswith("(Landroid/content/Context;)L"):
                        return_type = proto_desc.split(')')[1]
                        target_method = f"{m_name}{proto_desc}"

                        return {
                            "name": f"强制平板模式动态穿透 ({cls_name}->{m_name})",
                            "target_class": cls_name,
                            "target_method": target_method,
                            "type": "INSERT_BEFORE",
                            "smali": f"""
    invoke-static {{}}, Lcom/tencent/qqnt/patch/PatchBridge;->isTabletModeEnabled()Z
    move-result v0
    if-eqz v0, :cond_tablet_pass
    sget-object v0, {return_type}->TABLET:{return_type}
    return-object v0
    :cond_tablet_pass
"""
                        }
    return None

def _method_has_const_string(p, code_off, s_id):
    """机器指令检查：方法是否真正执行了 const-string 加载指定字符串"""
    if s_id == -1 or code_off == 0 or code_off + 16 >= len(p.data):
        return False
    insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
    insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]
    s_16 = struct.pack('<H', s_id) if s_id <= 65535 else None
    s_32 = struct.pack('<I', s_id)
    k = 0
    len_insns = len(insns)
    while k < len_insns - 1:
        op = insns[k]
        if op == 0x1A and k + 4 <= len_insns:
            if s_16 and insns[k + 2 : k + 4] == s_16: return True
            k += 4
            continue
        elif op == 0x1B and k + 6 <= len_insns:
            if insns[k + 2 : k + 6] == s_32: return True
            k += 6
            continue
        k += 2
    return False

def _find_methods_calling_named_method(p, class_idx, target_method_name):
    """机器指令检查：从底层 Dalvik 机器码查找类中真正调用了 target_method_name 的方法"""
    s_id = p.find_string_id(target_method_name)
    if s_id == -1: return []
    
    target_m_indices = set()
    for m_idx in range(p.method_ids_size):
        name_idx = struct.unpack_from('<I', p.data, p.method_ids_off + m_idx * 8 + 4)[0]
        if name_idx == s_id:
            target_m_indices.add(m_idx)
            
    if not target_m_indices: return []
    
    matched = []
    for m_name, proto_desc, _, code_off, _ in p.get_class_methods(class_idx):
        if code_off == 0 or code_off + 16 >= len(p.data): continue
        insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
        insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]
        
        k = 0
        len_insns = len(insns)
        while k < len_insns - 3:
            op = insns[k]
            if op in (0x71, 0x77):  # invoke-static 或 invoke-static/range
                m_ref = struct.unpack_from('<H', insns, k + 2)[0]
                if m_ref in target_m_indices:
                    matched.append((m_name, proto_desc, code_off))
                    break
            k += 2
    return matched

def get_dynamic_group_file_rules(dex_data_dict):
    """
    全自动动态群文件下载次数规则引擎
    - 网络回包：动态挂钩 GroupFileListRepo 挂起函数
    - UI 渲染追加：双通道并存，哪个存在就挂哪个，绝不误抓，0 WARN
    """
    rules_list = []
    
    # =========================================================================
    # 1. 网络回包拦截 (GroupFileListRepo -> 查找打印 [syncExtraInfo] 的方法)
    # =========================================================================
    repo_cls = "Lgroup_file/group_file_common/repo/GroupFileListRepo;"
    for _, dex_bytes in dex_data_dict.items():
        if b"GroupFileListRepo" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            c_idx = p.find_class_index(repo_cls)
            if c_idx != -1:
                s_v1 = p.find_string_id("[syncExtraInfo]: start")
                s_v2 = p.find_string_id("[syncExtraInfoV2]: start")
                for m_name, proto_desc, _, code_off, _ in p.get_class_methods(c_idx):
                    if proto_desc.startswith("(Ljava/lang/String;L") and proto_desc.endswith(";Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"):
                        hit_v1 = _method_has_const_string(p, code_off, s_v1)
                        hit_v2 = _method_has_const_string(p, code_off, s_v2)
                        if hit_v1 or hit_v2:
                            rules_list.append({
                                "name": f"群文件回包拦截 (GroupFileListRepo->{m_name})",
                                "target_class": repo_cls,
                                "target_method": f"{m_name}{proto_desc}",
                                "type": "INSERT_BEFORE",
                                "smali": """
    move-object/16 v0, p2
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->handleGroupFileListResponse(Ljava/lang/Object;)V
"""
                            })

    # =========================================================================
    # 2. UI 渲染点 A: 检查 qqfile_common/components/f (9.2.90 主渲染通道)
    # =========================================================================
    old_f_cls = "Lqqfile_common/components/f;"
    for _, dex_bytes in dex_data_dict.items():
        if b"qqfile_common/components/f" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            c_idx = p.find_class_index(old_f_cls)
            if c_idx != -1:
                # 只找真正调用了 joinToString$default 的方法 (必定只命中 i，彻底排除 d)
                calling_methods = _find_methods_calling_named_method(p, c_idx, "joinToString$default")
                for m_name, proto_desc, _ in calling_methods:
                    if proto_desc.startswith("(Llr5/e;") or "ZLqqfile_common/data/a;)" in proto_desc:
                        rules_list.append({
                            "name": f"群文件状态文字追加 (components.f->{m_name})",
                            "target_class": old_f_cls,
                            "target_method": f"{m_name}{proto_desc}",
                            "type": "REGEX_REPLACE",
                            "regex": r"(invoke-static/range\s+\{[^}]+\},\s+Lkotlin/collections/CollectionsKt;->joinToString\$default\([^)]+\)Ljava/lang/String;\s+move-result-object\s+([vp]\d+))",
                            "smali": r"""\1
    move-object/16 v8, p0
    invoke-static {\2, v8}, Lcom/tencent/qqnt/patch/PatchBridge;->appendDownloadCountToStatusText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
    move-result-object \2"""
                        })
                        break

    # =========================================================================
    # 3. UI 渲染点 B: 检查 GroupFileCellSlotProvider (9.3.60+ 主渲染通道)
    # =========================================================================
    slot_cls = "Lgroup_file/group_file_common/abstraction/slot/GroupFileCellSlotProvider;"
    for _, dex_bytes in dex_data_dict.items():
        if b"GroupFileCellSlotProvider" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            c_idx = p.find_class_index(slot_cls)
            if c_idx != -1:
                # 只有真正调用了 joinToString$default 且入参为 Lqqfile_common/components/e 的方法 (精准锁定方法 o，排除 n)
                calling_methods = _find_methods_calling_named_method(p, c_idx, "joinToString$default")
                for m_name, proto_desc, _ in calling_methods:
                    if "Lqqfile_common/components/e;" in proto_desc:
                        rules_list.append({
                            "name": f"群文件状态文字追加 (GroupFileCellSlotProvider->{m_name})",
                            "target_class": slot_cls,
                            "target_method": f"{m_name}{proto_desc}",
                            "type": "REGEX_REPLACE",
                            "regex": r"(invoke-static/range\s+\{[^}]+\},\s+Lkotlin/collections/CollectionsKt;->joinToString\$default\([^)]+\)Ljava/lang/String;\s+move-result-object\s+([vp]\d+))",
                            "smali": r"""\1
    move-object/16 v8, p1
    invoke-static {\2, v8}, Lcom/tencent/qqnt/patch/PatchBridge;->appendDownloadCountToStatusText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
    move-result-object \2"""
                        })
                        break

    return rules_list