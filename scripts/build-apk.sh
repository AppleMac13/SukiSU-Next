set -e
K=/root/build/KSNext-SukiUI
cp $K/userspace/ksud/target/aarch64-linux-android/release/ksud $K/manager/app/src/main/jniLibs/arm64-v8a/libksud.so
ls -l $K/manager/app/src/main/jniLibs/arm64-v8a/libksud.so
cd $K/manager
sed -i 's/\r$//' gradlew
chmod +x gradlew
./gradlew :app:assembleRelease \
  -PKEYSTORE_FILE=/root/build/ksunext-sukui.jks \
  -PKEYSTORE_PASSWORD=ksunextsukisu \
  -PKEY_ALIAS=key \
  -PKEY_PASSWORD=ksunextsukisu 2>&1 | tail -40