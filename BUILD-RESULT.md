# 编译验证结果（WSL）

结论：**KSUNext 后端 + SukiSU Nova 前端 已在 WSL 里完成全量编译**，debug / release（R8 混淆 + 自有密钥签名）均 BUILD SUCCESSFUL，产物可直接装机。

## 1. 产物

| 产物 | 路径 | 说明 |
|---|---|---|
| 主交付 APK | `ksu-port/out/SukiSU_Nova_KSUNext_30997-release.apk`（8.8 MB） | release、R8 混淆、资源压缩、自有证书签名 |
| debug APK（备用） | WSL `manager/app/build/outputs/apk/debug/SukiSU_Nova_dev_30997-debug.apk`（32 MB） | 未优化，含调试符号，用来自查 |
| ksud 二进制 | `ksu-port/out/ksud-arm64`（4.1 MB） | aarch64 PIE，已 strip，来自 Next userspace 源码 |
| 签名证书 DER | `ksu-port/out/manager-cert.der`（822 B） | 内核要钉的就是这份 |
| 签名密钥库 | `ksu-port/out/ksunext-sukui.jks` | 自建，密码见下；用于后续升级同签名覆盖安装 |

APK 包名 `com.sukisu.nova`（与前端原包名一致），versionCode 30997，`versionName=dev`（浅克隆没有 tag，属正常）。四个 ABI 的 `libkernelsu.so` / `libadbroot.so` 全部编进去了；`libksud.so` 只有 `arm64-v8a`（ksud 只编了 aarch64 一个 target），32 位 ARM 设备上会缺 ksud。

## 2. 管理器签名校验：已在核内删除

`kernel/manager/apk_sign.c` 的 `is_manager_apk()` 不再调用 `check_v2_signature()`，改为只比对包名：

- 剥掉路径末尾的 `/base.apk`，用 `get_pkg_from_apk_dir_path()` 取出包名，与 `KSU_MANAGER_PACKAGE` 比较；
- 编译时没传 `KSU_MANAGER_PACKAGE` 就回退到 `"com.sukisu.nova"`（本树前端包名固定）；
- 签名校验代码（`check_v2_signature` / `check_block` 等）原样保留在文件里，只是没人调用，需要时可一行改回。

副作用：`KSU_NEXT_MANAGER_SIZE` / `KSU_NEXT_MANAGER_HASH` 不再影响行为，编内核时传不传都行。下面是之前算出来的值，留档备用：

```
KSU_MANAGER_PACKAGE=com.sukisu.nova
KSU_NEXT_MANAGER_SIZE=0x336          # 十进制 822（已不参与判定）
KSU_NEXT_MANAGER_HASH=d09dd186f025f404cef16a2273a9744ddb218deb873fa424c2a23c5c9a85cbef（已不参与判定）
```

也就是说：**现在换成任何证书签名的 APK，只要包名是 `com.sukisu.nova` 就能被内核认作管理器**，内核不用重编第二次。

补丁：`apk_sign-signature-off.patch`（1.1 KB，单文件单 hunk）。

签名密钥：`ksunext-sukui.jks`，alias `key`，store/key 密码均为 `ksunextsukisu`，有效期 10000 天。别丢——丢了以后只能重编内核。

## 3. 复现命令（WSL，实测）

工具链：Ubuntu WSL + JDK 21.0.12.1、SDK `/opt/android-sdk`（platform 37 / build-tools 37.0.0 / NDK 29.0.14206865）、rustc 1.100.0 + `aarch64-linux-android` target。

```bash
# 1) ksud（cargo 直编，不用 cross；NDK 环境变量必须齐）
cd /root/build/KSNext-SukiUI/userspace/ksud
NP=/opt/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64
SYS=$NP/sysroot
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/29.0.14206865 ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--sysroot=$SYS -I$SYS/usr/include/aarch64-linux-android -I$SYS/usr/include"
export CC_aarch64_linux_android=$NP/bin/aarch64-linux-android26-clang
export CXX_aarch64_linux_android=$NP/bin/aarch64-linux-android26-clang++
export AR_aarch64_linux_android=$NP/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$CC_aarch64_linux_android
export CARGO_NET_GIT_FETCH_WITH_CLI=true
cargo build --release --target aarch64-linux-android   # 3m37s

# 2) 塞进 jniLibs（这个文件就是 app 里 exec 的 ksud）
cp target/aarch64-linux-android/release/ksud \
   /root/build/KSNext-SukiUI/manager/app/src/main/jniLibs/arm64-v8a/libksud.so

# 3) 编 + 签（apksign 插件直接读 -P 属性，不需要 sign.properties）
cd /root/build/KSNext-SukiUI/manager
./gradlew :app:assembleRelease \
  -PKEYSTORE_FILE=/root/build/ksunext-sukui.jks \
  -PKEYSTORE_PASSWORD=ksunextsukisu -PKEY_ALIAS=key -PKEY_PASSWORD=ksunextsukisu
```

