package com.sukisu.nova.backend

/**
 * Capability map of the kernel backend this manager build talks to.
 *
 * This build ships the SukiSU-Next frontend on top of the KernelSU-Next driver.
 * KernelSU-Next speaks the same UAPI (version 6) for every core operation -
 * superuser allowlist, app profiles, feature toggles, sulog, module lifecycle -
 * but it does not implement the fork-only kernel interfaces SukiSU added later.
 * Everything listed below as `false` is therefore gated in the UI instead of
 * failing halfway through an operation.
 */
object BackendCapabilities {

    const val KERNEL_FLAVOR = "KernelSU-Next"

    /** Kernel Patch Module support (needs the SukiSU + KernelPatch kernel). */
    const val supportsKpm = false

    /**
     * `KSU_IOCTL_EXTENSION`: CPU spoof, kernel-side version spoof, umount path
     * bookkeeping and dynamic-manager switching.
     */
    const val supportsKernelExtensions = false

    /** Switching the manager package at runtime (dynamic manager). */
    const val supportsDynamicManager = false

    /** Built-in zygisk toggle exposed by the fork's kernel. */
    const val supportsBuiltinZygisk = false

    /** `KSU_FEATURE_MOUNT_HIDE` (feature id 16). */
    const val supportsMountHide = false

    /**
     * SukiSU's extended `ksud umount list/save/apply/clear-custom` path manager.
     * KernelSU-Next only keeps the minimal `kernel umount add|del|wipe` list.
     */
    const val supportsUmountManager = false

    /** `ksud late-load --magica` app-zygote bootstrap. */
    const val supportsMagica = false

    /**
     * Kernel-side su log streaming. Both backends implement KSU_FEATURE_SULOG
     * and the sulog fd ioctl, so this stays available.
     */
    const val supportsSulog = true

    /** SuSFS is part of the KernelSU-Next kernel tree (kernel/susfs). */
    const val supportsSuSfs = true
}
