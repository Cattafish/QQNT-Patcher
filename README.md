# QQNT-Patcher

针对 Android QQNT 的自动化静态 Patch 工具。通过分析并修改 APK 内的目标 Dex 字节码、追加独立扩展 Dex 的方式实现功能扩展。

## 为什么采用静态 Patch

相比传统的 LSPosed / Xposed 动态 Hook 方案，本项目采用直接修改 Dex 的静态补丁方案：

1. 规避 Hook 特征检测
   动态 Hook 框架（如 Xposed/LSPosed）通常会在内存映射（/proc/self/maps）、堆栈调用、ClassLoader 以及 ART 方法入口留下明显痕迹，容易被特征扫描识别。静态 Patch 将逻辑直接固化在 Dex 字节码中，运行时与原生代码无异，不包含任何 Hook 框架特征。
2. 运行稳定与免 Root
   无需 Root 权限，不需要依赖 VirtualXposed、两面宿傩等虚拟环境容器，重签名后即可直接在普通系统上安装运行。所有逻辑在主进程原生执行，不存在跨进程 Binder 转发开销或掉 Hook 问题。
3. 极速增量修补
   采用单 Dex 快速索引与局部修补机制。工具仅对包含目标类的单一 Dex 执行反编译和汇编，避免了使用 Apktool 对 300MB+ 安装包进行全量反编译的漫长耗时，整个 Patch 流程通常在 5~10 秒内完成。

## 当前实现的功能

1. 消息防撤回与后台同步保护
   - 实时推送拦截：拦截私聊和群聊的实时撤回指令（MsgPush）。
   - 同步数据过滤：针对下拉刷新以及后台唤醒时的同步数据包（InfoSyncPush）进行树形递归过滤，仅剥离其中的撤回指令，完整保留 sync_cookie 等同步游标，避免本地消息被服务端覆盖删除。
   - 自身操作放行：当前账号自己在其他设备或本设备发起的撤回操作正常生效，不生成多余提示。
2. 富文本灰条与交互
   - 提取撤回操作人的真实 UID 与群名片/昵称。
   - 撤回人名字带有超链接样式，点击可直接打开对方个人资料卡。
   - 提取原消息的真实序号（msgSeq），点击提示中的“一条消息”可平滑滚动并高亮定位原消息。
3. 自动化修补框架
   - 自动扫描全包 Dex，定位目标类所在的分包。
   - 自动编译 src 目录下的 Java 源码为独立扩展 Dex 并追加到 APK 尾部（如 classes38.dex）。
   - 自动完成单 Dex 替换、无损压缩打包、zipalign 对齐与重签名。

## 项目结构

```text
QQNT-Patcher/
├── src/                  # 扩展功能的 Java 源码与编译桩 (Stub)
│   └── com/tencent/qqnt/
│       └── patch/        # 核心逻辑 (AntiRevokeHelper.java 等)
├── tools/                # 自动依赖的 smali / baksmali 工具
├── rules.py              # 声明式 Hook 规则配置
├── patcher.py            # 核心自动化 Patch 执行脚本
└── README.md
```

## 环境准备

运行环境需具备 Python 3、JDK 17、Android SDK 构建工具以及 zip：

### Linux / Ubuntu / WSL 环境
```bash
sudo apt update
sudo apt install python3 openjdk-17-jdk android-sdk-build-tools zip -y
```

### Termux 环境
```bash
pkg update
pkg install python openjdk-17 d8 apksigner android-tools zip -y
```

## 使用方法

1. 将官方原版安装包命名为 `QQ.apk` 并放置在项目根目录。
2. 运行修补脚本：
   ```bash
   python3 patcher.py
   ```
   也可以手动指定输入与输出文件名：
   ```bash
   python3 patcher.py 输入包名.apk 输出包名.apk
   ```
3. 运行完成后，安装生成的 `QQ_Patched.apk` 即可。

## 新增自定义功能

项目采用引擎与规则分离的设计。如需添加新功能或修改其他 Dex 中的类，只需两步：

### 1. 编写 Java 业务代码
在 `src/com/tencent/qqnt/patch/` 目录下新增你的功能实现类（例如 `MyFeature.java`）。

### 2. 在 rules.py 中添加配置
无需关心目标类位于第几个 Dex，引擎会自动检索并完成插桩：

```python
RULES = [
    # 现有规则...

    # 新增规则示例：
    {
        "name": "自定义组件修改",
        "target_class": "Lcom/tencent/mobileqq/some/TargetClass;",
        "target_method": "targetMethod(Ljava/lang/String;)V",
        "type": "INSERT_BEFORE",  # 支持 INSERT_BEFORE 或 REPLACE
        "smali": """
        invoke-static {p0, p1}, Lcom/tencent/qqnt/patch/MyFeature;->onHook(Ljava/lang/Object;Ljava/lang/String;)V
        """
    }
]
```

## 免责声明

本项目仅供 Android 逆向工程、Dex 字节码插桩技术的研究与交流使用。请勿将本项目用于任何商业牟利或侵犯他人合法权益的场景。使用修改版本产生的任何问题由使用者自行承担。

