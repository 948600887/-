import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.stockdiary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.stockdiary"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // 从 local.properties 读取 API_KEY
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
            buildConfigField("String", "API_KEY", "\"${localProperties.getProperty("api_key", "demo")}\"")
            buildConfigField("String", "EASTMONEY_SKILL_API_KEY", "\"${localProperties.getProperty("eastmoney_skill_api_key", "")}\"")
        } else {
            buildConfigField("String", "API_KEY", "\"demo\"")
            buildConfigField("String", "EASTMONEY_SKILL_API_KEY", "\"\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // JVM 单测中 Android 框架方法默认抛 "Method ... not mocked"。
        // AppLogger 走 android.util.Log，而 ViewModel 里有无条件日志调用
        // （如 CalendarViewModel 生成日历后的 AppLogger.d），一旦炸掉会连带整条协程链失败。
        // 这里改为返回默认值，让日志在单测里退化为空操作。
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    // 避免非 ASCII 项目路径导致 Gradle Test Worker 找不到 GradleWorkerMain
    jvmArgs("-Dfile.encoding=UTF-8")
    maxParallelForks = 1
}

dependencies {
    // Jetpack Compose BOM - 与 Kotlin 1.9.24 兼容
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    
    // Jetpack Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room - 使用 2.6.1 稳定版本
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Jetpack Charts
    implementation("androidx.compose.foundation:foundation:1.6.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.8.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Other
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    // 提供 collectAsStateWithLifecycle：App 退到后台时停止收集 Flow，避免后台无谓的重组与数据库查询
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    
    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.annotation:annotation-experimental:1.4.0")
    implementation("com.google.android.material:material:1.12.0")
    
    // ML Kit 中文文字识别（捆绑版，模型内置在 APK 中，不依赖 Google Play 服务，无需联网下载）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // HTML解析
    implementation("org.jsoup:jsoup:1.17.2")
    
    // JSON处理
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    // JVM 单测驱动 viewModelScope（Dispatchers.Main 接管）+ MockK 伪造 final 类 / AppContainer
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.11")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}