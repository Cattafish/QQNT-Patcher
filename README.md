# QQNT-Patcher

针对 Android QQNT 的自动化静态 Patch 工具。通过分析并修改 APK 内的目标 Dex 字节码、追加独立扩展 Dex 的方式实现功能扩展。

## 为什么采用静态 Patch

相比传统的 LSPosed / Xposed 动态 Hook 方案，本项目采用直接修改 Dex 的静态补丁方案：

1. 规避 Hook 特征检测
   动态 Hook 框架通常会在内存映射（`/proc/self/maps`）、堆栈调用、ClassLoader 以及 ART 方法入口留下明显痕迹。静态 Patch 将逻辑直接固化在 Dex 字节码中，运行时与原生代码无异，不包含任何 Hook 框架特征。
2. 运行稳定与免 Root
   无需 Root 权限，不依赖虚拟环境容器，重签名后即可直接安装运行。所有逻辑在主进程原生执行，不存在跨进程 Binder 转发开销或掉 Hook 问题。
3. 内存级分包修补
   采用基于 `dexlib2` 的内存 AST 局部重构技术，无需将几十万行代码解压为 Smali 文本碎文件，大幅降低磁盘 I/O 损耗并减少构建耗时。

## 当前实现的功能

1. 消息防撤回与同步保护
   - 实时推送拦截：拦截私聊和群聊的实时撤回指令（`MsgPush`）。
   - 同步数据过滤：针对下拉刷新与后台唤醒的同步包（`InfoSyncPush`）进行树形递归过滤，剥离撤回指令并保留同步游标。
   - 自身操作放行：当前账号自己在其他设备或本设备发起的撤回操作正常生效。
   - 富文本可交互灰条：提取撤回人真实 UID、群名片/昵称，撤回人名字支持点击打开资料卡，点击“一条消息”支持定位并高亮原消息。
2. 闪照破解与画廊放行
   - 闪照自动解密：实时推送与消息列表中的闪照转换为普通图片展示，支持直接保存。
   - 画廊大图放行：解除 AIO 画廊对闪照资源的查看限制。
3. 喵喵助手
   - 消息发送拦截：发送文本消息时自动进行人称词汇替换、括号语气适配与末尾喵化处理。
4. 原生二级设置界面挂载
   - 在 QQ 原生设置中心中注入二级设置入口，支持在应用内自由开关防撤回、闪照破解、喵喵助手等功能。

## 环境准备

运行环境需具备 Python 3、JDK 17、Android SDK 构建工具（d8、apksigner）、zip 与 curl：

### Linux / Ubuntu / WSL 环境
```bash
sudo apt update
sudo apt install python3 openjdk-17-jdk android-sdk-build-tools zip curl -y
```

### Termux (Android) 环境
```bash
pkg update
pkg install python openjdk-17 d8 apksigner android-tools zip curl -y
```

---

## 快速使用教程

### 步骤 1：拉取构建依赖组件

首次拉取项目后，在项目根目录执行以下命令一键下载所需的 4 个依赖 Jar 包至 `tools/` 目录：

```bash
mkdir -p tools
curl -L -o tools/baksmali.jar https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
curl -L -o tools/smali.jar https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
curl -L -o tools/dexlib2.jar https://repo1.maven.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar
curl -L -o tools/guava.jar https://repo1.maven.org/maven2/com/google/guava/guava/18.0/guava-18.0.jar
```

---

### 步骤 2：放置官方原版 APK

将官方原版 QQ 安装包命名为 `QQ.apk` 并放置在项目根目录下。

---

### 步骤 3：执行自动化修补

根据你的设备环境选择对应的修补方式：

#### 方式 A：免 Root / 普通设备（默认模式）
自动使用固定的 Debug 证书进行重签名（首次会自动生成 `tools/debug.keystore`，后续永久复用证书）：
```bash
python3 patcher.py
```
> **提示**：若此前安装的是官方原版，因签名不一致需卸载原版后安装生成的 `QQ_Patched.apk`；后续使用该脚本更新时均可直接覆盖安装。

#### 方式 B：已开启“核心破解”的 Root 设备（未签名模式）
添加 `--no-sign`（或简写 `-n`）参数跳过签名阶段，自动生成纯净未签名包：
```bash
python3 patcher.py --no-sign
# 或使用简写
python3 patcher.py -n
```
> **提示**：配合核心破解中的“允许安装未签名应用”与“允许覆盖不同签名的应用”，可直接免卸载覆盖官方原版，保留所有聊天记录。

