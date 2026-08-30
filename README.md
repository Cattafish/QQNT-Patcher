# QQNT-Patcher

主人好喵！欢迎来到 QQNT-Patcher~ 这里是一只专门给 Android QQNT 做静态修补的小工具，不用 root 也不依赖各种 hook 框架就能直接跑起来喵！

- Telegram 频道：[ZcraftMod](https://t.me/ZcraftMod)
- GitHub 仓库：[Cattafish/QQNT-Patcher](https://github.com/Cattafish/QQNT-Patcher)

---

## 项目简介

QQNT-Patcher 是一款针对 Android QQNT 的自动化静态 Dex 字节码修补工具。通过直接对 APK 内的目标 Dex 进行内存级 AST 局部重构，并追加独立扩展 Dex，实现功能扩展与增强。

### 为什么采用静态 Patch
1. **规避 Hook 特征检测**：代码直接固化在 Dex 字节码中，运行时与官方原生代码无异，不依赖 Xposed / LSPosed 框架，不存在跨进程 Binder 转发开销或掉 Hook 风险。
2. **免 Root 与便捷安装**：无需修改系统分区或依赖虚拟容器，重签名后即可直接安装使用。
3. **内存级 AST 批量修补**：采用基于 `dexlib2` 的内存流式字节码修改技术，免去解压数十万 Smali 碎文件的磁盘 I/O 损耗。
4. **4 字节页面对齐**：内置 `zipalign` 自动 4 字节页面对齐，优化在 Android 11~15 系统上的冷启动加载效率与内存占用。

---

## 版本兼容性说明

- **当前主力测试版本**：QQ `9.3.55`
- **最低兼容测试版本**：QQ `9.2.90`
- **自定义支持**：若不想使用最新版，可直接将其他版本的官方安装包传入脚本进行自动化修补。

---

## 当前功能特性

1. **消息防撤回与后台同步保护**
   - 实时推送拦截：拦截私聊和群聊的实时撤回指令（`MsgPush`）。
   - 同步数据过滤：针对下拉刷新与后台唤醒的同步包（`InfoSyncPush`）进行树形递归过滤，剥离撤回指令并保留同步游标。
   - 自身撤回放行：当前账号自己在其他设备或本设备发起的撤回操作正常生效。
   - 富文本可交互灰条：提取撤回人真实 UID、群名片/昵称，撤回人名字支持点击打开资料卡，点击“一条消息”支持定位并高亮原消息。
   - *(注：防撤回核心处理与灰条实现思路参考并致谢 [QFun](https://github.com/oneQAQone/QFun) 项目)*

2. **闪照破解与画廊放行**
   - 闪照自动解密：实时推送与消息列表中的闪照转换为普通图片展示，支持长按直接保存。
   - 画廊大图放行：解除 AIO 画廊对闪照资源的查看与保存限制。

3. **喵喵助手**
   - 发送文本消息拦截：自动进行人称词汇替换（“你” -> “主人”、“我” -> “猫猫”），智能识别语气标点与括号并在末尾适配喵化尾缀。

4. **QQ 原生二级设置中心 (Zzz)**
   - 动态在 QQ 原生设置顶层挂载 **“Zzz”** 设置入口。
   - 包含消息防撤回、闪照破解、喵喵助手、调试日志等功能的独立开关。
   - 接入版本更新检测与 QQ 原厂 `QUIBadge`（ID: `0x7f0a5eb2`）原生红点联动。

---

## 社区与交流

- **Telegram 频道**：[https://t.me/ZcraftMod](https://t.me/ZcraftMod)（获取最新打包发布、更新动态与使用交流）
- **Issue 反馈**：欢迎在 GitHub 提交 Issue 反馈使用中遇到的 Bug 或新特性建议。

---

## 环境准备

运行环境需具备 Python 3、JDK 17、Android SDK 构建工具（d8、zipalign、apksigner）、zip 与 curl：

### Linux / Ubuntu / WSL 环境
```bash
sudo apt update
sudo apt install python3 openjdk-17-jdk android-sdk-build-tools zipalign zip curl -y
```

### Termux (Android) 环境
```bash
pkg update
pkg install python openjdk-17 d8 apksigner android-tools zip curl -y
```

---

## 快速使用教程

### 步骤 1：拉取构建依赖组件

首次拉取项目后，在项目根目录下执行以下命令下载所需的 4 个依赖 Jar 包至 `tools/` 目录：

```bash
mkdir -p tools
curl -L -o tools/baksmali.jar https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
curl -L -o tools/smali.jar https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
curl -L -o tools/dexlib2.jar https://repo1.maven.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar
curl -L -o tools/guava.jar https://repo1.maven.org/maven2/com/google/guava/guava/18.0/guava-18.0.jar
```

---

### 步骤 2：放置官方原版 APK

将官方原版 QQ 安装包命名为 `QQ.apk` 并放置在项目根目录下（或在命令行指定路径）。

---

### 步骤 3：执行自动化修补

#### 方式 A：普通设备（默认模式）
自动使用固定的 Debug 证书进行重签名（首次会自动生成 `tools/debug.keystore`，后续永久复用证书）：
```bash
python3 patcher.py
```

#### 方式 B：不签名模式
添加 `--no-sign`（或简写 `-n`）参数跳过签名阶段，自动生成纯净未签名包并清除损坏的签名元数据：
```bash
python3 patcher.py -n
```

#### 方式 C：自定义输入输出路径
```bash
python3 patcher.py 我的QQ.apk 输出_已修补.apk
```

---

### 步骤 4：在应用内配置功能

安装并登录修补后的 QQ：
1. 打开 QQ，点击左上角头像 -> **设置**。
2. 找到顶层的 **“Zzz”** 选项。
3. 进入后即可按需开启各项功能或检查最新版本。

---

## 命令行参数一览

| 参数 | 说明 | 示例 |
| :--- | :--- | :--- |
| `[输入包路径]` | 可选，指定输入 APK 路径，默认 `QQ.apk` | `python3 patcher.py base.apk` |
| `[输出包路径]` | 可选，指定输出 APK 路径，默认 `QQ_Patched.apk` | `python3 patcher.py in.apk out.apk` |
| `--no-sign` / `-n` | 跳过签名阶段并剔除残留签名元数据 | `python3 patcher.py -n` |

---

## 项目结构

```text
QQNT-Patcher/
├── src/                  # 扩展功能的 Java 源码与编译桩 (Stub)
│   └── com/tencent/qqnt/
│       └── patch/        # 核心逻辑实现 (AntiRevokeHelper、FlashPicHelper、QUIBadgeHelper 等)
├── assets/               # 注入 APK 的自定义静态资源 (zzz_icon.png)
├── tools/                # 构建依赖库与签名证书
│   ├── baksmali.jar
│   ├── smali.jar
│   ├── dexlib2.jar
│   ├── guava.jar
│   └── debug.keystore    # 固定签名证书 (首次自动生成)
├── DexPatcher.java       # DEX 内存 AST 批量修补引擎
├── rules.py              # 声明式 Hook 规则配置与动态探测逻辑
├── patcher.py            # 核心自动化 Patch 执行调度脚本
└── README.md
```

---

## 鸣谢与致敬

- [QFun](https://github.com/oneQAQone/QFun)：感谢项目提供的 QQNT 消息防撤回与富文本灰条交互思路。
- [Smali / Baksmali / Dexlib2](https://github.com/JesusFreke/smali)：感谢 JesusFreke 提供的强大 Dex 字节码重构库。

---

## 免责声明

本项目仅供 Android 逆向工程与 Dex 字节码静态插桩技术的研究与交流使用。请勿将本项目用于任何商业牟利或侵犯他人合法权益的场景。使用修改版本产生的任何问题由使用者自行承担。
