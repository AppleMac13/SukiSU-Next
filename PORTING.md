# SukiSU Nova 前端 → KernelSU-Next 后端 移植说明

本目录是 `KernelSU-Next`（后端：kernel + userspace）与 `SukiSU-Ultra` 的 Nova 前端（manager）合并后的移植树。
前端 UI、模块管理、超级用户、SuSFS、刷写、安装等全部保留；只把「内核接口层」从 SukiSU 方言改写成 KernelSU-Next 方言，
并把 KernelSU-Next 内核根本没有实现的 fork 私有功能在 UI 上显式关门，而不是让它们在半路报错。

- 上游基线：`KernelSU-Next/KernelSU-Next`（main）、`SukiSU-Ultra/SukiSU-Ultra`（main）
- 前端包名：`com.sukisu.nova`（未改，内核按这个包名 + 签名哈希认 manager）
- 后端 UAPI：version 6，核心 ioctl（1–22、98、99）与 SukiSU 完全同号，这是能移植的前提

## 1. 能力对照

| 功能 | KernelSU-Next 后端 | 处理方式 |
|---|---|---|
| 超级用户 / allowlist / app profile | ✅ 同结构 `app_profile` | 直接复用 |
| su compat、kernel umount、sulog、adb_root、selinux_hide、avc_spoof | ✅ `KSU_FEATURE_*` | `ksud feature` 通用通道 |
| SuSFS | ✅ 内核自带 `kernel/susfs`，ksud 有 `susfs` 子命令 | 直接复用 |
| 模块安装/启停/卸载/action/WebUI | ✅ `ksud module` | 直接复用 |
| boot-patch / boot-restore / 安装 / LKM | ✅ | 去掉 SukiSU 私有参数（见 2.3） |
| 完整版本号（`GET_FULL_VERSION`, ioctl 100） | ❌ 只有 `GET_VERSION_TAG`(99) | JNI 用版本 tag 合成 `getFullVersion()` |
| hook 类型（`HOOK_TYPE`, ioctl 101） | ❌ 只有 `GET_HOOK_MODE`(98) | JNI 映射 |
| mount hide（feature 16） | ❌ 无此 feature | JNI 返回 false / 设置返回 `-EOPNOTSUPP` |
| 内核扩展（`KSU_IOCTL_EXTENSION`）：CPU 伪装、内核侧版本伪装、umount 路径表、动态 manager | ❌ | `BackendCapabilities` 关门，路由落到说明页 |
| KPM / KernelPatch | ❌ | 关门，构建不再下载 kpm 工具 |
| 内置 Zygisk 开关 | ❌ | 关门 |
| `ksud umount list/save/apply/clear-custom` | ❌ 只有 `kernel umount add/del/wipe` | umount 管理页关门 |
| `ksud temporary-root` | ❌ | 调用会失败，UI 提示失败（未单独隐藏） |
| magica 应用 zygote 引导（`late-load --magica`） | ❌ | 保留 JNI 符号避免 manifest 崩溃，实际 exec 失败 |

## 2. 改动清单

### 2.1 原生层 `manager/app/src/main/cpp`

- `ksu.h` / `ksu.cc`：重写为 KernelSU-Next 驱动适配版。
  - 保留 Next 的 `GET_INFO` + `GET_INFO_LEGACY` 双路径、feature 读写、app profile。
  - 新增 `get_full_version()`（`GET_VERSION_TAG` → 版本字符串）、`get_hook_type()`（`GET_HOOK_MODE`）。
  - 新增 `is_pr_build()`。
  - `set/is_mount_hide_enabled()` 作为 no-op stub（`-EOPNOTSUPP` / false）。
- `jni.cc`：JNI 符号名保持 SukiSU 的 `Java_com_sukisu_nova_*`，签名与 `Natives.kt` 一一对应；
  新增 `getFullVersion` / `getHookType` / `getIsPrBuild` / mount hide 两个入口；
  保留 `AppZygotePreload` 的 `forkDontCareAndExecKsud`（manifest 声明了 `zygotePreloadName`，缺符号会崩）。
- `uapi/*.h`：由符号链接改为真实文件（上游用 git symlink 指向仓库根，Windows 检出后无法编译）。
- `CMakeLists.txt`：去掉指向仓库根的 include，改为 `-I .` + 本目录 `uapi/`。

### 2.2 Kotlin 层（只动必要处）

- `Natives.kt`
  - `MINIMAL_SUPPORTED_KERNEL = 33188`
  - `isFullFeatured()` 改为「isManager && kernelUAPIVersion == managerUAPIVersion && rootAvailable()」，
    不再依赖不存在的 `MINIMAL_SUPPORTED_KERNEL_FULL` 全版本比较。
