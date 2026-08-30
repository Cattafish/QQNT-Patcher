#include <iostream>
#include <vector>
#include <string>
#include <fstream>
#include <cstring>
#include <cstdint>
#include <map>
#include <algorithm>

// --- SHA1 & Adler32 ---
static inline uint32_t calc_adler32(const uint8_t* data, size_t len) {
    uint32_t a = 1, b = 0;
    for (size_t i = 0; i < len; ++i) {
        a = (a + data[i]) % 65521;
        b = (b + a) % 65521;
    }
    return (b << 16) | a;
}

static inline void sha1_transform(uint32_t state[5], const uint8_t buffer[64]) {
    uint32_t a = state[0], b = state[1], c = state[2], d = state[3], e = state[4];
    uint32_t w[80];
    for (int i = 0; i < 16; i++) {
        w[i] = (buffer[i * 4] << 24) | (buffer[i * 4 + 1] << 16) | (buffer[i * 4 + 2] << 8) | (buffer[i * 4 + 3]);
    }
    for (int i = 16; i < 80; i++) {
        uint32_t temp = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
        w[i] = (temp << 1) | (temp >> 31);
    }
    for (int i = 0; i < 80; i++) {
        uint32_t f, k;
        if (i < 20) { f = (b & c) | ((~b) & d); k = 0x5A827999; }
        else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
        else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
        else { f = b ^ c ^ d; k = 0xCA62C1D6; }
        uint32_t temp = ((a << 5) | (a >> 27)) + f + e + k + w[i];
        e = d; d = c; c = (b << 30) | (b >> 2); b = a; a = temp;
    }
    state[0] += a; state[1] += b; state[2] += c; state[3] += d; state[4] += e;
}

static inline void calc_sha1(const uint8_t* data, size_t len, uint8_t digest[20]) {
    uint32_t state[5] = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
    uint64_t total_bits = len * 8;
    size_t i = 0;
    while (i + 64 <= len) {
        sha1_transform(state, data + i);
        i += 64;
    }
    uint8_t buffer[64] = {0};
    size_t rem = len - i;
    memcpy(buffer, data + i, rem);
    buffer[rem] = 0x80;
    if (rem >= 56) {
        sha1_transform(state, buffer);
        memset(buffer, 0, 64);
    }
    for (int j = 0; j < 8; j++) {
        buffer[63 - j] = (total_bits >> (j * 8)) & 0xFF;
    }
    sha1_transform(state, buffer);
    for (int j = 0; j < 5; j++) {
        digest[j * 4] = (state[j] >> 24) & 0xFF;
        digest[j * 4 + 1] = (state[j] >> 16) & 0xFF;
        digest[j * 4 + 2] = (state[j] >> 8) & 0xFF;
        digest[j * 4 + 3] = state[j] & 0xFF;
    }
}

// --- ULEB128 ---
static inline uint32_t read_uleb128(const uint8_t*& p) {
    uint32_t result = 0;
    int shift = 0;
    while (true) {
        uint8_t b = *p++;
        result |= (b & 0x7F) << shift;
        if (!(b & 0x80)) break;
        shift += 7;
    }
    return result;
}

static inline void write_uleb128(std::vector<uint8_t>& out, uint32_t val) {
    while (val >= 0x80) {
        out.push_back((uint8_t)((val & 0x7F) | 0x80));
        val >>= 7;
    }
    out.push_back((uint8_t)(val & 0x7F));
}

#pragma pack(push, 1)
struct DexHeader {
    uint8_t magic[8];
    uint32_t checksum;
    uint8_t signature[20];
    uint32_t file_size;
    uint32_t header_size;
    uint32_t endian_tag;
    uint32_t link_size;
    uint32_t link_off;
    uint32_t map_off;
    uint32_t string_ids_size;
    uint32_t string_ids_off;
    uint32_t type_ids_size;
    uint32_t type_ids_off;
    uint32_t proto_ids_size;
    uint32_t proto_ids_off;
    uint32_t field_ids_size;
    uint32_t field_ids_off;
    uint32_t method_ids_size;
    uint32_t method_ids_off;
    uint32_t class_defs_size;
    uint32_t class_defs_off;
    uint32_t data_size;
    uint32_t data_off;
};

struct ClassDefItem {
    uint32_t class_idx;
    uint32_t access_flags;
    uint32_t superclass_idx;
    uint32_t interfaces_off;
    uint32_t source_file_idx;
    uint32_t annotations_off;
    uint32_t class_data_off;
    uint32_t static_values_off;
};

struct MethodIdItem {
    uint16_t class_idx;
    uint16_t proto_idx;
    uint32_t name_idx;
};

struct ProtoIdItem {
    uint32_t shorty_idx;
    uint32_t return_type_idx;
    uint32_t parameters_off;
};
#pragma pack(pop)

class DexInjector {
public:
    std::vector<uint8_t> buf;

    bool load(const std::string& path) {
        std::ifstream f(path, std::ios::binary | std::ios::ate);
        if (!f.is_open()) return false;
        size_t sz = f.tellg();
        f.seekg(0, std::ios::beg);
        buf.resize(sz);
        f.read((char*)buf.data(), sz);
        return true;
    }