实测耗时：`compileDebugKotlin` 12m56s（首次），`assembleDebug` 6m04s，`assembleRelease`（含 R8）22m44s，带 `-P` 参数的增量 release 1m22s。

## 4. 编译期踩到的坑（都已解决，重装环境会再遇到）

1. `github.com` / `api.github.com` 在两个系统里都被 hosts 指到 `127.0.0.1`（等于屏蔽），WSL 侧完全不通。绕法：Windows 侧 clone 依赖到 `ksu-port/vendor/`，拷进 `/root/vendor/`，用 `git config --global --add url."file:///root/vendor/<name>".insteadOf "<原URL>"` 改写 cargo 的 git 拉取（必须 `--add`，直接赋值会互相覆盖）。
2. `gradlew: cannot execute: required file not found`：Windows 检出带 CRLF，`sed -i 's/\r$//' gradlew && chmod +x gradlew`。
3. ksud bindgen 报 `asm/ioctl.h not found`：按 `userspace/ksud/.cargo/config.example.toml` 补 `BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android`（见上）。
4. 上游 `cpp/uapi/*.h` 是 git symlink，Windows 检出变成文本文件无法编译，已改成真实头文件副本。
5. 不传 keystore 时插件静默回退 debug 签名（日志 `Apksign fallbacks to use debug singature`），装机后内核不认——务必用 `-P` 指定。

## 5. 还没验证的部分（需要在真机上做）

- 编译通过 ≠ 运行正确。JNI 层是按 Next 内核的 ioctl 语义重写的，`GET_INFO` 双路径、`GET_VERSION_TAG`、`GET_HOOK_MODE` 这几个都要上机才见真章。
- 模块安装（`ksud install` / `module install`）、boot 刷写（`ksud install <boot.img>`）、SuSFS 开关、sulog——代码路径已对齐 Next 的 CLI 参数，但没跑过真机。
- 已主动关门的页面（KPM、内核扩展/CPU/版本伪装、动态 manager、内置 Zygisk、umount 路径管理）现在是说明页，不去碰不存在的 ioctl。

## 6. 品牌化改动（com.appstor678.sukisunext / SukiSU-Next）

- 包名 `com.appstor678.sukisunext`（`manager/app/build.gradle.kts` 的 defaultManagerPackageName），app 名 `SukiSU-Next`；ksud 三处默认 `--package-name` 同步改，late-load 组件名 `{pkg}/com.sukisu.nova.ui.MainActivity`（类路径不变）。
- 首页删掉"支持开发"捐赠卡片（Miuix / Material 两套都删），只剩"了解"一项；点它不再跳 `sukisu.org/guide`，改为 `Route.LearnMore` 打开 `assets/learn_more.txt`（内容取自桌面那个 txt，原样不加工）。
- 内置图标：删掉 KernelSU / SukiSU 标志 / SukiSU 线稿三套（mipmap、anydpi、drawable 前景、manifest 里 6 个 alias 全删）；保留"内置图标"页，条目只剩两个 —— `SukiSU-Next`（新图标 icon.png，默认）与 `SukiSU`（原默认图）。
- 新图标从 `icon.png`（1016×1016，无 alpha）按 5 个密度生成 `ic_launcher_next(_round/_fore)`，外加 anydpi-v26 自适应 xml；生成脚本 `ksu-port/tools/make-icons.ps1`。
- 坑：WSL 里 res 同时存在 `ic_launcher.webp` 和 `ic_launcher.png`，全量重打包时报 Duplicate resources；已删 .webp（Windows 树本来只有 png）。