- 新增 `backend/BackendCapabilities.kt`：一处声明本后端支持/不支持的能力。
- 新增 `ui/screen/UnsupportedBackend.kt`：不支持功能的说明页。
- `ui/MainActivity.kt`：KPM / DynamicManager / Extensions / CpuSpoof / VersionSpoof / BuiltinZygisk / UmountManager
  七个路由按能力开关择一渲染（现在是说明页）。
- `ui/util/KsuCli.kt`
  - `install()`：去掉 Next 不认的 `--data-path`
  - `undoUninstallModule()`：`module undo-uninstall` → `module restore`
  - `getFeatureStatus()`：非四种标准状态一律折算为 `unsupported`，让设置项正常置灰
  - boot-patch：去掉 `--backup`、`--spoof-release`、`--spoof-version`（Next 无这些参数）

### 2.3 userspace `ksud`

- `cli.rs`：`late-load --package-name` / `uninstall --package-name` / `debug set-manager` 默认包名 → `com.sukisu.nova`
- `late_load.rs`：重启 manager 的组件名 → `com.sukisu.nova.ui.MainActivity`
  （否则 late-load 之后会把原 KernelSU-Next manager 拉起来，而不是本前端）

### 2.4 构建

- `manager/app/build.gradle.kts`：`preBuild` 不再依赖 `downloadKpmTools`（KPM 已关门，构建不依赖那个镜像）。

### 2.5 内核

- `kernel/manager/apk_sign.c`：`is_manager_apk()` 删除签名校验，改为按包名判定（详见第 3 节）。
  唯一调用点在 `throne_tracker.c` 的 `search_manager()`，它本来就是遍历 `/data/app` 下的 `base.apk`，
  现在等于「包名对了就加冕」，不再校验证书。

## 3. 构建步骤

工具链：JDK 21、Android SDK Platform 37 / Build-Tools 37.0.0、NDK（版本见 `manager/gradle/libs.versions.toml` 的 `ndk`）、
Rust + `cross`（编 ksud）。仓库自带的 `build.sh` 是 bash 脚本，Windows 上请在 WSL 里跑。

```bash
# 1) 编 ksud 并塞进 jniLibs（libksud.so 就是会被 app exec 的 ksud）
./build.sh            # 等价于 cross build + cp 到 manager/app/src/main/jniLibs/arm64-v8a/libksud.so

# 2) 编 APK 并直接用自己的 keystore 签名（apksign 插件读 -P 属性，不传就静默回退 debug 签名）
cd manager && ./gradlew :app:assembleRelease \
  -PKEYSTORE_FILE=/root/build/ksunext-sukui.jks \
  -PKEYSTORE_PASSWORD=****** -PKEY_ALIAS=key -PKEY_PASSWORD=******
```

没有 `cross` 时用 cargo 直编，NDK 环境变量整套见 `BUILD-RESULT.md` 第 3 节（实测可跑）。产物在
`manager/app/build/outputs/apk/release/`。

内核侧的管理器签名校验已删除：`kernel/manager/apk_sign.c` 的 `is_manager_apk()` 只比对包名
（`KSU_MANAGER_PACKAGE`，缺省回退 `"com.sukisu.nova"`），不再读 APK 的 v2 签名块。因此换任何 keystore 签名都不影响
内核认管理器，也不再需要 `KSU_NEXT_MANAGER_SIZE/HASH`：

```bash
make O=out ARCH=arm64 KSU_MANAGER_PACKAGE=com.sukisu.nova -j$(nproc)
```

需要恢复签名校验时：把 `is_manager_apk()` 改回 `return check_v2_signature(path, EXPECTED_MANAGER_SIZE, EXPECTED_MANAGER_HASH);`
（校验代码仍在文件里），再用 `scripts/manager-signature.ps1` 算出 size/hash 带进内核编译。

## 4. 已知限制

1. **KPM、内核扩展（CPU/版本伪装）、动态 manager、内置 Zygisk、umount 路径管理**：KernelSU-Next 内核没有对应 ioctl，
   走的是说明页；想要这些功能只能把 SukiSU 的 `kernel/extension/*` 与 `KSU_IOCTL_EXTENSION` 反向移植进 Next 内核。
2. **mount hide 开关**：设置项会显示为不支持；`ksud feature check mount_hide` 在 Next 返回错误，已折算成 `unsupported`。
3. **临时授权（temporary-root）**：Next 的 profile 没有租期概念，相关操作会失败并提示，不会破坏已有 profile。
4. **magica**：manifest 里的 zygotePreload 仍在，JNI 符号也保留，但 `late-load --magica` 在 Next 的 ksud 里不存在，实际不生效。
5. 本树**已在 WSL 完成实际编译验证**：KSUNext 的 ksud 交叉编译通过，`assembleDebug` / `assembleRelease`（含 R8）
   均为 BUILD SUCCESSFUL，产物与实测环境、参数见 `BUILD-RESULT.md`。**但只有编译验证，没有真机运行验证**——
   JNI 的 ioctl 语义、模块安装、boot 刷写、SuSFS 开关都需要上机过一遍。
