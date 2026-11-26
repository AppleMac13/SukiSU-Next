//
// Created by weishu on 2022/12/9.
//
// SukiSU Nova frontend adapted to the KernelSU-Next kernel driver.
//

#include <sys/prctl.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <utility>
#include <android/log.h>
#include <dirent.h>
#include <cstdlib>

#include <unistd.h>
#include <climits>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <fcntl.h>
#include <cerrno>
#include "ksu.h"

#ifndef LOG_TAG
#define LOG_TAG "KernelSU-Next-SukiUI"
#endif

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static int fd = -1;

static inline int scan_driver_fd() {
    const char *kName = "[ksu_driver]";
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) {
        return -1;
    }

    int found = -1;
    struct dirent *de;
    char path[64];
    char target[PATH_MAX];

    while ((de = readdir(dir)) != nullptr) {
        if (de->d_name[0] == '.') {
            continue;
        }

        char *endptr = nullptr;
        long fd_long = strtol(de->d_name, &endptr, 10);
        if (!de->d_name[0] || *endptr != '\0' || fd_long < 0 || fd_long > INT_MAX) {
            continue;
        }

        snprintf(path, sizeof(path), "/proc/self/fd/%s", de->d_name);
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n < 0) {
            continue;
        }
        target[n] = '\0';

        const char *base = strrchr(target, '/');
        base = base ? base + 1 : target;

        if (strstr(base, kName)) {
            found = (int) fd_long;
            break;
        }
    }

    closedir(dir);
    return found;
}

// ---------------------------------------------------------------------------
// Root bridge.
//
// On a build whose kernel pins the manager to an APK certificate we never
// signed, every driver ioctl answers EPERM. The same kernel does serve uid 0,
// so the app asks su for a one-shot privileged helper (ksud's hidden `proxy`
// command) and replays the ioctls through a unix socket owned by that helper.
// -------------------------------------------------------------------

static char g_proxy_path[192] = {0};
static char g_proxy_token[128] = {0};
static int g_proxy_token_len = 0;
static int g_proxy_fd = -1;
static bool g_proxy_ok = false;

void set_proxy_config(const char *path, const char *token) {
    if (!path || !token) return;
    snprintf(g_proxy_path, sizeof(g_proxy_path), "%s", path);
    snprintf(g_proxy_token, sizeof(g_proxy_token), "%s", token);
    g_proxy_token_len = (int) strlen(g_proxy_token);
}

static void proxy_disconnect() {
    if (g_proxy_fd >= 0) {
        close(g_proxy_fd);
        g_proxy_fd = -1;
    }
}

static bool read_exact(int s, void *buf, size_t len) {
    char *p = static_cast<char *>(buf);
    size_t got = 0;
    while (got < len) {
        ssize_t n = TEMP_FAILURE_RETRY(read(s, p + got, len - got));
        if (n <= 0) return false;
        got += static_cast<size_t>(n);
    }
    return true;
}

static bool write_exact(int s, const void *buf, size_t len) {
    const char *p = static_cast<const char *>(buf);
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = TEMP_FAILURE_RETRY(write(s, p + sent, len - sent));
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

static int proxy_connect() {
    if (g_proxy_fd >= 0) return 0;
    if (g_proxy_path[0] == '\0') return -1;

    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;

    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", g_proxy_path);

    if (TEMP_FAILURE_RETRY(connect(s, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr))) != 0) {
        LOGD("proxy connect failed: %s", strerror(errno));
        close(s);
        return -1;
    }

    uint32_t n = static_cast<uint32_t>(g_proxy_token_len);
    if (!write_exact(s, &n, sizeof(n)) ||
        (n > 0 && !write_exact(s, g_proxy_token, n))) {
        close(s);
        return -1;
    }

    g_proxy_fd = s;
    g_proxy_ok = true;
    LOGD("root bridge connected");
    return 0;
}

bool proxy_available() {
    return proxy_connect() == 0;
}

