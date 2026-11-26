# SukiSU-Next

把 **SukiSU 管理器前端**移植到 **KernelSU-Next 内核后端**的第三方嫁接项目。不重编内核，靠一个 root 桥让前端和一个它不被识别的内核通信。

> 非官方项目，与 KernelSU-Next 团队、SukiSU 团队、D-R-99、ShirkNeko 均无关联。

## 它是什么

SukiSU 的管理器（`manager/`，Nova 界面，GPL-3.0）本来只跟 SukiSU 自己的内核说话。KernelSU-Next 的内核（`kernel/` + `uapi/` + `userspace/ksud`，GPL-2.0）只认自己编译时钉死的 manager 签名与包名，所以换一个管理器直连必然 `EPERM`。

本项目的做法是**不动内核**，加一层桥：

```
 SukiSU 前端 (com.appstor678.sukisunext)
        │  JNI ksu.cc
        ├──────────── 直连 ioctl（被拒，EPERM）
        └──────────── AF_UNIX + 令牌 ─→ ksud proxy 守护（root）
                                        │  ioctl 转发
                                        │  遇 EPERM 时借内核钉死的 manager uid
                                        ▼
                                   KernelSU-Next 驱动
```

三个关键点：

1. **`ksud proxy`**（`userspace/ksud/src/proxy.rs`）—— 以 root 常驻，持有驱动 fd，转发前端发来的原始 ioctl。
2. **uid 借用**（`ksucalls.rs` / `proxy.rs`）—— `is_manager()` 比的是 real uid，少数 manager-only 命令（`GET/SET_APP_PROFILE`）连 root 都不给，于是临时 `setresuid(manager_appid, manager_appid, 0)` 打一次 ioctl 再换回 root。
3. **写路径兼容**（`userspace/ksud/src/suki_compat.rs`）—— SukiSU 前端改 profile 用的是 `ksud temporary-root apply <uid> <mode> <expected> <json>`，KernelSU-Next 的 ksud 只有 `profile set`，这里把前者的 camelCase JSON 翻译成内核 `app_profile` 结构，并用 `expectedRoot` 做陈旧校验。
4. **su 授权请求**（`proxy.rs` + `su.rs` + `SuRequestActivity.kt`）—— KernelSU-Next 的内核没有"待批准的 su 请求"：uid 不在白名单时 `su` 直接以原身份静默执行。这里自己补了这条链路：未授权 uid 调 `su` 时，ksud 通过 `/data/local/tmp/.ksu-req.sock`（0666，`SO_PEERCRED` 校验 uid）把请求交给 root 桥，桥用 `am start` 拉起 `SuRequestActivity` 弹框；用户点"允许"后 Activity 用桥写入 `allow_su=true` 并回传决定（桥自定义 op `0xFFFFFFFF`），ksud 再重新 `exec` 同一个 `su` 入口，由内核 sucompat 在 execve 时正式提权。拒绝则原样以非 root 身份继续。

`manager/` 侧另加了 `SuBridge.kt`（起桥、探活）与 `is_manager()` 的桥兜底判断，桥起来后前端能力集与"内核认得的管理器"一致。

## 当前状态（真机验证）

测试机：KernelSU-Next `v3.4.2-legacy-susfs-v2`（uapi 5），内核 `4.14.357-Azuronix-COS`，钩子 Manual，SuSFS v2.3.0 非 GKI，SELinux permissive。

| 能力 | 状态 |
| --- | --- |
| 首页状态 / 内核版本 / 钩子类型 / 计数 | 可用 |
| 超级用户列表、App Profile 读取 | 可用 |
| 授予 / 撤销超级用户（UI 点开关） | 可用（内核回读 `allow_su` true↔false 已验证） |
| 未授权应用的 `su` 请求弹框、允许后提权 | 可用（真机验证：弹框 → 允许 → `uid=0(root)`；拒绝 → 保持原 uid） |
| 模块列表、启用 / 禁用（UI 点开关） | 可用（`/data/adb/modules/*/disable` 落盘已验证） |
| 模块安装 / 卸载、`ksud` 直通命令（`feature`、`umount`、`profile`、`susfs`） | 走 root shell，可用 |
| 临时 Root 租约（`timed`） | 本机内核无 lease 表，按永久授权落地，`status` 恒返回 `none` |
| Zygisk / KPM / 扩展 | 内核无对应能力，相关页面无后端 |

已知前提：桥本身要以 root 起来，所以**本应用自己必须先能拿到一次 `su`**。首次安装（或换 uid 重装）后，如果首页显示"未安装"，先用官方 KernelSU-Next 管理器把本应用加入超级用户，或 `adb shell` 下 root 执行：

```bash
ksud profile get com.android.shell > /data/local/tmp/p.json   # 取一份模板
# 把 key 改成 com.appstor678.sukisunext、uid 改成 dumpsys 里的真实 uid，allow_su 置 true
ksud profile set /data/local/tmp/p.json
```

桥起来之后，其余应用的授权请求全部走上面的弹框链路。设备上会留下 `/data/adb/ksud-bridge` 与 `/data/local/tmp/.ksu-req.sock`。

## 构建

依赖：Android NDK、Rust `aarch64-linux-android` target、JDK、WSL 或 Linux 环境。

```bash
# 1) 先编 ksud（会同时作为 libksud.so 打进 APK）
bash scripts/build-ksud.sh

# 2) 再把 ksud 放进 jniLibs 并打 APK
bash scripts/build-apk.sh
```

`scripts/build-apk.sh` 里的 keystore 参数需要换成你自己的签名。产物在 `manager/app/build/outputs/apk/release/`。

两个脚本里的路径（`/root/build/KSNext-SukiUI`、NDK 版本）按你的环境改。

## 目录

```
kernel/          KernelSU-Next 内核（未改，参考用）
uapi/            内核 uapi 定义
userspace/ksud/  ksud：proxy 桥 + suki-compat 命令
manager/         SukiSU 管理器前端（包名、品牌、图标、了解页已改）
scripts/         构建脚本
PORTING.md       移植记录
PORT-CHANGES.diff 相对上游的完整 diff
```

## 许可

双协议，按目录分区：

- `kernel/`、`uapi/`、`userspace/`、`website/` — **GPL-2.0**（根 `LICENSE`、`LICENSES/GPL-2.0.txt`）
- `manager/` — **GPL-3.0**（`manager/LICENSE`、`LICENSES/GPL-3.0.txt`）

两者不合并成单一作品，通过进程边界（socket / CLI）交互。详见 [NOTICE.md](NOTICE.md)。

## English

A third-party port that runs the SukiSU manager frontend against a KernelSU-Next kernel **without rebuilding the kernel**. A root-side `ksud proxy` daemon forwards the manager's ioctls and borrows the kernel-pinned manager uid for the few manager-only commands; `ksud temporary-root` provides the SukiSU CLI spelling the frontend expects. Dual-licensed by directory: KernelSU-Next parts GPL-2.0, SukiSU manager GPL-3.0. Not affiliated with either upstream.
