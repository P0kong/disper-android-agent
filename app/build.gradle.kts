import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin：不要应用 org.jetbrains.kotlin.android（会与内置支持冲突）。
    // 只应用 Compose 编译器插件，版本与 AGP 内置的 KGP 保持一致。
    alias(libs.plugins.kotlin.compose)
    // Room 的注解处理走 KSP（KSP 版本前缀 = Kotlin 版本 2.2.10）。
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquopy)
}

/**
 * 设计预览构建可用 -PdisperApplicationId=... 覆盖包名，
 * 以便与模拟器里已有的开发版并存而不覆盖它；默认值保持原样。
 */
val disperApplicationId: String? = providers.gradleProperty("disperApplicationId").orNull

/** 本地私有签名配置：仓库外/未纳入 Git 的 keystore.properties；文件不存在时 release 保持未签名。 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties: Properties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { stream -> keystoreProperties.load(stream) }
}

gradle.taskGraph.whenReady {
    val releaseAssembly = allTasks.any { task ->
        (task.name.startsWith("assemble") || task.name.startsWith("bundle")) && task.name.contains("Release")
    }
    if (releaseAssembly && disperApplicationId == null) {
        throw GradleException("Release 构建必须显式指定 -PdisperApplicationId=com.example.aiuseagent.design")
    }
}

android {
    namespace = "com.example.aiuseagent"
    // Compose 1.12.x 的 AAR 元数据要求 compileSdk >= 37；AGP 9.2 支持的最高 API level 正好是 37.0。
    compileSdk = 37

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(requireNotNull(keystoreProperties.getProperty("storeFile")))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = disperApplicationId ?: "com.example.aiuseagent"
        // 产品需求：Android 13+（API 33）。Chaquopy 17 的下限是 24，不是本工程的取值依据。
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Chaquopy 必须显式声明 ABI：只有打包进来的 ABI 才带 Python 运行时。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        // 应用侧 Python 主次版本；必须与 buildPython 的主次版本一致。
        version = "3.13"
        // 阶段 0 不引入任何 pip 依赖，先验证“运行时能起来”。
        pip {
        }
    }
}

ksp {
    // 导出 Room schema，便于审计表结构与后续迁移测试。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // Material3 1.12 不再传递依赖 material-icons-core，本工程用它渲染导航图标；
    // 版本由 Compose BOM（2026.09.00）管理，不手写版本号。
    implementation(libs.compose.material.icons.core)

    // P2-B：唯一真实 HTTP 客户端与 JSON 运行时（都在主源码，Mock 不联网）。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // JVM 单元测试：Repository / ViewModel / Room schema 契约。不引入 Robolectric。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // MockWebServer：只在 JVM 测试里起本地假服务器，不访问任何真实供应商。
    testImplementation(libs.mockwebserver3)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)

    debugImplementation(libs.compose.ui.tooling)
}
