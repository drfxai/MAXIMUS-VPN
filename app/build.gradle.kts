import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

// import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  // alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.drfxai.maximusvpn"
    minSdk = 24
    targetSdk = 36
    // Keep this monotonic so installs from the pre-public builds can upgrade.
    versionCode = 28
    versionName = "1.0.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // The free config list's public key (DER, base64), derived in the release workflow from the
    // HUB_SIGNING_KEY secret. Without it the app refuses every list, as unsigned.
    val hubPublicKey = System.getenv("HUB_PUBLIC_KEY").orEmpty().trim()
    require(hubPublicKey.all { it.isLetterOrDigit() || it in "+/=" }) { "HUB_PUBLIC_KEY must be base64" }
    buildConfigField("String", "HUB_PUBLIC_KEY", "\"$hubPublicKey\"")
    // Public half of OFFICIAL_SIGNING_KEY: with it, the official subscription is accepted only when signed.
    val officialPublicKey = System.getenv("OFFICIAL_PUBLIC_KEY").orEmpty().trim()
    require(officialPublicKey.all { it.isLetterOrDigit() || it in "+/=" }) { "OFFICIAL_PUBLIC_KEY must be base64" }
    buildConfigField("String", "OFFICIAL_PUBLIC_KEY", "\"$officialPublicKey\"")
    // Independent copies of the official subscription (comma-separated base addresses, see docs/SUBSCRIPTIONS.md).
    val officialMirrors = System.getenv("OFFICIAL_MIRRORS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    require(officialMirrors.all { it.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?")) }) { "OFFICIAL_MIRRORS must be https base addresses" }
    buildConfigField("String", "OFFICIAL_MIRRORS", "\"${officialMirrors.joinToString(",")}\"")

    // Which build made a diagnostic report. CI sets GITHUB_SHA; local builds say "unknown".
    val gitCommit = System.getenv("GITHUB_SHA").orEmpty().take(12).filter { it.isLetterOrDigit() }.ifEmpty { "unknown" }
    val buildTime = if (gitCommit == "unknown") "unknown" else Instant.now().truncatedTo(ChronoUnit.MINUTES).toString()
    buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

    // The release build number the update pop-up compares (the release workflow's run number, also
    // published with each release). 0 for local and test builds, which never show the pop-up.
    val releaseBuild = System.getenv("MAXIMUS_RELEASE_BUILD").orEmpty().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    buildConfigField("int", "RELEASE_BUILD", "$releaseBuild")
  }

  val rootDebugKeystore = file("${rootDir}/debug.keystore")
  val base64DebugKeystore = file("${rootDir}/debug.keystore.base64")
  if (!rootDebugKeystore.exists() && base64DebugKeystore.exists()) {
    runCatching {
      val decoded = Base64.getDecoder().decode(base64DebugKeystore.readText().trim())
      rootDebugKeystore.writeBytes(decoded)
    }
  }

  signingConfigs {
    if (rootDebugKeystore.exists()) {
      create("debugConfig") {
        storeFile = rootDebugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val kFile = file(keystorePath)
      storeFile = kFile
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Debug builds install alongside the release app and never overwrite it.
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
      if (rootDebugKeystore.exists() && signingConfigs.findByName("debugConfig") != null) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
  }
  providers.gradleProperty("targetAbi").orNull?.let { targetAbi ->
    require(targetAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
    defaultConfig.ndk.abiFilters.add(targetAbi)
  }
  packaging { jniLibs { useLegacyPackaging = true } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  lint {
    checkReleaseBuilds = true
    abortOnError = true
    disable.add("InvalidFragmentVersionForActivityResult")
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

listOf(file("${rootDir}/.env"), file("${rootDir}/.env.example")).forEach { envFile ->
  if (envFile.exists()) {
    runCatching {
      val lines = envFile.readLines()
      val updated = lines.map { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("GEMINI_API_KEY=") && (trimmed == "GEMINI_API_KEY=" || trimmed == "GEMINI_API_KEY=\"\"")) {
          "GEMINI_API_KEY=DEFAULT_API_KEY"
        } else {
          line
        }
      }
      if (updated != lines) {
        envFile.writeText(updated.joinToString("\n") + "\n")
      }
    }
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

dependencies {
  implementation(files("libs/libXray.aar"))
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation("com.github.mwiede:jsch:2.28.7")
  implementation(libs.snakeyaml)
  // Tor for the Tor engine (BSD-3-Clause); its bridges run in lyrebird, built by scripts/engines/lyrebird.sh.
  // 0.4.9.5.1 is the newest release that builds against compileSdk 36. It is plain Java, so its
  // newer Kotlin standard library is left out to keep the app's own.
  implementation("info.guardianproject:tor-android:0.4.9.5.1") { exclude(group = "org.jetbrains.kotlin") }
  implementation("info.guardianproject:jtorctl:0.4.5.7")
  implementation(libs.zxing.core)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.json)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
  testImplementation("com.squareup.okhttp3:okhttp-tls:4.10.0")
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
