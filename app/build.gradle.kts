import java.io.FileInputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// امضای release از keystore.properties (اختیاری؛ بدون آن، release امضانشده ساخته می‌شود)
val keystoreProps = Properties()
val keystoreFile = rootProject.file("keystore.properties")
val hasKeystore = keystoreFile.exists()
if (hasKeystore) keystoreProps.load(FileInputStream(keystoreFile))

// شمارهٔ نسخهٔ خودکار از زمان ساخت (به وقت تهران): versionCode = دقیقه‌ها از ابتدای ۲۰۲۶
val buildStamp: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Tehran"))
val autoVersionCode: Int = ((buildStamp.toEpochSecond() - 1767225600L) / 60L).toInt()
val autoVersionName: String = buildStamp.format(DateTimeFormatter.ofPattern("yyyy.MM.dd-HHmm"))

android {
    namespace = "com.denanex.aod"
    compileSdk = 34

    if (hasKeystore) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.denanex.aod"
        minSdk = 26
        targetSdk = 34
        versionCode = autoVersionCode
        versionName = autoVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Lint پیش از release خاموش است (دانلود ابزار lint در بعضی شبکه‌ها ناموفق است)
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// ---------------------------------------------------------------------------------------------------------
// انتشار: gradlew publishApk (یا publish.bat)
// APK را می‌سازد، aod.json (شمارهٔ نسخه برای به‌روزرسانی خودکار) را کنارش می‌نویسد و هر دو را با scp
// به مقصد «target» در publish.properties می‌فرستد. اول APK و بعد aod.json.
// پیام‌ها انگلیسی‌اند، چون پنجرهٔ cmd ویندوز فارسی را درهم نشان می‌دهد.
// ---------------------------------------------------------------------------------------------------------
val publishVariant = if (hasKeystore) "release" else "debug"
tasks.register<Exec>("publishApk") {
    group = "publishing"
    description = "ساخت APK و فرستادن به سرور به‌روزرسانی"
    dependsOn("assemble" + publishVariant.replaceFirstChar { it.uppercase() })
    doFirst {
        val pf = rootProject.file("publish.properties")
        if (!pf.exists()) throw GradleException("publish.properties is missing: copy publish.properties.example to publish.properties and set target.")
        val target = Properties().apply { load(FileInputStream(pf)) }.getProperty("target")?.trim()
        if (target.isNullOrEmpty()) throw GradleException("publish.properties: target is empty.")
        val apk = File(layout.buildDirectory.get().asFile, "outputs/apk/$publishVariant/app-$publishVariant.apk")
        if (!apk.exists()) throw GradleException("APK was not built: $apk")
        val up = File(apk.parentFile, "aod.apk")
        apk.copyTo(up, overwrite = true)
        val json = File(apk.parentFile, "aod.json")
        json.writeText("{\n  \"version_code\": $autoVersionCode,\n  \"version_name\": \"$autoVersionName\",\n  \"apk\": \"aod.apk\"\n}\n")
        commandLine("scp", up.absolutePath, json.absolutePath, target)
        println("Uploading version $autoVersionName ($publishVariant) ...")
    }
    doLast { println("Done. Phones get the new version with \"check for update\" in the app.") }
}
