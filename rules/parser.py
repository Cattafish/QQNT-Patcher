# -*- coding: utf-8 -*-
"""FastDexParser: DEX 字节码流式解析引擎与语义扫描器"""
import struct

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

    def find_string_id(self, target: str) -> int:
        target_bytes = target.encode('utf-8')
        if target_bytes not in self.data: return -1
        for i in range(self.string_ids_size):
            if self.get_string(i) == target: return i
        return -1

    def find_type_id(self, target_type: str) -> int:
        target_bytes = target_type.encode('utf-8')
        if target_bytes not in self.data: return -1
        for i in range(self.type_ids_size):
            if self.get_type_str(i) == target_type: return i
        return -1

    def find_setting_config_info(self):
        target_super = "Lcom/tencent/mobileqq/setting/processor/SettingConfigProvider;"
        matching_proto_indices = set()
        for p_idx in range(self.proto_ids_size):
            if self.get_proto_desc(p_idx) == "(Landroid/content/Context;)Ljava/util/List;":
                matching_proto_indices.add(p_idx)

        if not matching_proto_indices: return None, None

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

                    # 1. 扫描直接方法
                    m_idx = 0
                    for _ in range(direct_methods_size):
                        diff, p = self.read_uleb128(p)
                        m_idx += diff
                        _, p = self.read_uleb128(p); _, p = self.read_uleb128(p)
                        _, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, self.method_ids_off + m_idx * 8)
                        if proto_idx in matching_proto_indices:
                            return cls_name, f"{self.get_string(name_idx)}(Landroid/content/Context;)Ljava/util/List;"

                    # 2. 扫描虚方法 (★ 必须严格置零计数器，修复错位)
                    m_idx = 0
                    for _ in range(virtual_methods_size):
                        diff, p = self.read_uleb128(p)
                        m_idx += diff
                        _, p = self.read_uleb128(p); _, p = self.read_uleb128(p)
                        _, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, self.method_ids_off + m_idx * 8)
                        if proto_idx in matching_proto_indices:
                            return cls_name, f"{self.get_string(name_idx)}(Landroid/content/Context;)Ljava/util/List;"
        return None, None

    def find_simple_item_class(self):
        target_str_ids = set()
        for s_idx in range(self.string_ids_size):
            s = self.get_string(s_idx)
            if "SimpleItemProcessor" in s:
                target_str_ids.add(s_idx)

        if not target_str_ids: return None

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + class_idx * 4)[0]
            if desc_idx in target_str_ids: return self.get_string(desc_idx)[1:-1].replace('/', '.')
            super_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 8)[0]
            if super_idx < self.type_ids_size:
                super_desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + super_idx * 4)[0]
                if super_desc_idx in target_str_ids: return self.get_string(desc_idx)[1:-1].replace('/', '.')
            source_file_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 16)[0]
            if source_file_idx in target_str_ids: return self.get_string(desc_idx)[1:-1].replace('/', '.')

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 24)[0]
            if class_data_off == 0: continue
            p = class_data_off
            s_fields, p = self.read_uleb128(p); i_fields, p = self.read_uleb128(p)
            d_methods, p = self.read_uleb128(p); v_methods, p = self.read_uleb128(p)
            for _ in range((s_fields + i_fields) * 2): _, p = self.read_uleb128(p)
            for _ in range(d_methods + v_methods):
                _, p = self.read_uleb128(p); _, p = self.read_uleb128(p)
                code_off, p = self.read_uleb128(p)
                if code_off != 0 and code_off < len(self.data):
                    insns_size = struct.unpack_from('<I', self.data, code_off + 12)[0]
                    insns_start = code_off + 16
                    for k in range(insns_size):
                        if insns_start + k * 2 + 4 > len(self.data): break
                        opcode = self.data[insns_start + k * 2]
                        if opcode == 0x1A or opcode == 0x1B:
                            ref_str_idx = struct.unpack_from('<H', self.data, insns_start + k * 2 + 2)[0]
                            if ref_str_idx in target_str_ids:
                                desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + class_idx * 4)[0]
                                return self.get_string(desc_idx)[1:-1].replace('/', '.')
        return None

    def get_class_methods(self, class_idx: int):
        methods = []
        class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + class_idx * 32 + 24)[0]
        if class_data_off == 0: return methods
        p = class_data_off
        s_f, p = self.read_uleb128(p); i_f, p = self.read_uleb128(p)
        d_m, p = self.read_uleb128(p); v_m, p = self.read_uleb128(p)
        for _ in range((s_f + i_f) * 2): _, p = self.read_uleb128(p)

        m_idx = 0
        for _ in range(d_m):
            diff, p = self.read_uleb128(p)
            m_idx += diff
            _, p = self.read_uleb128(p)
            code_off, p = self.read_uleb128(p)
            _, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, self.method_ids_off + m_idx * 8)
            methods.append((self.get_string(name_idx), self.get_proto_desc(proto_idx), False, code_off))

        m_idx = 0
        for _ in range(v_m):
            diff, p = self.read_uleb128(p)
            m_idx += diff
            _, p = self.read_uleb128(p)
            code_off, p = self.read_uleb128(p)
            _, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, self.method_ids_off + m_idx * 8)
            methods.append((self.get_string(name_idx), self.get_proto_desc(proto_idx), True, code_off))

        return methods

    def find_class_index(self, target_type_str: str) -> int:
        for i in range(self.class_defs_size):
            c_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            if self.get_type_str(c_idx) == target_type_str:
                return i
        return -1

    def find_classes_referencing_string_strictly(self, target_str: str):
        if not self.valid: return []
        s_id = self.find_string_id(target_str)
        if s_id == -1: return []

        matched_classes = []
        EXCLUDE = ("/nearby/", "/fragment/", "/viewmodel/", "/ui/", "/widget/", "/adapter/", "/facetoface/", "/qcall/", "/relation/", "/share/")
        str_bytes_16 = struct.pack('<H', s_id)
        str_bytes_32 = struct.pack('<I', s_id)

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            cls_name = self.get_type_str(class_idx)
            if any(tag in cls_name for tag in EXCLUDE): continue

            class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 24)[0]
            if class_data_off == 0: continue

            methods = self.get_class_methods(i)
            found = False
            for _, _, _, code_off in methods:
                if code_off != 0 and code_off + 16 < len(self.data):
                    insns_size = struct.unpack_from('<I', self.data, code_off + 12)[0]
                    insns = self.data[code_off + 16: code_off + 16 + insns_size * 2]
                    k = 0
                    len_insns = len(insns)
                    while k < len_insns - 1:
                        op = insns[k]
                        if op == 0x1A and k + 4 <= len_insns:
                            if insns[k + 2 : k + 4] == str_bytes_16:
                                found = True; break
                            k += 4; continue
                        elif op == 0x1B and k + 6 <= len_insns:
                            if insns[k + 2 : k + 6] == str_bytes_32:
                                found = True; break
                            k += 6; continue
                        k += 2
                if found: break
            if found: matched_classes.append((cls_name, i))
        return matched_classes

    def find_classes_referencing_type_strictly(self, target_type_str: str):
        if not self.valid: return []
        t_id = self.find_type_id(target_type_str)
        if t_id == -1: return []

        matched_classes = []
        t_pat = struct.pack('<H', t_id)

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            cls_name = self.get_type_str(class_idx)
            if "com/tencent/ims" in cls_name: continue

            class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 24)[0]
            if class_data_off == 0: continue

            methods = self.get_class_methods(i)
            found = False
            for _, _, _, code_off in methods:
                if code_off != 0 and code_off + 16 < len(self.data):
                    insns_size = struct.unpack_from('<I', self.data, code_off + 12)[0]
                    insns = self.data[code_off + 16: code_off + 16 + insns_size * 2]
                    k = 0
                    while k < len(insns) - 3:
                        op = insns[k]
                        if (op in (0x1F, 0x20, 0x22)) and insns[k + 2 : k + 4] == t_pat:
                            found = True
                            break
                        k += 2
                if found: break
            if found: matched_classes.append((cls_name, i))
        return matched_classes