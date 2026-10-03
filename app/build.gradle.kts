plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// 版本号集中定义：versionName 与 release 产物文件名共用
val appVersionName = "0.1.1"

android {
    namespace = "io.github.hecate2.D7"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hecate2.D7"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 真机只需 arm64-v8a 与 armeabi-v7a，裁掉 x86/x86_64 的 native 库
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 只保留中文资源，剔除各家库自带的几十种语言翻译，显著缩减 resources.arsc
        resourceConfigurations += setOf("zh")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    // 剔除 Kotlin 编译器调试探针与工具链元数据：它们只服务于 IDE 断点与插件，release 用不到
    packaging {
        resources.excludes += setOf(
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "**/DebugProbesKt.bin",
            "**/kotlin-tooling-metadata.json",
        )
    }

    lint {
        // 报错即失败：防止 NewApi / 可用性回归再次静默积累
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// release 产物文件名固定为中文名并带上版本号，便于分发（内部类只因 AGP 未公开该设置项）
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("七日-看房拍照测日照时间-v$appVersionName.apk")
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)

    // 纯函数单测（不碰 Android 运行时，Surface.ROTATION_* 为编译期常量）
    testImplementation(libs.junit)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view) {
        // camera-view 的 POM 挂着 appcompat→fragment→viewpager，但它的字节码对这些库零引用
        // （PreviewView 继承的是 android.widget.FrameLayout）。不显式 exclude 的话，删掉直接依赖
        // 只会让 Gradle 把 appcompat 降到 camera-view 要求的 1.1.0，体积一点不降。
        exclude(group = "androidx.appcompat")
    }

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.test.uiautomator)
}