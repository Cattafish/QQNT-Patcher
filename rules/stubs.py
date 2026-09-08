# -*- coding: utf-8 -*-
"""Smali 桩代码工厂函数 (支持精准访问修饰符匹配，防 IncompatibleClassChangeError)"""

def stub_void(cls, method, is_static=False, is_private=False, regs=1, name=""):
    if is_static:
        acc = "public static"
    elif is_private:
        acc = "private"
    else:
        acc = "public"
    return {
        "name": name or f"掏空 {cls}->{method.split('(')[0]}",
        "target_class": cls,
        "target_method": method,
        "type": "REPLACE",
        "smali": f".method {acc} {method}\n    .registers {regs}\n    return-void\n.end method"
    }

def stub_ret(cls, method, ret_insn, is_static=False, is_private=False, regs=2, name=""):
    if is_static:
        acc = "public static"
    elif is_private:
        acc = "private"
    else:
        acc = "public"
    return {
        "name": name or f"重定向 {cls}->{method.split('(')[0]}",
        "target_class": cls,
        "target_method": method,
        "type": "REPLACE",
        "smali": f".method {acc} {method}\n    .registers {regs}\n    {ret_insn}\n.end method"
    }