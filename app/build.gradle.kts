plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    id("com.google.devtools.ksp")
}

android {
    namespace = "com.dazo66.milkmilk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dazo66.milkmilk"
        minSdk = 24
        targetSdk = 35
        // 动态版本：优先使用 CI 注入的环境变量
        val ciVersionName = System.getenv("VERSION_NAME")
        val ciVersionCodeStr = System.getenv("VERSION_CODE")
        val ciVersionCode = ciVersionCodeStr?.toIntOrNull()

        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 使用 CI Secrets 在构建时为 release 包进行签名（本地无 Secrets 时保持未签名）
    signingConfigs {
        val ksFilePath = System.getenv("KEYSTORE_FILE")
        val ksPass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val alias = System.getenv("ANDROID_KEY_ALIAS")
        val keyPass = System.getenv("ANDROID_KEY_PASSWORD")
        if (!ksFilePath.isNullOrEmpty() && !ksPass.isNullOrEmpty() && !alias.isNullOrEmpty() && !keyPass.isNullOrEmpty()) {
            create("release") {
                storeFile = file(ksFilePath)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 若存在 release 签名配置，则在 CI 上自动使用该签名
            val hasReleaseSigning = signingConfigs.findByName("release") != null
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // https://mvnrepository.com/artifact/com.himanshoe/kalendar
    implementation(libs.himanshoe.kalendar)
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-datetime
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.foundation)
    //noinspection UseTomlInstead
    implementation(libs.accompanist.pager)
    // Use BOM-managed version for Compose UI
    implementation("androidx.compose.ui:ui")
    // implementation("androidx.compose.animation:animation:1.5.4")
    
    // Room数据库依赖
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // Kotlin注解处理器
    implementation("androidx.room:room-common:2.6.1")

    // Paging 3 依赖（运行时 + Compose 集成）
    implementation("androidx.paging:paging-runtime-ktx:3.3.2")
    implementation("androidx.paging:paging-compose:3.3.2")

}
