# -*- coding: utf-8 -*-
"""rules 包入口：无缝对接 patcher.py"""

from .base_rules import BASE_RULES
from .security_rules import build_security_rules
from .parser import FastDexParser

# 对外暴露 patcher.py 需要的基础规则列表
RULES = list(BASE_RULES)

def get_dynamic_security_rules(dex_data_dict):
    """提取全套动态安全穿透规则"""
    return build_security_rules(dex_data_dict)

def get_dynamic_setting_rule_fast(dex_data_dict):
    """动态探测并生成设置中心挂载规则"""
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