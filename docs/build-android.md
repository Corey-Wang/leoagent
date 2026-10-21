# Building the Android app

Requirements: JDK 17+ and the Android SDK (platform 34). Create `android/local.properties` with `sdk.dir=/path/to/Android/sdk` (it is git-ignored).

```bash
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

`settings.gradle.kts` lists the Aliyun Maven mirrors first (handy in mainland China) followed by `google()` / `mavenCentral()`; remove them if you prefer.

## Release signing

Debug builds are signed with your local Android debug key (never commit it). For release builds create a keystore **outside the repository** and add to `~/.gradle/gradle.properties`:

```
leoagent.storeFile=/absolute/path/leoagent-release.jks
leoagent.storePassword=…
leoagent.keyAlias=leoagent
leoagent.keyPassword=…
```

then `./gradlew assembleRelease`. In-app updates require every APK to be signed with the same key.

## Publishing updates to your phone

Bump `android/version.properties`, build with `./gradlew assembleRelease`, then run `../deploy/publish-apk.sh app/build/outputs/apk/release/app-release.apk "notes"` from `android/`. Verify the package ID, version and signing certificate against the previously published APK first. In-app upgrades require the same signing identity. The app checks `/api/app/version` on start (设置 → 检查更新).


## 隔离 Android 验收

只对明确的 Android 模拟器运行真实设备流程，不安装到物理手机。验证包使用独立的 `io.leoagent.app.verification` application ID：

```bash
./gradlew -Pleoagent.verification :app:assembleDebug :app:assembleDebugAndroidTest
python3 ../tests/android_integration.py
```

脚本会拒绝安装正式包名，并验证 Markdown 日夜主题渲染与对比度、Keystore、篡改拒绝、旧明文凭据迁入加密存储、旧二维码填入与保存、一次性配对、任务提交和进程重启后的凭据恢复。