// Replays one ioctl. KernelSU encodes no argument size in its ioctl numbers
// (`_IOC(..., 0)`), so the caller has to state it - the C++ side knows the
// struct, the Rust daemon trusts the header.
static int proxy_ioctl(unsigned long op, void *arg, size_t size) {
    if (proxy_connect() < 0) return -EPERM;

    uint32_t arg_size = static_cast<uint32_t>(size);
    uint32_t req[2] = {static_cast<uint32_t>(op), arg_size};

    if (!write_exact(g_proxy_fd, req, sizeof(req)) ||
        (size > 0 && arg != nullptr && !write_exact(g_proxy_fd, arg, size))) {
        proxy_disconnect();
        return -EPERM;
    }

    int32_t ret = -EPERM;
    uint32_t rsize = 0;
    if (!read_exact(g_proxy_fd, &ret, sizeof(ret)) ||
        !read_exact(g_proxy_fd, &rsize, sizeof(rsize)) || rsize != arg_size ||
        (size > 0 && arg != nullptr && !read_exact(g_proxy_fd, arg, size))) {
        proxy_disconnect();
        return -EPERM;
    }

    return ret;
}

static int ksuctl_raw(unsigned long op, void *arg, size_t size) {

    if (fd < 0) {
        fd = scan_driver_fd();
    }

    int ret = ioctl(fd, op, arg);

    // The kernel refuses a manager it does not recognise with EPERM/EBADF;
    // that is the only case worth paying for a round trip through the bridge.
    if (ret < 0 && (errno == EPERM || errno == EACCES || errno == EBADF || errno == ENODEV)) {
        int bridged = proxy_ioctl(op, arg, size);
        if (bridged != -EPERM) {
            return bridged;
        }
    }

    return ret;
}

template<typename T>
static int ksuctl(unsigned long op, T *arg) {
    return ksuctl_raw(op, static_cast<void *>(arg), sizeof(T));
}

static struct ksu_get_info_cmd g_version{};

struct ksu_get_info_cmd get_info() {
    if (!g_version.version) {
        if (ksuctl(KSU_IOCTL_GET_INFO, &g_version) < 0) {
            ksuctl(KSU_IOCTL_GET_INFO_LEGACY, &g_version);
            g_version.uapi_version = 0;
        }
    }
    return g_version;
}

uint32_t get_kernel_uapi_version() {
    auto info = get_info();
    return info.uapi_version;
}

uint32_t get_manager_uapi_version() {
    return KERNEL_SU_UAPI_VERSION;
}

uint32_t get_version() {
    auto info = get_info();
    return info.version;
}

bool get_allow_list(struct ksu_new_get_allow_list_cmd *cmd) {
    return ksuctl(KSU_IOCTL_NEW_GET_ALLOW_LIST, cmd) == 0;
}

bool is_safe_mode() {
    struct ksu_check_safemode_cmd cmd = {};
    ksuctl(KSU_IOCTL_CHECK_SAFEMODE, &cmd);
    return cmd.in_safe_mode;
}

bool is_lkm_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0;
    }
    return (legacy_get_info().second & KSU_GET_INFO_FLAG_LKM) != 0;
}

bool is_lkm_bundled() {
    auto info = get_info();
    return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0 &&
           (info.flags & KSU_GET_INFO_FLAG_BUNDLED) != 0;
}

bool is_late_load_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LATE_LOAD) != 0;
    }
    return false;
}

bool is_manager() {
    auto info = get_info();
    if (info.version > 0) {
        if ((info.flags & KSU_GET_INFO_FLAG_MANAGER) != 0) {
            return true;
        }
        // The kernel only flags the uid it was built for; once the root bridge
        // is up this app holds exactly that privilege set, so report the same
        // answer the kernel would give its own manager.
        return g_proxy_ok;
    }
    return legacy_get_info().first > 0 || g_proxy_ok;
}

bool is_pr_build() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_PR_BUILD) != 0;
    }
    return false;
}

bool uid_should_umount(int uid) {
    struct ksu_uid_should_umount_cmd cmd = {};
    cmd.uid = uid;
    ksuctl(KSU_IOCTL_UID_SHOULD_UMOUNT, &cmd);
    return cmd.should_umount;
}

