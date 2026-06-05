# 🌸 PingLauncher

> Android-native Minecraft Java Edition launcher — PojavLauncher 코어 위에 올린 한국어 친화 UI
>
> An Android-native launcher for Minecraft: Java Edition, built on top of the PojavLauncher core with a Korean-friendly UI.

[한국어](#-한국어) · [English](#-english)

---

## 🇰🇷 한국어

### 개요

PingLauncher는 안드로이드 기기에서 마인크래프트 자바 에디션을 실행하는 런처입니다. PojavLauncher의 검증된 네이티브 브릿지(JNI/EGL/AWT 스텁)를 기반으로, Jetpack Compose로 새로 그린 UI와 CurseForge 통합, 자동 로더 설치, 가상 키패드 편집기를 얹었습니다.

핑크 테마와 한국어 우선 UX가 기본이고, 폰/태블릿 둘 다 반응형으로 대응합니다.

### ✨ 주요 기능

- 🎮 **바닐라 / Fabric / Forge / NeoForge** 자동 설치 및 실행
- 📦 **CurseForge 통합** — 모드팩 · 모드 · 텍스처팩 · 쉐이더팩 · 월드 검색/설치
- 🔑 **Microsoft 정식 인증** (Xbox Live → XSTS → Minecraft Services)
- 🎨 **렌더러 선택** — MobileGlues / Zink / Holy-GL4ES / GL4ES Desktop / LTW
- ⚙️ **JVM 설정** — 힙 메모리, G1GC 튜닝, FPS 언락, 커스텀 인자
- ⌨️ **가상 키패드 편집기** — 드래그 앤 드롭으로 자유 배치, 폰/태블릿 자동 스케일
- ⚔️ **전투/일반 모드 토글** — 탭과 롱프레스 동작을 상황에 맞게 자동 스위칭
- 🌍 **물리 키보드 / 마우스** 자동 감지 및 IME 한글 입력 지원
- 💥 **크래시 복구 센터** — 의심 모드 자동 식별 및 토글 비활성화
- 🩹 **Sodium → Podium 자동 보강** — Pojav 환경 호환성 패치 자동 동봉

### 🏗️ 빌드

#### 요구사항

- Android Studio Hedgehog 이상
- Android SDK 36, NDK r27 (27.0.12077973)
- CMake 3.22.1
- JDK 11+

#### `local.properties` 설정

```properties
sdk.dir=/path/to/Android/Sdk
curseforge.api.key="YOUR_CURSEFORGE_API_KEY"
```

CurseForge API 키는 [console.curseforge.com](https://console.curseforge.com/)에서 발급받으세요.

#### `assets/` 에 넣어야 할 것

- `jre8.zip`, `jre17.zip`, `jre21.zip` — 마인크래프트 버전에 따라 사용됨 (1.16 이하 → 8, 1.17 → 16/17, 1.20.5+ → 21)
- `caciocavallo/` — 1.12.2 이하 Legacy AWT 지원용 (`cacio-shared`, `cacio-androidnw`, `ResConfHack`)
- `lwjgl3/lwjgl-glfw-classes.jar` — PojavLauncher 패치 LWJGL
- `forge-runtime/processor-launcher.jar` — 빌드 시 자동 생성됨

#### 빌드 명령

```bash
./gradlew :app:assembleDebug
# 또는 릴리즈
./gradlew :app:assembleRelease
```

ABI는 `arm64-v8a` 단일 — 다른 ABI는 빌드 시 자동 제외됩니다.

### 📂 프로젝트 구조

```
app/
├── src/main/
│   ├── cpp/                          # 네이티브 코어 (C/C++)
│   │   ├── pingjvm.cpp               # JVM 부팅 + 후킹 + showingWindow 워치독
│   │   ├── pojav_jni/                # PojavLauncher core
│   │   │   ├── ctxbridges/           # GL/EGL/OSMesa 컨텍스트 브릿지
│   │   │   ├── jvm_hooks/            # LWJGL dlopen / forkAndExec / EMUI 후킹
│   │   │   ├── native_hooks/         # exit / chmod 후킹 (bytehook)
│   │   │   ├── awt_xawt/             # libfontmanager.so 의 X11FontScaler 스텁
│   │   │   └── driver_helper/        # Adreno Turnip 로더
│   │   └── CMakeLists.txt
│   │
│   ├── java/kr/co/donghyun/pinglauncher/
│   │   ├── data/                     # 도메인 모델
│   │   │   ├── auth/                 # Microsoft OAuth 세션
│   │   │   ├── curseforge/           # CurseForge API 모델
│   │   │   ├── instance/             # 인스턴스 메타 (vanilla/fabric/forge/modpack)
│   │   │   ├── jvm/                  # JVM 설정
│   │   │   ├── key/                  # 가상 키패드 레이아웃
│   │   │   ├── mojang/               # 버전 매니페스트
│   │   │   └── renderer/             # 렌더러 프리셋
│   │   │
│   │   └── presentation/             # UI + 실행 로직
│   │       ├── MainActivity.kt       # 버전 목록 + 실행 디스패처
│   │       ├── MinecraftActivity.kt  # 실제 JVM 부팅 + 입력 라우팅
│   │       ├── ContentPackBrowserActivity.kt   # CurseForge 검색
│   │       ├── CrashReportActivity.kt
│   │       └── util/
│   │           ├── curseforge/       # ModPack 설치, 의존성 해결
│   │           ├── fabric/           # Fabric meta + installer
│   │           ├── forge/            # Forge / NeoForge installer + processor 직렬화
│   │           ├── minecraft/        # Mojang downloader + JRE 추출
│   │           └── jni/              # JavaNativeLauncher (JNI 진입)
│   │
│   └── java/
│       ├── net/kdt/pojavlaunch/      # PojavLauncher 호환 진입점
│       ├── org/lwjgl/glfw/           # CallbackBridge
│       └── kr/.../forge/             # ProcessorLauncher (임베디드 JVM용)
│
├── build.gradle.kts                  # NDK 27, ABI arm64-v8a, processor-launcher.jar 자동 빌드
└── local.properties                  # API 키 (gitignore)
```

### 🚀 실행 흐름 (요약)

1. **버전 선택** → Mojang `version_manifest.json` 에서 메타 조회
2. **로더 선택** → Fabric/Forge/NeoForge 메타 API 호출
3. **MinecraftDownloader** → client jar + libraries + assets 받음 (인스턴스 디렉토리 안)
4. **로더 설치** → FabricInstaller / ForgeInstaller가 libraries 머지
5. **JRE 추출** → `assets/jreN.zip` → `filesDir/jreN_runtime/`
6. **MinecraftActivity** → 렌더러별 `.so` 로드 → `pingjvm.cpp::bootMinecraftJVM` 호출
7. **JNI_CreateJavaVM** → mainClass.main(args) 호출 → 게임 부팅

### 📝 라이선스 및 기여

- PojavLauncher 코어: GPLv3
- 본 프로젝트의 추가 코드: 별도 라이선스 명시 전까지 저자에게 문의

---

## 🇺🇸 English

### Overview

PingLauncher is an Android launcher for Minecraft: Java Edition. It builds on PojavLauncher's battle-tested native bridges (JNI / EGL / AWT stubs) and adds a Jetpack Compose UI, CurseForge integration, automatic loader installation, and a draggable virtual keypad editor.

Pink-themed, Korean-first UX by default, with responsive layouts for both phones and tablets.

### ✨ Features

- 🎮 **Vanilla / Fabric / Forge / NeoForge** auto-install and launch
- 📦 **CurseForge integration** — search and install modpacks, mods, resource packs, shader packs, worlds
- 🔑 **Microsoft official auth** (Xbox Live → XSTS → Minecraft Services)
- 🎨 **Renderer picker** — MobileGlues / Zink / Holy-GL4ES / GL4ES Desktop / LTW
- ⚙️ **JVM tuning** — heap, G1GC, FPS unlock, custom args
- ⌨️ **Virtual keypad editor** — drag-and-drop placement with phone/tablet auto-scaling
- ⚔️ **Combat / normal mode toggle** — tap and long-press swap depending on context
- 🌍 **Hardware keyboard / mouse** auto-detection plus IME Korean composition support
- 💥 **Crash recovery center** — identifies suspect mods and disables them with one tap
- 🩹 **Sodium → Podium auto-augment** — automatically bundles the Pojav compatibility patch

### 🏗️ Build

#### Requirements

- Android Studio Hedgehog or newer
- Android SDK 36, NDK r27 (27.0.12077973)
- CMake 3.22.1
- JDK 11+

#### `local.properties`

```properties
sdk.dir=/path/to/Android/Sdk
curseforge.api.key="YOUR_CURSEFORGE_API_KEY"
```

Grab a CurseForge API key from [console.curseforge.com](https://console.curseforge.com/).

#### Assets you need to drop in

- `jre8.zip`, `jre17.zip`, `jre21.zip` — picked by MC version (≤1.16 → 8, 1.17 → 16/17, 1.20.5+ → 21)
- `caciocavallo/` — AWT support for legacy MC (≤1.12.2): `cacio-shared`, `cacio-androidnw`, `ResConfHack`
- `lwjgl3/lwjgl-glfw-classes.jar` — PojavLauncher-patched LWJGL
- `forge-runtime/processor-launcher.jar` — auto-built by Gradle

#### Build commands

```bash
./gradlew :app:assembleDebug
# or release
./gradlew :app:assembleRelease
```

Only `arm64-v8a` is shipped — other ABIs are filtered out at build time.

### 📂 Project Layout

```
app/
├── src/main/
│   ├── cpp/                          # Native core (C/C++)
│   │   ├── pingjvm.cpp               # JVM boot + hooks + showingWindow watchdog
│   │   ├── pojav_jni/                # PojavLauncher core
│   │   │   ├── ctxbridges/           # GL / EGL / OSMesa context bridges
│   │   │   ├── jvm_hooks/            # LWJGL dlopen / forkAndExec / EMUI hooks
│   │   │   ├── native_hooks/         # exit / chmod hooks (via bytehook)
│   │   │   ├── awt_xawt/             # X11FontScaler stubs for libfontmanager.so
│   │   │   └── driver_helper/        # Adreno Turnip loader
│   │   └── CMakeLists.txt
│   │
│   ├── java/kr/co/donghyun/pinglauncher/
│   │   ├── data/                     # Domain models
│   │   │   ├── auth/                 # Microsoft OAuth session
│   │   │   ├── curseforge/           # CurseForge API models
│   │   │   ├── instance/             # Instance metadata (vanilla/fabric/forge/modpack)
│   │   │   ├── jvm/                  # JVM settings
│   │   │   ├── key/                  # Virtual keypad layout
│   │   │   ├── mojang/               # Version manifests
│   │   │   └── renderer/             # Renderer presets
│   │   │
│   │   └── presentation/             # UI + launch orchestration
│   │       ├── MainActivity.kt
│   │       ├── MinecraftActivity.kt  # Actually boots the JVM and routes input
│   │       ├── ContentPackBrowserActivity.kt
│   │       ├── CrashReportActivity.kt
│   │       └── util/
│   │           ├── curseforge/       # Modpack installer, dependency resolver
│   │           ├── fabric/           # Fabric meta + installer
│   │           ├── forge/            # Forge / NeoForge installer + processor serializer
│   │           ├── minecraft/        # Mojang downloader + JRE extractor
│   │           └── jni/              # JavaNativeLauncher (JNI entry)
│   │
│   └── java/
│       ├── net/kdt/pojavlaunch/      # PojavLauncher-compat entry points
│       ├── org/lwjgl/glfw/           # CallbackBridge
│       └── kr/.../forge/             # ProcessorLauncher (runs inside the embedded JVM)
│
├── build.gradle.kts                  # NDK 27, ABI arm64-v8a, auto-builds processor-launcher.jar
└── local.properties                  # API keys (gitignored)
```

### 🚀 Launch Flow (TL;DR)

1. **Pick a version** → fetch from Mojang `version_manifest.json`
2. **Pick a loader** → query Fabric / Forge / NeoForge meta APIs
3. **MinecraftDownloader** → download client jar + libraries + assets (into the instance dir)
4. **Loader install** → FabricInstaller / ForgeInstaller merges libraries
5. **Extract JRE** → `assets/jreN.zip` → `filesDir/jreN_runtime/`
6. **MinecraftActivity** → load renderer `.so`s → call `pingjvm.cpp::bootMinecraftJVM`
7. **JNI_CreateJavaVM** → invoke `mainClass.main(args)` → game boots

### 📝 License & Contribution

- PojavLauncher core: GPLv3
- Additional code in this project: contact the author until a license is declared

---

<sub>🌸 Made with too much caffeine and not enough sleep.</sub>

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://coff.ee/ydh878787)



