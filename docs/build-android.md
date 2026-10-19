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

Bump `android/version.properties`, build, then `deploy/publish-apk.sh app/build/outputs/apk/debug/app-debug.apk "notes"`. The app checks `/api/app/version` on start (设置 → 检查更新).
