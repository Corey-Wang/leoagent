import java.util.Properties
val vp = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.leoagent.app"; compileSdk = 34
    defaultConfig { applicationId = "io.leoagent.app"; minSdk = 26; targetSdk = 34; versionCode = vp.getProperty("versionCode").toInt(); versionName = vp.getProperty("versionName") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Release signing: put a keystore OUTSIDE the repo and point to it via ~/.gradle/gradle.properties
    // (leoagent.storeFile / leoagent.storePassword / leoagent.keyAlias / leoagent.keyPassword). See docs/build-android.md.
    val sf = (project.findProperty("leoagent.storeFile") as String?)
    if (sf != null) signingConfigs.create("release") {
        storeFile = file(sf); storePassword = project.property("leoagent.storePassword") as String
        keyAlias = project.property("leoagent.keyAlias") as String; keyPassword = project.property("leoagent.keyPassword") as String
    }
    buildTypes { getByName("release") { isMinifyEnabled = false; if (sf != null) signingConfig = signingConfigs.getByName("release") } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("io.noties.markwon:inline-parser:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    testImplementation("junit:junit:4.13.2")
}
