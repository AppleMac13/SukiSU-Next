//
// Created by weishu on 2022/12/9.
//
// SukiSU Nova frontend adapted to the KernelSU-Next kernel driver.
//

#ifndef KERNELSU_KSU_H
#define KERNELSU_KSU_H

#include <cstdint>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <utility>

#include "uapi/ksu.h"

uint32_t get_kernel_uapi_version();

uint32_t get_manager_uapi_version();

uint32_t get_version();

bool uid_should_umount(int uid);

bool is_safe_mode();

bool is_lkm_mode();

bool is_lkm_bundled();

bool is_late_load_mode();

bool is_manager();

bool is_pr_build();

using p_key_t = char[KSU_MAX_PACKAGE_NAME];

bool set_app_profile(const app_profile *profile);

int get_app_profile(app_profile *profile);

// KernelSU-Next exposes GET_HOOK_MODE (ioctl 98) and GET_VERSION_TAG (ioctl 99).
// SukiSU's GET_FULL_VERSION (100) / HOOK_TYPE (101) do not exist on this backend,
// so the two helpers below derive the values the ported frontend expects.
const char *get_hook_mode(void);

const char *get_version_tag(void);

bool get_full_version(char *buff, int size);

bool get_hook_type(char *buff, int size);

uid_t get_manager_appid(void);

bool is_zygisk_enabled();

// Su compat
bool set_su_enabled(bool enabled);

bool is_su_enabled();

// Kernel umount
bool set_kernel_umount_enabled(bool enabled);

bool is_kernel_umount_enabled();

// ADB root
bool set_adb_root_enabled(bool enabled);

bool is_adb_root_enabled();

// SELinux hide
int set_selinux_hide_enabled(bool enabled);

bool is_selinux_hide_enabled();

// Avc spoof
bool set_avc_spoof_enabled(bool enabled);

bool is_avc_spoof_enabled();

// Mount hide: SukiSU fork-local feature (KSU_FEATURE_MOUNT_HIDE = 16) that the
// KernelSU-Next kernel does not implement. Kept as no-ops so the ported UI can
// call them unconditionally.
int set_mount_hide_enabled(bool enabled);

bool is_mount_hide_enabled();

bool get_allow_list(struct ksu_new_get_allow_list_cmd *);

// Root bridge: when the kernel pins the manager to a certificate we do not own,
// ioctls are replayed through a privileged helper started via su.
void set_proxy_config(const char *path, const char *token);

bool proxy_available();

inline std::pair<int, int> legacy_get_info() {
    int32_t version = -1;
    int32_t flags = 0;
    int32_t result = 0;
    prctl(0xDEADBEEF, 2, &version, &flags, &result);
    return {version, flags};
}

#endif //KERNELSU_KSU_H
