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

// ========================================================================
// ProcessorLauncher 자동 컴파일 태스크
// ========================================================================
// app/src/main/java/.../forge/ProcessorLauncher.java 를 컴파일해서
// app/src/main/assets/forge-runtime/processor-launcher.jar 로 패키징한다.
//
// 문제 배경:
//   ProcessorLauncher 는 게임 JVM(JRE 21) 안에서 실행되는 별도 jar 라서
//   앱 코드(Kotlin)와 함께 컴파일되지 않는다. 그래서 .java 를 고쳐도
//   assets 의 .jar 가 자동 갱신되지 않아, 옛 버전이 인스턴스로 복사되는
//   "소스 따로 jar 따로" 문제가 있었다.
//   이 태스크가 그 간극을 메워서, 소스를 고치면 빌드 시 jar 가 자동 갱신된다.
//
// 위치: app/build.gradle.kts 의 android { ... } 블록 "바깥", 파일 하단에 추가.
// ========================================================================

val processorLauncherSrc = file("src/main/java/kr/co/donghyun/pinglauncher/forge/ProcessorLauncher.java")
val processorLauncherJar = file("src/main/assets/forge-runtime/processor-launcher.jar")
val processorLauncherWork = layout.buildDirectory.dir("processor-launcher").get().asFile

tasks.register("buildProcessorLauncher") {
    description = "Compiles ProcessorLauncher.java into assets/forge-runtime/processor-launcher.jar"
    group = "build"

    // 입력/출력 선언 → 소스가 안 바뀌면 태스크 스킵(up-to-date), 바뀌면 재실행
    inputs.file(processorLauncherSrc)
    outputs.file(processorLauncherJar)

    doLast {
        val classesDir = File(processorLauncherWork, "classes")
        classesDir.deleteRecursively()
        classesDir.mkdirs()

        // JDK 도구 위치: Gradle 이 실행 중인 JVM(자바 홈) 의 javac/jar 사용
        val javaHome = System.getProperty("java.home")
        // java.home 이 JRE 를 가리킬 수 있으니 javac 후보를 탐색
        val javacCandidates = listOf(
            File(javaHome, "bin/javac"),
            File(File(javaHome).parentFile, "bin/javac")  // jre 의 부모(jdk)/bin/javac
        )
        val javac = javacCandidates.firstOrNull { it.exists() }
            ?: error("javac 를 찾을 수 없습니다. JAVA_HOME 이 JDK 를 가리키는지 확인하세요. (탐색: $javacCandidates)")
        val jarCandidates = listOf(
            File(javaHome, "bin/jar"),
            File(File(javaHome).parentFile, "bin/jar")
        )
        val jarTool = jarCandidates.firstOrNull { it.exists() }
            ?: error("jar 도구를 찾을 수 없습니다. (탐색: $jarCandidates)")

        // 1) 컴파일 — release 17 로 컴파일.
        //    Gradle 이 JDK 17 로 실행되므로 17 로 맞춤. 게임 JVM 은 JRE 21 이지만
        //    21 은 17 바이트코드를 하위호환으로 완벽히 실행하며, ProcessorLauncher 는
        //    표준 라이브러리(파일 IO/reflection)만 써서 17/21 차이가 없음.
        exec {
            commandLine(
                javac.absolutePath,
                "--release", "17",
                "-d", classesDir.absolutePath,
                processorLauncherSrc.absolutePath
            )
        }

        // 2) jar 패키징 — 원본과 동일하게 Main-Class 없는 최소 manifest
        //    (ProcessorLauncher 는 -Dping.main.class 로 직접 지정 실행되므로 Main-Class 불필요)
        processorLauncherJar.parentFile.mkdirs()
        exec {
            commandLine(
                jarTool.absolutePath,
                "cf", processorLauncherJar.absolutePath,
                "-C", classesDir.absolutePath, "."
            )
        }

        println("✅ processor-launcher.jar 생성됨: ${processorLauncherJar.absolutePath}")
    }
}

// 앱 빌드(에셋 머지) 전에 자동 실행되도록 연결.
// preBuild 에 의존시키면 모든 변형(debug/release)에서 항상 먼저 실행됨.
tasks.named("preBuild") {
    dependsOn("buildProcessorLauncher")
}

// 앱 빌드(에셋 머지) 전에 자동 실행되도록 연결.
// preBuild 에 의존시키면 모든 변형(debug/release)에서 항상 먼저 실행됨.
tasks.named("preBuild") {
    dependsOn("buildProcessorLauncher")
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
    implementation(libs.androidx.compose.material.icons.core)

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