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
    versionCode = 26
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
