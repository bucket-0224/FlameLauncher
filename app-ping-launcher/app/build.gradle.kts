import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.co.donghyun.pinglauncher"
    compileSdk = 36
    ndkVersion = "27.0.12077973"   // NDK r27 LTS (CMake 3.22.1 호환)

    val localProperties = Properties().apply {
        load(rootProject.file("local.properties").inputStream())
    }

    defaultConfig {
        applicationId = "kr.co.donghyun.pinglauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "CURSEFORGE_API_KEY",
            "\"${localProperties["curseforge.api.key"]}\""
        )

        // ── ABI는 MinecraftActivity가 arm64-v8a만 추출하므로 단일 ABI ──
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                cFlags   += listOf("-std=gnu11")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26",
                    // 경고가 너무 많이 나오는 옛 PojavLauncher 코드 대응
                    "-DCMAKE_C_FLAGS=-Wno-implicit-function-declaration -Wno-incompatible-pointer-types -Wno-int-conversion"
                )
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // 디버그 .so 심볼 유지 (크래시 분석용)
            packaging {
                jniLibs {
                    keepDebugSymbols += "**/*.so"
                }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ── 패키징 옵션 ────────────────────────────────────────────
    packaging {
        jniLibs {
            // libpojavexec.so / libglfw.so 등은 APK 안에 그대로 들어가야
            // MainActivity.copyNativesFromApkLibDir() 가 꺼내 쓸 수 있음.
            useLegacyPackaging = true
            // 다른 의존성의 .so 와 이름이 겹쳐도 우리 것을 우선
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libbytehook.so"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    // ── lwjgl-glfw-classes.jar 를 assets 으로도 두고 컴파일 클래스패스에도 ──
    // (assets 폴더를 그대로 packagingOptions에 포함 → MinecraftActivity 가 추출 가능)
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
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

    // PojavLauncher patched LWJGL (컴파일 시점에만 클래스 참조용)
    compileOnly(files("src/main/assets/lwjgl3/lwjgl-glfw-classes.jar"))

    // ── bytehook (native_hooks/exit_hook.c, chmod_hook.c 가 dlopen 으로 사용) ──
    implementation(libs.bytehook)

    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.coil.compose)
    implementation(libs.androidx.foundation)
    implementation(libs.asm)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // ── ProcessorLauncher.java → assets/forge-runtime/processor-launcher.jar 빌드 ──
// embedded OpenJDK 위에서 돌 코드라 안드로이드 dex 와는 별도로 JVM 8 호환 jar 로 패키징.
    val processorLauncherSrc =
        file("src/main/java/kr/co/donghyun/pinglauncher/forge/ProcessorLauncher.java")
    val processorLauncherClassesDir =
        layout.buildDirectory.dir("processor-launcher/classes")
    val processorLauncherJarOut =
        file("src/main/assets/forge-runtime/processor-launcher.jar")

    val compileProcessorLauncher by tasks.registering(JavaCompile::class) {
        description = "Compile ProcessorLauncher.java for the embedded JVM (JDK 8 target)"
        source = fileTree(processorLauncherSrc.parentFile) {
            include("ProcessorLauncher.java")
        }
        classpath = files()
        destinationDirectory.set(processorLauncherClassesDir)
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
        options.release.set(8)
        inputs.file(processorLauncherSrc)
    }

    val buildProcessorLauncherJar by tasks.registering(Jar::class) {
        description = "Package ProcessorLauncher classes into assets/forge-runtime/processor-launcher.jar"
        dependsOn(compileProcessorLauncher)
        from(processorLauncherClassesDir)
        archiveFileName.set("processor-launcher.jar")
        destinationDirectory.set(processorLauncherJarOut.parentFile)
        // assets 디렉토리 mkdirs 보장
        doFirst { processorLauncherJarOut.parentFile.mkdirs() }
    }

    // merge*Assets 시점 전에 jar 가 준비되어 있도록 강제
    tasks.matching {
        it.name.startsWith("merge") && it.name.endsWith("Assets") ||
                it.name.startsWith("package") && it.name.endsWith("Assets") ||
                it.name == "preBuild"
    }.configureEach {
        dependsOn(buildProcessorLauncherJar)
    }
}