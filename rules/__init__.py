# -*- coding: utf-8 -*-
"""rules 包入口：无缝对接 patcher.py"""

from .base_rules import BASE_RULES
from .security_rules import build_security_rules
from .parser import FastDexParser

RULES = list(BASE_RULES)

def get_dynamic_security_rules(dex_data_dict, orig_apk_md5="", orig_sig_md5=""):
    """提取全套动态安全穿透规则 (支持动态回填官方指纹)"""
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
    """
    全动态嗅探 PadUtil 设备类型判断类与方法 (自适应类名/方法名/枚举类型混淆)
    """
    for _, dex_bytes in dex_data_dict.items():
        # 快速特征预检
        if b"initDeviceType type = " in dex_bytes or b"key_common_split_switch" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            # 1. 动态嗅探引用特征日志字符串的目标类 (无论是 PadUtil 还是混淆后的 a/b/c 类)
            candidate_classes = p.find_classes_referencing_string_strictly("initDeviceType type = ")
            if not candidate_classes:
                candidate_classes = p.find_classes_referencing_string_strictly("key_common_split_switch")

            for cls_name, cls_idx in candidate_classes:
                methods = p.get_class_methods(cls_idx)
                for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                    # 2. 匹配 static 方法，且原型为 (Landroid/content/Context;)L<DeviceType>;
                    is_static = bool(access_flags & 0x0008)
                    if is_static and proto_desc.startswith("(Landroid/content/Context;)L"):
                        return_type = proto_desc.split(')')[1]  # 动态拿到 DeviceType 枚举类的完整混淆描述符
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