plugins { alias(libs.plugins.android.application) }
val authRoot = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR").orNull?.let { file(it).canonicalFile }
android {
    namespace = "com.shilapi.xcertplay.geely"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 30
        targetSdk = 30
        versionCode = 104
        versionName = "0.1.4-geely-minimal"
    }
    authRoot?.let { sourceSets.getByName("main").assets.srcDir(it) }
    buildTypes {
        debug {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "receiver-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    // Private Android 11 receiver; Google Play publication is outside this build.
    lint { disable += "ExpiredTargetSdkVersion" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":geely-core"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
val verifyAuthentication by tasks.registering {
    val localAssets = authRoot
    doLast {
        check(localAssets != null) { "DIPLAY_AUTH_ASSETS_DIR is required for a standalone receiver APK" }
        check(listOf("identity.pk8", "certificate.p7b").all { localAssets.resolve("offline-mfi/$it").let { f -> f.isFile && f.length() > 0 } }) { "Missing local authentication files" }
    }
}
tasks.register("assembleStandaloneDebug") { dependsOn(verifyAuthentication, "assembleDebug") }
tasks.named("preBuild") { mustRunAfter(verifyAuthentication) }

val rejectUnexpectedAuthentication by tasks.registering {
    val localAssets = authRoot
    doLast {
        if (localAssets != null) {
            val expected = setOf("offline-mfi/identity.pk8", "offline-mfi/certificate.p7b")
            val actual = localAssets.walkTopDown().filter { it.isFile }.map { it.relativeTo(localAssets).invariantSeparatorsPath }.toSet()
            check(actual == expected) { "Authentication input must contain exactly the two selected offline-mfi files" }
        }
    }
}
tasks.named("preBuild") { dependsOn(rejectUnexpectedAuthentication) }
