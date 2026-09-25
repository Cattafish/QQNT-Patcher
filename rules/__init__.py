# -*- coding: utf-8 -*-
"""rules 统一入口：纯路由导出层"""

from .base_rules import BASE_RULES
from .security_rules import build_security_rules
from .setting_rules import build_setting_rule
from .tablet_rules import build_tablet_rule
from .group_file_rules import build_group_file_rules
from .troop_todo_rules import build_troop_todo_rule
from .parser import FastDexParser

RULES = list(BASE_RULES)

# 统一对外方法别名（保持 patcher.py 无缝调用）
get_dynamic_security_rules = build_security_rules
get_dynamic_setting_rule_fast = build_setting_rule
get_dynamic_tablet_rule_fast = build_tablet_rule
get_dynamic_group_file_rules = build_group_file_rules
get_dynamic_troop_todo_rule = build_troop_todo_rule
