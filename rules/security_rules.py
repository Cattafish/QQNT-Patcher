# -*- coding: utf-8 -*-
"""全套反外挂、防篡改、风控对齐规则 (动态感知 SsoEventReport 零警告版)"""

import struct
from .stubs import stub_void, stub_ret
from .parser import FastDexParser

def build_security_rules(dex_data_dict):
    sec_rules = []
    parsers = [FastDexParser(v) for v in dex_data_dict.values() if len(v) >= 0x70 and v[:4] == b'dex\n']

    # 1. 主查签 A (ctsz.m) -> 掏空 d() 与 a()
    for p in parsers:
        for cls_name, cls_idx in p.find_classes_referencing_type_strictly("Lcom/tencent/ims/signature$SignatureReport;"):
            for m_name, proto, _, _, _ in p.get_class_methods(cls_idx):
                if proto == "()V" and m_name not in ("<init>", "<clinit>"):
                    sec_rules.append(stub_void(cls_name, f"{m_name}()V", name=f"致盲主查签 A ({cls_name}->{m_name})"))
                elif proto == "(Ljava/lang/Object;Ljava/lang/Object;)V":
                    sec_rules.append(stub_void(cls_name, f"{m_name}(Ljava/lang/Object;Ljava/lang/Object;)V", regs=3, name=f"阻断查签回调 A ({cls_name}->{m_name})"))

    # 2. 主查签 B (mezs.a) -> 掏空 d(Z) 与 b(String)
    for p in parsers:
        for cls_name, cls_idx in p.find_classes_referencing_string_strictly("SecMd5Entry"):
            for m_name, proto, _, _, _ in p.get_class_methods(cls_idx):
                if proto == "(Z)V":
                    sec_rules.append(stub_void(cls_name, f"{m_name}(Z)V", regs=2, name=f"致盲主查签 B ({cls_name}->{m_name})"))
                elif proto == "(Ljava/lang/String;)V":
                    sec_rules.append(stub_void(cls_name, f"{m_name}(Ljava/lang/String;)V", regs=2, name=f"阻断主查签 B 回调 ({cls_name}->{m_name})"))

    # 3. 完整性打击器 (MSFIntChkStrike) -> 掏空 exec
    for p in parsers:
        for cls_name, cls_idx in p.find_classes_referencing_string_strictly("strike_result"):
            for m_name, proto, _, _, _ in p.get_class_methods(cls_idx):
                if "secsrv" in proto:
                    sec_rules.append(stub_void(cls_name, f"{m_name}{proto}", regs=3, name=f"阻断完整性打击惩罚 ({cls_name}->{m_name})"))

    # 4. 动态嗅探云控账号获取方法
    united_a_cls = "Lcom/tencent/mobileqq/unitedconfig_android/api/impl/UnitedConfigManagerImpl$a;"
    for p in parsers:
        c_idx = p.find_class_index(united_a_cls)
        if c_idx != -1:
            for m_name, proto, _, _, _ in p.get_class_methods(c_idx):
                if proto == "()Ljava/lang/String;" and m_name not in ("<init>", "<clinit>"):
                    sec_rules.append(stub_ret(united_a_cls, f"{m_name}()Ljava/lang/String;", 'const-string v0, ""\n    return-object v0', name=f"脱敏云控账号参数 ({m_name})"))
            break

    # 5. 动态对齐 QSecConfig 设备与账号指纹脱敏
    qsec_cfg_cls = "Lcom/tencent/mobileqq/qsec/qsecurity/QSecConfig;"
    qsec_proto = "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
    for p in parsers:
        c_idx = p.find_class_index(qsec_cfg_cls)
        if c_idx != -1:
            for m_name, proto, _, _, _ in p.get_class_methods(c_idx):
                if proto == qsec_proto:
                    sec_rules.append({
                        "name": "脱敏 QSec 设备指纹并注入假 UIN",
                        "target_class": qsec_cfg_cls,
                        "target_method": f"{m_name}{proto}",
                        "type": "REPLACE",
                        "smali": f""".method public static {m_name}{proto}
    .registers 7
    sput-object p0, Lcom/tencent/mobileqq/qsec/qsecurity/QSecConfig;->sContext:Landroid/content/Context;
    const-string p1, "12345678901"
    sput-object p1, Lcom/tencent/mobileqq/qsec/qsecurity/QSecConfig;->business_uin:Ljava/lang/String;
    return-void
.end method"""
                    })
            break

    # ★ 6. 动态嗅探 ChannelReport 中上报 SsoEventReport 的真实方法 (自适应不同混淆名)
    chan_rep_cls = "Lcom/tencent/mobileqq/channel/ChannelReport;"
    for p in parsers:
        c_idx = p.find_class_index(chan_rep_cls)
        if c_idx != -1:
            sso_event_id = p.find_string_id("trpc.o3.report.Report.SsoEventReport")
            if sso_event_id != -1:
                s_16 = struct.pack('<H', sso_event_id) if sso_event_id <= 65535 else None
                s_32 = struct.pack('<I', sso_event_id)
                for m_name, proto, is_virt, code_off, access_flags in p.get_class_methods(c_idx):
                    if code_off != 0 and code_off + 16 < len(p.data):
                        insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
                        insns = p.data[code_off + 16: code_off + 16 + insns_size * 2]
                        if (s_16 and s_16 in insns) or (s_32 in insns):
                            is_static = bool(access_flags & 0x0008)
                            is_priv = bool(access_flags & 0x0002)
                            sec_rules.append(stub_void(
                                chan_rep_cls,
                                f"{m_name}{proto}",
                                is_static=is_static,
                                is_private=is_priv,
                                regs=4,
                                name=f"动态阻断通道事件上报 ({m_name})"
                            ))
            break

    # 7. 静态精确桩化规则
    sec_rules.extend([
        # 启动自检 -> return 7 (DONE)
        stub_ret("Lcom/tencent/mobileqq/app/automator/step/SignatureScan;", "doStep()I", "const/4 v0, 0x7\n    return v0", name="旁路启动自检 SignatureScan"),
        stub_ret("Lcom/tencent/mobileqq/app/automator/step/CheckSafeCenterConfig;", "doStep()I", "const/4 v0, 0x7\n    return v0", name="旁路启动自检 CheckSafeCenterConfig"),
        
        # 风控信道 & 本地安全扫描 -> return false
        stub_ret("Lcom/tencent/mobileqq/dt/api/impl/QSecChannelImpl;", "reportEnable()Z", "const/4 v0, 0\n    return v0", name="强制关闭风控信道"),
        stub_ret("Lcom/tencent/mobileqq/app/QQAppInterface;", "isNeedSecurityScan()Z", "const/4 v0, 0\n    return v0", name="写死关闭本地安全扫描"),
        
        # 内存与代码扫描休眠 (unusedcodecheck)
        stub_void("Lcom/tencent/mobileqq/startup/task/p;", "run(Landroid/content/Context;)V", regs=2, name="休眠代码防篡改扫描"),
        stub_void("Lcom/tencent/mobileqq/perf/memory/dump/MemoryFile;", "b()V", is_private=True, regs=1, name="阻断内存篡改转储"),
        
        # 灯塔硬件指纹脱敏
        stub_void("Lcom/tencent/mobileqq/statistics/QQBeaconReport;", "setBeaconPrivacyInfo()V", is_static=True, regs=1, name="阻断灯塔 Beacon 硬件指纹收集"),
        
        # 通道风控单条与批量上报拦截 (SsoReport)
        stub_ret("Lcom/tencent/mobileqq/channel/ChannelReport;", "isReportOnceOfDay(Ljava/lang/String;)Z", "const/4 v0, 0\n    return v0", is_static=True, name="禁用通道风控周期判定"),
        stub_void("Lcom/tencent/mobileqq/channel/ChannelReport;", "commonReport(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V", regs=4, name="阻断通道单条风控上报"),
        stub_void("Lcom/tencent/mobileqq/channel/ChannelReport;", "batchCommonReport(Ljava/lang/String;[Ljava/lang/String;[[Ljava/lang/String;)V", regs=4, name="阻断通道批量风控上报"),
        
        # 安装包物理路径脱敏
        stub_ret("Lcom/tencent/mobileqq/app/qfix/ApplicationDelegate;", "getPackageCodePath()Ljava/lang/String;", "const/4 v0, 0\n    return-object v0", name="脱敏安装包物理路径"),
        
        # 极化云控更新频率 (严格 private 防止 invoke-direct 崩溃)
        stub_ret("Lcom/tencent/mobileqq/unitedconfig_android/api/impl/UnitedConfigManagerImpl;", "getUpdateInterval()J", "const-wide v0, 0xc92a69c000L\n    return-wide v0", is_private=True, regs=3, name="极化统一云控拉取间隔"),
        
        # 阻断密码安全配置查询
        stub_ret("Lcom/tencent/mobileqq/app/identity/impl/SafeApiImpl;", "getUpdatePwdUrl(Ljava/lang/String;)Ljava/lang/String;", "const/4 v0, 0\n    return-object v0", regs=3, name="阻断安全中心密码配置查询"),
        
        # 极化 AntEst 定时器 (排队 270 年彻底休眠)
        {
            "name": "极化 AntEst 定时器",
            "target_class": "Lcom/tencent/qqprotect/xps/core/AntEst;",
            "target_method": "d",
            "type": "REGEX_REPLACE",
            "regex": r"const-wide/32\s+([vp]\d+),\s+0x5265c00",
            "smali": "const-wide \\1, 0x7dba8218000L"
        },
        
        # 全局静默敏感消息与涉诈本地扫描
        stub_void("Lcom/tencent/mqp/app/sec/c;", "i(Lcom/tencent/mobileqq/data/MessageRecord;Ljava/util/List;Z[B)V", is_static=True, regs=4, name="全局静默敏感消息涉诈扫描"),

        # FEKit 内部风控采集门限阈值归零
        {
            "name": "致盲 FEKit 采集门限判定 (fe/d)",
            "target_class": "Lcom/tencent/mobileqq/fe/d;",
            "target_method": "a",
            "type": "REGEX_REPLACE",
            "regex": r"const/4\s+([vp]\d+),\s+0x1(?=\s*?(?:invoke-virtual|\n\s*?return))",
            "smali": "const/4 \\1, 0x0"
        }
    ])

    return sec_rules