bool set_app_profile(const app_profile *profile) {
    struct ksu_set_app_profile_cmd cmd = {};
    cmd.profile = *profile;
    return ksuctl(KSU_IOCTL_SET_APP_PROFILE, &cmd) == 0;
}

int get_app_profile(app_profile *profile) {
    struct ksu_get_app_profile_cmd cmd = {.profile = *profile};
    int ret = ksuctl(KSU_IOCTL_GET_APP_PROFILE, &cmd);
    *profile = cmd.profile;
    return ret;
}

bool set_su_enabled(bool enabled) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    cmd.value = enabled ? 1 : 0;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool is_su_enabled() {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (!(cmd.supported & KSU_FEATURE_FLAG_SUPPORTED)) {
        return false;
    }
    return cmd.value != 0;
}

static inline bool get_feature(uint32_t feature_id, uint64_t *out_value, bool *out_supported) {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (out_value) *out_value = cmd.value;
    if (out_supported) *out_supported = cmd.supported & KSU_FEATURE_FLAG_SUPPORTED;
    return true;
}

static inline bool set_feature(uint32_t feature_id, uint64_t value) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    cmd.value = value;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool set_kernel_umount_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_KERNEL_UMOUNT, enabled ? 1 : 0);
}

bool is_kernel_umount_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_KERNEL_UMOUNT, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

bool set_adb_root_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_ADB_ROOT, enabled ? 1 : 0);
}

bool is_adb_root_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_ADB_ROOT, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

int set_selinux_hide_enabled(bool enabled) {
    if (!set_feature(KSU_FEATURE_SELINUX_HIDE, enabled ? 1 : 0)) {
        return -errno;
    }
    return 0;
}

bool is_selinux_hide_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_SELINUX_HIDE, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

bool set_avc_spoof_enabled(bool enabled) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_AVC_SPOOF;
    cmd.value = enabled ? 1 : 0;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool is_avc_spoof_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_AVC_SPOOF, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

// SukiSU's KSU_FEATURE_MOUNT_HIDE (16) has no counterpart in the KernelSU-Next
// kernel; report the feature as absent and refuse the toggle.
int set_mount_hide_enabled(bool enabled) {
    (void) enabled;
    return -EOPNOTSUPP;
}

bool is_mount_hide_enabled() {
    return false;
}

const char *get_hook_mode(void) {
    static struct ksu_get_hook_mode_cmd cmd = {0};

    if (ksuctl(KSU_IOCTL_GET_HOOK_MODE, &cmd) == 0)
        return cmd.mode;

    return "Unknown";
}

uid_t get_manager_appid(void) {
    static struct ksu_get_manager_appid_cmd cmd = {0};

    if (ksuctl(KSU_IOCTL_GET_MANAGER_APPID, &cmd) == 0)
        return cmd.appid;

    return 0;
}

const char *get_version_tag(void) {
    static struct ksu_get_version_tag_cmd cmd = {0};

    if (ksuctl(KSU_IOCTL_GET_VERSION_TAG, &cmd) == 0)
        return cmd.tag;

    return "Unknown";
}

bool get_full_version(char *buff, int size) {
    if (!buff || size <= 0) {
        return false;
    }

    static char cached[255] = {0};
    static bool tried = false;

    if (!tried) {
        tried = true;
        const char *tag = get_version_tag();
        if (tag && tag[0] && strcmp(tag, "Unknown") != 0) {
            snprintf(cached, sizeof(cached), "%s", tag);
        }
    }

    if (cached[0] == '\0') {
        return false;
    }

    snprintf(buff, (size_t) size, "%s", cached);
    return true;
}

bool get_hook_type(char *buff, int size) {
    if (!buff || size <= 0) {
        return false;
    }

    const char *mode = get_hook_mode();
    if (!mode || mode[0] == '\0') {
        return false;
    }

    snprintf(buff, (size_t) size, "%s", mode);
    return true;
}

bool is_zygisk_enabled() {
    return !!getenv("ZYGISK_ENABLED");
}