#### 方式 C：自定义输入输出路径
```bash
python3 patcher.py 我的QQ.apk 输出_已修补.apk
```

---

### 步骤 4：在应用内配置功能

安装并登录修补后的 QQ：
1. 打开 QQ，点击左上角头像 -> **设置**。
2. 找到新增的 **“Zzz”** 选项（位于顶层设置列表中）。
3. 进入后即可自由开启或关闭：
   - **消息防撤回**
   - **闪照破解**
   - **喵喵助手**
   - **调试日志输出**

---

## 命令行参数一览

| 参数 | 说明 | 示例 |
| :--- | :--- | :--- |
| `[输入包路径]` | 可选，指定输入 APK 路径，默认 `QQ.apk` | `python3 patcher.py base.apk` |
| `[输出包路径]` | 可选，指定输出 APK 路径，默认 `QQ_Patched.apk` | `python3 patcher.py in.apk out.apk` |
| `--no-sign` / `-n` | 跳过签名阶段并剔除残留签名数据（适合核心破解） | `python3 patcher.py -n` |

---

## 项目结构

```text
QQNT-Patcher/
├── src/                  # 扩展功能的 Java 源码与编译桩 (Stub)
│   └── com/tencent/qqnt/
│       └── patch/        # 核心逻辑实现 (AntiRevokeHelper、FlashPicHelper 等)
├── assets/               # 注入 APK 的自定义静态资源 (如 zzz_icon.png)
├── tools/                # 构建依赖库与签名证书
│   ├── baksmali.jar
│   ├── smali.jar
│   ├── dexlib2.jar
│   ├── guava.jar
│   └── debug.keystore    # 固定的签名证书 (首次自动生成)
├── DexPatcher.java       # DEX 内存 AST 批量修补引擎
├── rules.py              # 声明式 Hook 规则配置与动态探测逻辑
├── patcher.py            # 核心自动化 Patch 执行调度脚本
└── README.md
```

---

## 新增自定义功能

项目采用“业务源码与插桩规则解耦”的设计。如需添加新功能：

### 1. 编写 Java 业务代码
在 `src/com/tencent/qqnt/patch/` 目录下新增你的功能实现类（例如 `MyFeature.java`）。

### 2. 在 rules.py 中添加规则
无需关心目标类位于哪个 Dex 分包，引擎会自动完成索引与路由：

```python
RULES = [
    # 现有规则...

    # 新增规则示例：
    {
        "name": "自定义组件修改",
        "target_class": "Lcom/tencent/mobileqq/some/TargetClass;",
        "target_method": "targetMethod(Ljava/lang/String;)V",
        "type": "INSERT_BEFORE",  # 支持 INSERT_BEFORE, REPLACE, REGEX_REPLACE
        "smali": """
        invoke-static {p0, p1}, Lcom/tencent/qqnt/patch/MyFeature;->onHook(Ljava/lang/Object;Ljava/lang/String;)V
        """
    }
]
```

---

## 常见问题排查 (FAQ)

### Q1: 提示 `未检测到完整的 tools 依赖！`
**A**: 请确保完整执行了[步骤 1](#步骤-1拉取构建依赖组件) 中的 `curl` 下载命令，`tools/` 目录下必须同时存在 `baksmali.jar`、`smali.jar`、`dexlib2.jar` 和 `guava.jar`。

### Q2: 提示 `INSTALL_PARSE_FAILED_NO_CERTIFICATES: Signature stripped?`
**A**: 
- 若使用 `--no-sign` 参数，请确保在系统**核心破解模块中勾选了“允许安装未签名的应用”**。
- 若未使用核心破解，请不要添加 `--no-sign` 参数，直接运行 `python3 patcher.py` 走默认固定证书签名。

### Q3: 提示 `d8: command not found` 或 `apksigner: command not found`
**A**: 构建工具未安装。在 Termux 中执行 `pkg install d8 apksigner android-tools -y`；在 Ubuntu/Debian 中执行 `sudo apt install android-sdk-build-tools -y`。

---

## 免责声明

本项目仅供 Android 逆向工程、Dex 字节码插桩技术的研究与交流使用。请勿将本项目用于任何商业牟利或侵犯他人合法权益的场景。使用修改版本产生的任何问题由使用者自行承担。
