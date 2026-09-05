# -*- coding: utf-8 -*-
"""
Patch 规则定义文件 (含 AIO 双保险精准感知)
"""

import struct

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
    # 规则 3：喵喵助手 (sendMsg)
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
    },
    # 规则 4：AIO 气泡构造感知与闪照解密 (只要进入会话必调，100% 触发)
    {
        "name": "AIO 气泡构造感知 (AIOMsgItem)",
        "target_class": "Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;",
        "target_method": "<init>(Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    # === [AIOMsgItem Double Check Hook] ===
    invoke-static {p1}, Lcom/tencent/qqnt/patch/FlashPicHelper;->handleMsgRecord(Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;)V
    invoke-static {p1}, Lcom/tencent/qqnt/patch/plugin/FloatingBallManager;->onAIOMsgItemBind(Ljava/lang/Object;)V
"""
    },
    # 规则 5：闪照破解 (批量转换解密)
    {
        "name": "闪照破解 (com.tencent.qqnt.msg.n.a 批量转换解密)",
        "target_class": "Lcom/tencent/qqnt/msg/n;",
        "target_method": "a(Ljava/util/ArrayList;)Ljava/util/ArrayList;",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/FlashPicHelper;->handleMsgList(Ljava/util/List;)V
"""
    },
    # 规则 6：画廊大图放行 a.b
    {
        "name": "闪照破解 (AIO 画廊大图放行 a.b)",
        "target_class": "Lcom/tencent/qqnt/aio/gallery/fetch/a;",
        "target_method": "b(Ljava/util/List;)Ljava/util/List;",
        "type": "REGEX_REPLACE",
        "regex": r"sget-object\s+\w+,\s+Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;\s+invoke-static\s+\{[^}]+\},\s+Lkotlin/jvm/internal/Intrinsics;->areEqual\(Ljava/lang/Object;Ljava/lang/Object;\)Z(?:\s+move-result\s+\w+)?",
        "smali": """
    const/4 v2, 0x0"""
    },
    # 规则 7：画廊大图放行 b.b
    {
        "name": "闪照破解 (AIO 画廊大图放行 b.b)",
        "target_class": "Lcom/tencent/qqnt/aio/gallery/fetch/b;",
        "target_method": "b(Ljava/util/List;)Ljava/util/List;",
        "type": "REGEX_REPLACE",
        "regex": r"sget-object\s+\w+,\s+Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;\s+invoke-static\s+\{[^}]+\},\s+Lkotlin/jvm/internal/Intrinsics;->areEqual\(Ljava/lang/Object;Ljava/lang/Object;\)Z(?:\s+move-result\s+\w+)?",
        "smali": """
    const/4 v6, 0x0"""
    },
    # 规则 8：PicElement 构造函数脱壳
    {
        "name": "闪照破解 (PicElement 所有构造函数脱壳)",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;",
        "target_method": "<init>",
        "type": "REGEX_REPLACE",
        "regex": r"return-void(?=\s*(?:\.end\s+method|$))",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/FlashPicHelper;->handlePicElement(Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;)V
    return-void"""
    },
    # 规则 9：MsgRecord 构造函数脱壳
    {
        "name": "闪照破解 (MsgRecord 所有构造函数脱壳)",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;",
        "target_method": "<init>",
        "type": "REGEX_REPLACE",
        "regex": r"return-void(?=\s*(?:\.end\s+method|$))",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/FlashPicHelper;->handleMsgRecord(Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;)V
    return-void"""
    },
    # 规则 10：AIOElementType$f 构造函数脱壳
    {
        "name": "闪照破解 (AIOElementType.PicElement 构造函数脱壳)",
        "target_class": "Lcom/tencent/qqnt/aio/msg/element/AIOElementType$f;",
        "target_method": "<init>",
        "type": "REGEX_REPLACE",
        "regex": r"return-void(?=\s*(?:\.end\s+method|$))",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/FlashPicHelper;->handleAIOElementPic(Ljava/lang/Object;)V
    return-void"""
    },
    # 规则 11：addKernelMsgListener 实时推送代理
    {
        "name": "闪照破解 (addKernelMsgListener 实时推送代理)",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgService$CppProxy;",
        "target_method": "addKernelMsgListener(Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;)J",
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {p1}, Lcom/tencent/qqnt/patch/FlashPicHelper;->wrapKernelMsgListener(Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;)Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;
    move-result-object p1
"""
    },
    # 规则 12：AIO 会话开启监听 (AIODelegate.show)
    {
        "name": "AIO 会话开启监听 (AIODelegate.show)",
        "target_class": "Lcom/tencent/qqnt/aio/activity/AIODelegate;",
        "target_method": "show()V",
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {p0}, Lcom/tencent/qqnt/patch/plugin/FloatingBallManager;->onAIODelegateShow(Ljava/lang/Object;)V
"""
    },
    # 规则 13：AIO 会话关闭监听 (AIODelegate.hide)
    {
        "name": "AIO 会话关闭监听 (AIODelegate.hide)",
        "target_class": "Lcom/tencent/qqnt/aio/activity/AIODelegate;",
        "target_method": "hide()V",
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/plugin/FloatingBallManager;->onAIODelegateHide()V
"""
    }
]