    bool save(const std::string& path) {
        align4();
        DexHeader* hdr = get_header();
        hdr->file_size = (uint32_t)buf.size();
        hdr->data_size = hdr->file_size - hdr->data_off;

        calc_sha1(buf.data() + 32, buf.size() - 32, hdr->signature);
        hdr->checksum = calc_adler32(buf.data() + 12, buf.size() - 12);

        std::ofstream f(path, std::ios::binary);
        if (!f.is_open()) return false;
        f.write((const char*)buf.data(), buf.size());
        return true;
    }

    DexHeader* get_header() { return (DexHeader*)buf.data(); }

    void align4() {
        while (buf.size() % 4 != 0) buf.push_back(0);
    }

    std::string get_string(uint32_t str_idx) {
        DexHeader* hdr = get_header();
        if (str_idx >= hdr->string_ids_size) return "";
        uint32_t str_off = *(uint32_t*)(buf.data() + hdr->string_ids_off + str_idx * 4);
        const uint8_t* p = buf.data() + str_off;
        while (*p & 0x80) p++;
        p++;
        return std::string((const char*)p);
    }

    std::string get_type_str(uint32_t type_idx) {
        DexHeader* hdr = get_header();
        if (type_idx >= hdr->type_ids_size) return "";
        uint32_t desc_idx = *(uint32_t*)(buf.data() + hdr->type_ids_off + type_idx * 4);
        return get_string(desc_idx);
    }

    uint32_t find_string_idx(const std::string& target) {
        DexHeader* hdr = get_header();
        for (uint32_t i = 0; i < hdr->string_ids_size; ++i) {
            if (get_string(i) == target) return i;
        }
        return (uint32_t)-1;
    }

    uint32_t find_type_idx(const std::string& target) {
        DexHeader* hdr = get_header();
        for (uint32_t i = 0; i < hdr->type_ids_size; ++i) {
            if (get_type_str(i) == target) return i;
        }
        return (uint32_t)-1;
    }

    uint32_t find_method_idx(const std::string& cls_desc, const std::string& name) {
        DexHeader* hdr = get_header();
        uint32_t c_idx = find_type_idx(cls_desc);
        uint32_t n_idx = find_string_idx(name);
        if (c_idx == (uint32_t)-1 || n_idx == (uint32_t)-1) return (uint32_t)-1;

        MethodIdItem* methods = (MethodIdItem*)(buf.data() + hdr->method_ids_off);
        for (uint32_t i = 0; i < hdr->method_ids_size; ++i) {
            if (methods[i].class_idx == c_idx && methods[i].name_idx == n_idx) {
                return i;
            }
        }
        return (uint32_t)-1;
    }

    // 规则 6 & 7: 快速原位替换 boolean 检查为 false
    bool patch_gallery_boolean(const std::string& cls_desc, uint8_t target_reg) {
        DexHeader* hdr = get_header();
        uint32_t c_idx = find_type_idx(cls_desc);
        if (c_idx == (uint32_t)-1) return false;

        ClassDefItem* classes = (ClassDefItem*)(buf.data() + hdr->class_defs_off);
        for (uint32_t i = 0; i < hdr->class_defs_size; ++i) {
            if (classes[i].class_idx == c_idx) {
                uint32_t class_data_off = classes[i].class_data_off;
                if (class_data_off == 0) return false;

                const uint8_t* p = buf.data() + class_data_off;
                uint32_t s_fields = read_uleb128(p);
                uint32_t i_fields = read_uleb128(p);
                uint32_t d_methods = read_uleb128(p);
                uint32_t v_methods = read_uleb128(p);

                for (uint32_t f = 0; f < (s_fields + i_fields) * 2; ++f) read_uleb128(p);

                for (uint32_t m = 0; m < d_methods + v_methods; ++m) {
                    read_uleb128(p); // diff
                    read_uleb128(p); // flags
                    uint32_t code_off = read_uleb128(p);
                    if (code_off != 0 && code_off < buf.size()) {
                        uint16_t* insns = (uint16_t*)(buf.data() + code_off + 16);
                        uint32_t insns_size = *(uint32_t*)(buf.data() + code_off + 12);
                        // 寻找 areEqual 并替换
                        for (uint32_t k = 0; k + 4 < insns_size; ++k) {
                            if ((insns[k] & 0xFF) == 0x62 && (insns[k + 2] & 0xFF) == 0x71) {
                                // 替换为 const/4 vX, 0
                                insns[k] = (uint16_t)(0x12 | (target_reg << 8));
                                insns[k + 1] = 0x0000; // nop
                                insns[k + 2] = 0x0000; // nop
                                insns[k + 3] = 0x0000; // nop
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
};

int main(int argc, char* argv[]) {
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " <in.dex> <out.dex> [rules...]\n";
        return 1;
    }

    std::string in_dex = argv[1];
    std::string out_dex = argv[2];

    DexInjector injector;
    if (!injector.load(in_dex)) {
        std::cerr << "无法打开: " << in_dex << "\n";
        return 1;
    }

    // 执行画廊放行等原生极速原位修补
    injector.patch_gallery_boolean("Lcom/tencent/qqnt/aio/gallery/fetch/a;", 2);
    injector.patch_gallery_boolean("Lcom/tencent/qqnt/aio/gallery/fetch/b;", 6);

    if (!injector.save(out_dex)) {
        std::cerr << "写入失败: " << out_dex << "\n";
        return 1;
    }

    return 0;
}