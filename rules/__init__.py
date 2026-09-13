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

def get_dynamic_group_file_rules(dex_data_dict):
    rules_list = []

    # =========================================================================
    # 环节 ①：在 GroupFileListRepo 收到网络回包入口 (k 和 l) 拦截 lg4.j
    # =========================================================================
    repo_cls = "Lgroup_file/group_file_common/repo/GroupFileListRepo;"
    repo_proto = "(Ljava/lang/String;Llg4/j;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"

    for m_name in ["k", "l"]:
        rules_list.append({
            "name": f"群文件回包拦截 (GroupFileListRepo->{m_name})",
            "target_class": repo_cls,
            "target_method": f"{m_name}{repo_proto}",
            "type": "INSERT_BEFORE",
            "smali": """
    move-object/16 v0, p2
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->handleGroupFileListResponse(Ljava/lang/Object;)V
"""
        })

    # =========================================================================
    # 环节 ②：动态嗅探群文件 UI 状态文字渲染点 (优先 9.3.60+，兼容 9.2.90)
    # =========================================================================
    status_rule_added = False
    for _, dex_bytes in dex_data_dict.items():
        # A. 适配 9.3.60+ 架构: GroupFileCellSlotProvider->o (入参含 components/e 且返回 qd3/a)
        if b"GroupFileCellSlotProvider" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if p.valid:
                c_idx = p.find_class_index("Lgroup_file/group_file_common/abstraction/slot/GroupFileCellSlotProvider;")
                if c_idx != -1:
                    for m_name, proto_desc, _, _, _ in p.get_class_methods(c_idx):
                        if "Lqqfile_common/components/e;" in proto_desc and proto_desc.endswith(")Lqd3/a;"):
                            target_method = f"{m_name}{proto_desc}"
                            rules_list.append({
                                "name": f"群文件状态文字构造前追加下载次数 (GroupFileCellSlotProvider->{m_name})",
                                "target_class": "Lgroup_file/group_file_common/abstraction/slot/GroupFileCellSlotProvider;",
                                "target_method": target_method,
                                "type": "REGEX_REPLACE",
                                "regex": r"(invoke-static/range\s+\{[^}]+\},\s+Lkotlin/collections/CollectionsKt;->joinToString\$default\([^)]+\)Ljava/lang/String;\s+move-result-object\s+v2)",
                                "smali": r"""\1
    move-object/16 v8, p1
    invoke-static {v2, v8}, Lcom/tencent/qqnt/patch/PatchBridge;->appendDownloadCountToStatusText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
    move-result-object v2"""
                            })
                            status_rule_added = True
                            break
        if status_rule_added:
            break

        # B. 兼容 9.2.90 架构: qqfile_common/components/f->i
        if not status_rule_added and b"qqfile_common/components/f" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if p.valid:
                c_idx = p.find_class_index("Lqqfile_common/components/f;")
                if c_idx != -1:
                    for m_name, proto_desc, _, _, _ in p.get_class_methods(c_idx):
                        if "ZLqqfile_common/data/a;)" in proto_desc:
                            target_method = f"{m_name}{proto_desc}"
                            rules_list.append({
                                "name": f"群文件状态文字构造前追加下载次数 (qqfile_common/components/f->{m_name})",
                                "target_class": "Lqqfile_common/components/f;",
                                "target_method": target_method,
                                "type": "REGEX_REPLACE",
                                "regex": r"(invoke-static/range\s+\{[^}]+\},\s+Lkotlin/collections/CollectionsKt;->joinToString\$default\([^)]+\)Ljava/lang/String;\s+move-result-object\s+v0)",
                                "smali": r"""\1
    move-object/16 v8, p0
    invoke-static {v0, v8}, Lcom/tencent/qqnt/patch/PatchBridge;->appendDownloadCountToStatusText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
    move-result-object v0"""
                            })
                            status_rule_added = True
                            break
        if status_rule_added:
            break

    return rules_list