class FastDexParser:
    def __init__(self, data: bytes):
        self.data = data
        self.valid = len(data) >= 0x70 and data[:4] == b'dex\n'
        if not self.valid: return
        self.string_ids_size = struct.unpack_from('<I', data, 0x38)[0]
        self.string_ids_off = struct.unpack_from('<I', data, 0x3C)[0]
        self.type_ids_size, self.type_ids_off = struct.unpack_from('<II', data, 0x40)
        self.proto_ids_size, self.proto_ids_off = struct.unpack_from('<II', data, 0x48)
        self.method_ids_size, self.method_ids_off = struct.unpack_from('<II', data, 0x58)
        self.class_defs_size, self.class_defs_off = struct.unpack_from('<II', data, 0x60)

    def get_string(self, str_idx: int) -> str:
        if str_idx >= self.string_ids_size: return ""
        str_off = struct.unpack_from('<I', self.data, self.string_ids_off + str_idx * 4)[0]
        p = str_off
        while self.data[p] & 0x80: p += 1
        p += 1
        end = self.data.find(b'\x00', p)
        return self.data[p:end].decode('utf-8', errors='ignore') if end != -1 else ""

    def get_type_str(self, type_idx: int) -> str:
        if type_idx >= self.type_ids_size: return ""
        desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + type_idx * 4)[0]
        return self.get_string(desc_idx)

    def get_proto_desc(self, proto_idx: int) -> str:
        if proto_idx >= self.proto_ids_size: return ""
        _, return_type_idx, parameters_off = struct.unpack_from('<III', self.data, self.proto_ids_off + proto_idx * 12)
        ret_type = self.get_type_str(return_type_idx)
        param_types = []
        if parameters_off != 0:
            size = struct.unpack_from('<I', self.data, parameters_off)[0]
            for i in range(size):
                t_idx = struct.unpack_from('<H', self.data, parameters_off + 4 + i * 2)[0]
                param_types.append(self.get_type_str(t_idx))
        return f"({''.join(param_types)}){ret_type}"

    def read_uleb128(self, pos):
        result, shift = 0, 0
        while True:
            b = self.data[pos]
            pos += 1
            result |= (b & 0x7F) << shift
            if (b & 0x80) == 0: break
            shift += 7
        return result, pos

    def find_setting_config_info(self):
        target_super = "Lcom/tencent/mobileqq/setting/processor/SettingConfigProvider;"
        matching_proto_indices = set()
        for p_idx in range(self.proto_ids_size):
            if self.get_proto_desc(p_idx) == "(Landroid/content/Context;)Ljava/util/List;":
                matching_proto_indices.add(p_idx)

        if not matching_proto_indices:
            return None, None

        for i in range(self.class_defs_size):
            super_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 8)[0]
            if super_idx < self.type_ids_size and self.get_type_str(super_idx) == target_super:
                class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
                cls_name = self.get_type_str(class_idx)
                if cls_name.startswith("Lcom/tencent/mobileqq/setting/main/"):
                    class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 24)[0]
                    if class_data_off == 0: continue
                    p = class_data_off
                    static_fields_size, p = self.read_uleb128(p)
                    instance_fields_size, p = self.read_uleb128(p)
                    direct_methods_size, p = self.read_uleb128(p)
                    virtual_methods_size, p = self.read_uleb128(p)

                    for _ in range((static_fields_size + instance_fields_size) * 2):
                        _, p = self.read_uleb128(p)

                    m_idx = 0
                    for _ in range(direct_methods_size):
                        diff, p = self.read_uleb128(p)
                        m_idx += diff
                        _, p = self.read_uleb128(p)
                        _, p = self.read_uleb128(p)
                        _, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, self.method_ids_off + m_idx * 8)
                        if proto_idx in matching_proto_indices:
                            m_name = self.get_string(name_idx)
                            return cls_name, f"{m_name}(Landroid/content/Context;)Ljava/util/List;"

                    m_idx = 0
                    for _ in range(virtual_methods_size):
                        diff, p = self.read_uleb128(p)
                        m_idx += diff
                        _, p = self.read_uleb128(p)
                        _, p = self.read_uleb128(p)
                        _, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, self.method_ids_off + m_idx * 8)
                        if proto_idx in matching_proto_indices:
                            m_name = self.get_string(name_idx)
                            return cls_name, f"{m_name}(Landroid/content/Context;)Ljava/util/List;"
        return None, None

    def find_simple_item_class(self):
        target_str_ids = set()
        for s_idx in range(self.string_ids_size):
            s = self.get_string(s_idx)
            if "SimpleItemProcessor" in s:
                target_str_ids.add(s_idx)

        if not target_str_ids:
            return None

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + class_idx * 4)[0]
            if desc_idx in target_str_ids:
                return self.get_string(desc_idx)[1:-1].replace('/', '.')

            super_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 8)[0]
            if super_idx < self.type_ids_size:
                super_desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + super_idx * 4)[0]
                if super_desc_idx in target_str_ids:
                    return self.get_string(desc_idx)[1:-1].replace('/', '.')

            source_file_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 16)[0]
            if source_file_idx in target_str_ids:
                return self.get_string(desc_idx)[1:-1].replace('/', '.')

            interfaces_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 12)[0]
            if interfaces_off != 0:
                if_size = struct.unpack_from('<I', self.data, interfaces_off)[0]
                for j in range(if_size):
                    if_type_idx = struct.unpack_from('<H', self.data, interfaces_off + 4 + j * 2)[0]
                    if_desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + if_type_idx * 4)[0]
                    if if_desc_idx in target_str_ids:
                        return self.get_string(desc_idx)[1:-1].replace('/', '.')

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 24)[0]
            if class_data_off == 0: continue

            p = class_data_off
            s_fields, p = self.read_uleb128(p)
            i_fields, p = self.read_uleb128(p)
            d_methods, p = self.read_uleb128(p)
            v_methods, p = self.read_uleb128(p)

            for _ in range((s_fields + i_fields) * 2):
                _, p = self.read_uleb128(p)

            for _ in range(d_methods + v_methods):
                _, p = self.read_uleb128(p)
                _, p = self.read_uleb128(p)
                code_off, p = self.read_uleb128(p)
                if code_off != 0 and code_off < len(self.data):
                    insns_size = struct.unpack_from('<I', self.data, code_off + 12)[0]
                    insns_start = code_off + 16
                    for k in range(insns_size):
                        if insns_start + k * 2 + 4 > len(self.data): break
                        opcode = self.data[insns_start + k * 2]
                        if opcode == 0x1A:
                            ref_str_idx = struct.unpack_from('<H', self.data, insns_start + k * 2 + 2)[0]
                            if ref_str_idx in target_str_ids:
                                desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + class_idx * 4)[0]
                                return self.get_string(desc_idx)[1:-1].replace('/', '.')
                        elif opcode == 0x1B:
                            ref_str_idx = struct.unpack_from('<I', self.data, insns_start + k * 2 + 2)[0]
                            if ref_str_idx in target_str_ids:
                                desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + class_idx * 4)[0]
                                return self.get_string(desc_idx)[1:-1].replace('/', '.')
        return None

def get_dynamic_setting_rule_fast(dex_data_dict):
    config_class, target_method, item_class = None, None, None
    for _, dex_bytes in dex_data_dict.items():
        if not (config_class and target_method) and b'SettingConfigProvider' in dex_bytes:
            parser = FastDexParser(dex_bytes)
            if parser.valid:
                cls, method = parser.find_setting_config_info()
                if cls and method: config_class, target_method = cls, method

        if not item_class and b'SimpleItemProcessor' in dex_bytes:
            parser = FastDexParser(dex_bytes)
            if parser.valid:
                item = parser.find_simple_item_class()
                if item: item_class = item

        if config_class and target_method and item_class: break

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
