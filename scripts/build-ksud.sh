set -e
export PATH=/root/.cargo/bin:$PATH
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/29.0.14206865
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
export NDK=$ANDROID_NDK_HOME
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
export PATH=$TC/bin:$PATH
export CC_aarch64_linux_android=$TC/bin/aarch64-linux-android26-clang
export CXX_aarch64_linux_android=$TC/bin/aarch64-linux-android26-clang++
export AR_aarch64_linux_android=$TC/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TC/bin/aarch64-linux-android26-clang
export CARGO_NET_GIT_FETCH_WITH_CLI=true
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--sysroot=$TC/sysroot -I$TC/sysroot/usr/include/aarch64-linux-android -I$TC/sysroot/usr/include"
cd /root/build/KSNext-SukiUI/userspace/ksud
cargo build --release --target aarch64-linux-android 2>&1 | tail -30