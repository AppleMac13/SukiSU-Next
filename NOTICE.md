# 许可 / Licensing

本仓库是**双协议**的：两个上游项目各自的许可证按目录范围继续生效，互不覆盖。

This repository is **dual-licensed**: each upstream keeps its own license, scoped to its own directory tree.

## 目录 → 许可证对应表 / Path to license map

| 目录 / Path | 上游 / Upstream | 许可证 / License |
| --- | --- | --- |
| `kernel/` | KernelSU-Next | GPL-2.0（见 `LICENSE`、`LICENSES/GPL-2.0.txt`） |
| `uapi/` | KernelSU-Next | GPL-2.0 |
| `userspace/`（含 `ksud`） | KernelSU-Next | GPL-2.0 |
| `website/` | KernelSU-Next | GPL-2.0 |
| `scripts/`、`build.sh`、`justfile` | KernelSU-Next | GPL-2.0 |
| `manager/`（SukiSU 前端） | SukiSU-Ultra | GPL-3.0（见 `manager/LICENSE`、`LICENSES/GPL-3.0.txt`） |

根目录 `LICENSE` 是 **GPL-2.0** 全文，覆盖上表中 GPL-2.0 的部分；`manager/` 内部自带 **GPL-3.0** 全文，只覆盖 `manager/` 目录。两者全文另存于 `LICENSES/`。

The root `LICENSE` is the full **GPL-2.0** text and covers the GPL-2.0 paths above. `manager/` carries its own full **GPL-3.0** text covering that directory only. Both texts are also kept in `LICENSES/`.

## 移植方新增的代码 / Code added by this port

以下文件由本移植项目编写，随所在目录的许可证发布：

| 文件 / File | 所在许可 / Released under |
| --- | --- |
| `userspace/ksud/src/proxy.rs` | GPL-2.0（`userspace/` 目录） |
| `userspace/ksud/src/suki_compat.rs` | GPL-2.0 |
| `userspace/ksud/src/{cli.rs,main.rs,ksucalls.rs}` 中的改动 | GPL-2.0 |
| `manager/app/src/main/java/com/sukisu/nova/backend/SuBridge.kt` | GPL-3.0（`manager/` 目录） |
| `manager/app/src/main/cpp/{ksu.cc,ksu.h,jni.cc}` 中的改动 | GPL-3.0 |
| `manager/app/src/main/assets/learn_more.txt`、图标资源 | GPL-3.0 |

## 上游 / Upstreams

- KernelSU-Next — <https://github.com/KernelSU-Next/KernelSU-Next>（GPL-2.0）
- SukiSU-Ultra — <https://github.com/SukiSU-Ultra/SukiSU-Ultra>（GPL-3.0）
- KernelSU（KernelSU-Next 的上游）— <https://github.com/tiann/KernelSU>（GPL-2.0）

`kernel/`、`uapi/`、`userspace/`、`website/` 的版权归上述 KernelSU / KernelSU-Next 作者；`manager/` 的版权归 SukiSU 作者。本仓库只做前后端嫁接与品牌化，不声称对这些上游代码拥有版权。

Copyright of `kernel/`, `uapi/`, `userspace/`, `website/` belongs to the KernelSU / KernelSU-Next authors; copyright of `manager/` belongs to the SukiSU authors. This repository only recombines the two halves and rebrands the UI.

## 分发注意 / Distribution notes

- GPL-2.0 与 GPL-3.0 不兼容合并为单一作品，因此本项目**按目录分区发布**：内核侧与 `ksud` 侧仍是 GPL-2.0 的独立作品，`manager/` 是 GPL-3.0 的独立作品，二者通过 socket / 命令行协议（`proxy` 桥、`temporary-root`）交互，不构成单一派生作品。
- GPL-2.0 与 GPL-3.0 are not compatible for merging into one work. The two halves are distributed as separate works that communicate over a process boundary (the `ksud proxy` socket and the `ksud` CLI), not as a single derived work.
- 重新分发二进制时须同时提供对应源码与许可证全文；不得附加额外限制。
- Redistributing binaries requires shipping the corresponding sources and the full license texts, with no additional restrictions.
