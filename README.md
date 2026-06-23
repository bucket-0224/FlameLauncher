# 🔥 FlameLauncher

> Android-native Minecraft: Java Edition launcher — built on the **PojavLauncher** core with launch logic adapted from **ZalithLauncher 2 (ZL2)**, wrapped in a touch-first, Korean-friendly Jetpack Compose UI.
>
> PojavLauncher 코어 위에 ZalithLauncher 2(ZL2)의 실행 로직을 더하고, 터치 우선·한국어 친화 Jetpack Compose UI를 올린 안드로이드 네이티브 마인크래프트 자바 에디션 런처.

[English](#-english) · [한국어](#-한국어)

---

## 🇺🇸 English

### Overview

FlameLauncher runs Minecraft: Java Edition on Android. It is a **derivative of PojavLauncher and ZalithLauncher 2 (ZL2)**:

- It reuses PojavLauncher's battle-tested native bridges — JNI / EGL / OSMesa context bridges, LWJGL & exec hooks, and the AWT (Caciocavallo) stubs.
- It adapts several launch-orchestration approaches from ZL2 — NeoForge classpath handling, GLFW 3.4 method stubbing, Forge/NeoForge early-window handling, and hotbar hit-testing.
- On top of that it adds a freshly drawn Compose UI, CurseForge integration, automatic loader installation, a draggable virtual keypad editor, and KR-network helpers.

Pink-themed and Korean-first by default, with responsive layouts for both phones and tablets. Ships **`arm64-v8a` only**.

### 🎮 Input — touch-first by design

FlameLauncher is built for **touch play**. The on-screen virtual keypad plus touch camera / attack / hotbar controls are the primary input path:

- The keypad layout is fully editable (drag-and-drop) and auto-scales for phone vs. tablet.
- A **combat / normal toggle** swaps tap and long-press between attack and use depending on context.
- Touching the hotbar area selects slots directly; dragging elsewhere rotates the camera.

**Hardware keyboards and mice are supported, but de-emphasized.** When a physical keyboard or mouse is detected, the on-screen controller hides itself and input flows through the hardware path (physical-key routing and IME Korean composition are both wired up). This works — but it is a secondary, best-effort path. The project's focus is the touch experience, and the hardware path is not where most of the polish goes.

### ✨ Features

- 🧱 **Vanilla / Fabric / Forge / NeoForge** — automatic install and launch
- 📦 **CurseForge integration** — search & install modpacks, mods, resource packs, shader packs, and worlds
- 🔑 **Microsoft authentication** — full Xbox Live → XSTS → Minecraft Services flow
- 🎨 **Renderer: Zink (Vulkan → OpenGL via OSMesa)** with **automatic native fallback to GL4ES** when the device's Vulkan/Zink path isn't viable
- ⚙️ **JVM tuning** — heap size, G1GC, FPS unlock, custom arguments
- ⌨️ **Virtual keypad editor** — drag-and-drop placement with phone/tablet auto-scaling
- ⚔️ **Combat / normal mode toggle** — context-aware tap vs. long-press
- 🌍 **IME Korean composition** + physical keyboard/mouse auto-detection
- 💥 **Crash recovery center** — auto-detects new crash reports and surfaces the full log with copy-to-share
- 🩹 **Sodium → Podium auto-augment** — bundles the Pojav compatibility patch automatically when Sodium is present (Fabric / NeoForge)
- 🌐 **Network helpers** — KR-friendly DNS + `_minecraft._tcp` SRV resolution, plus custom hosts mapping for Hamachi / LAN servers

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

Grab a CurseForge API key from [console.curseforge.com](https://console.curseforge.com/). It's exposed to the app as `BuildConfig.CURSEFORGE_API_KEY`.

#### Assets to drop in (`app/src/main/assets/`)

- `jre/jre8.zip`, `jre/jre17.zip`, `jre/jre21.zip` — picked by MC version (≤1.16 → 8, 1.17 → 16, 1.18–1.20.4 → 17, 1.20.5+ → 21). Add `jre/jre25.zip` for the newest snapshots (1.26+). If the exact major isn't present, the launcher falls back 25 → 21 → 17.
- `caciocavallo/` — AWT support for legacy MC (≤1.12.2): `cacio-shared-1.10-SNAPSHOT.jar`, `cacio-androidnw-1.10-SNAPSHOT.jar`, `ResConfHack.jar`
- `lwjgl3/lwjgl-glfw-classes.jar` — the PojavLauncher-patched LWJGL fat jar (GLFW 3.4 stubs are injected at runtime when missing)
- `forge-runtime/processor-launcher.jar` — runs Forge/NeoForge install processors inside the embedded JVM

#### Build commands

```bash
./gradlew :app:assembleDebug
# or release
./gradlew :app:assembleRelease
```

Only `arm64-v8a` is built; other ABIs are filtered out at build time.

### 📂 Project layout

```
app/src/main/
├── cpp/                              # Native core (C/C++)
│   ├── pingjvm.cpp                   # JVM boot + grab hook + DNS/SRV port override + showingWindow watchdog
│   ├── pojav_jni/                    # PojavLauncher core
│   │   ├── ctxbridges/               # GL / EGL / OSMesa context bridges
│   │   ├── jvm_hooks/                # LWJGL dlopen / forkAndExec / EMUI linker hooks
│   │   ├── native_hooks/             # exit / chmod / getaddrinfo / resolv.conf hooks (bytehook)
│   │   ├── awt_xawt/xawt_fake.c      # X11FontScaler stubs so libfontmanager.so resolves
│   │   ├── awt_bridge.c              # Cacio AWT bridge (built as a separate .so)
│   │   └── driver_helper/            # Adreno Turnip loader / namespace bypass
│   └── CMakeLists.txt
├── java/kr/co/donghyun/FlameLauncher/
│   ├── data/                         # Domain models
│   │   ├── auth/  curseforge/  instance/  jvm/  key/  mojang/  renderer/  setting/
│   └── presentation/                 # Jetpack Compose UI + launch orchestration
│       ├── MainActivity.kt           # Version list + launch dispatch
│       ├── MinecraftActivity.kt      # Boots the JVM, assembles classpath, routes input
│       ├── ContentPackBrowserActivity.kt   # CurseForge browser
│       ├── CrashReportActivity.kt
│       └── util/
│           ├── curseforge/           # Modpack installer, dependency resolver
│           ├── fabric/               # Fabric meta + installer
│           ├── forge/                # Forge / NeoForge installer + processor serializer
│           ├── minecraft/            # Mojang downloader + JRE extractor
│           ├── dns/  hosts/          # DNS + hosts redirect hooks
│           └── jni/                  # JavaNativeLauncher (JNI entry)
└── java/
    ├── net/kdt/pojavlaunch/          # PojavLauncher-compat entry points
    ├── org/lwjgl/glfw/               # CallbackBridge
    └── kr/.../forge/                 # ProcessorLauncher (runs inside the embedded JVM)
```

### 🚀 Launch flow (TL;DR)

1. **Pick a version** → fetch from Mojang `version_manifest.json`
2. **Pick a loader** → query Fabric / Forge / NeoForge meta APIs
3. **MinecraftDownloader** → client jar + libraries + assets into the instance dir
4. **Loader install** → FabricInstaller / ForgeInstaller merges libraries (modern Forge serializes its processors for first-run client-jar patching)
5. **Extract JRE** → `assets/jre/jreN.zip` → `filesDir/jreN_runtime/`
6. **MinecraftActivity** → load the renderer `.so`s → call `pingjvm.cpp::bootMinecraftJVM`
7. **JNI_CreateJavaVM** → invoke `mainClass.main(args)` → game boots

### 📝 License & contribution

- PojavLauncher core: **GPLv3**
- Approaches adapted from **ZalithLauncher 2 (ZL2)** — see ZL2's respective license
- Additional code in this project: contact the author until a license is declared

---

## 🇰🇷 한국어

### 개요

FlameLauncher는 안드로이드 기기에서 마인크래프트 자바 에디션을 실행하는 런처이며, **PojavLauncher와 ZalithLauncher 2(ZL2)의 파생 프로젝트**입니다.

- PojavLauncher의 검증된 네이티브 브릿지(JNI / EGL / OSMesa 컨텍스트 브릿지, LWJGL·exec 후킹, Caciocavallo AWT 스텁)를 재사용합니다.
- ZL2의 실행 흐름 처리 방식 일부를 차용했습니다 — NeoForge 클래스패스 처리, GLFW 3.4 메서드 스텁, Forge/NeoForge early-window 처리, 핫바 히트 테스트 등.
- 그 위에 Jetpack Compose로 새로 그린 UI, CurseForge 통합, 자동 로더 설치, 드래그 앤 드롭 가상 키패드 편집기, KR 네트워크 보조 기능을 얹었습니다.

핑크 테마와 한국어 우선 UX가 기본이며, 폰/태블릿 둘 다 반응형으로 대응합니다. ABI는 **`arm64-v8a` 단일**입니다.

### 🎮 입력 — 터치 우선 설계

FlameLauncher는 **터치 플레이를 중심으로** 설계되었습니다. 화면 위 가상 키패드와 터치 기반 카메라 / 공격 / 핫바 조작이 기본 입력 경로입니다.

- 키패드 배치는 드래그 앤 드롭으로 자유롭게 편집할 수 있고, 폰/태블릿에 맞춰 자동 스케일됩니다.
- **전투 / 일반 토글**이 상황에 따라 탭과 롱프레스 동작(공격 ↔ 사용)을 자동으로 바꿔줍니다.
- 핫바 영역을 터치하면 슬롯을 바로 선택하고, 그 외 영역을 드래그하면 카메라가 회전합니다.

**물리 키보드 / 마우스는 지원하지만 권장하지 않습니다.** 물리 키보드나 마우스가 감지되면 화면 컨트롤러가 자동으로 숨겨지고 입력이 하드웨어 경로로 전환됩니다(물리 키 라우팅 + IME 한글 조합 모두 연결되어 있음). 동작은 하지만 어디까지나 보조적인 best-effort 경로이며, 이 프로젝트의 초점은 터치 경험입니다. 하드웨어 경로에 많은 다듬기를 들이지는 않습니다.

### ✨ 주요 기능

- 🧱 **바닐라 / Fabric / Forge / NeoForge** — 자동 설치 및 실행
- 📦 **CurseForge 통합** — 모드팩 · 모드 · 텍스처팩 · 쉐이더팩 · 월드 검색/설치
- 🔑 **Microsoft 정식 인증** — Xbox Live → XSTS → Minecraft Services 전 과정
- 🎨 **렌더러: Zink (Vulkan → OSMesa 경유 OpenGL)** — 기기에서 Vulkan/Zink 경로가 어려우면 **네이티브 레벨에서 GL4ES로 자동 폴백**
- ⚙️ **JVM 설정** — 힙 메모리, G1GC, FPS 언락, 커스텀 인자
- ⌨️ **가상 키패드 편집기** — 드래그 앤 드롭 배치, 폰/태블릿 자동 스케일
- ⚔️ **전투 / 일반 모드 토글** — 상황 인식형 탭 / 롱프레스 스위칭
- 🌍 **IME 한글 조합** + 물리 키보드/마우스 자동 감지
- 💥 **크래시 복구 센터** — 새 크래시 리포트를 자동 감지해 전체 로그를 보여주고 복사/공유 지원
- 🩹 **Sodium → Podium 자동 보강** — Sodium 이 있으면 Pojav 호환성 패치를 자동 동봉 (Fabric / NeoForge)
- 🌐 **네트워크 보조** — KR 친화 DNS + `_minecraft._tcp` SRV 조회, Hamachi / LAN 서버용 커스텀 hosts 매핑

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

CurseForge API 키는 [console.curseforge.com](https://console.curseforge.com/)에서 발급받으세요. 빌드 시 `BuildConfig.CURSEFORGE_API_KEY`로 노출됩니다.

#### `app/src/main/assets/` 에 넣어야 할 것

- `jre/jre8.zip`, `jre/jre17.zip`, `jre/jre21.zip` — 마인크래프트 버전에 따라 선택됨 (≤1.16 → 8, 1.17 → 16, 1.18–1.20.4 → 17, 1.20.5+ → 21). 최신 스냅샷(1.26+)용으로 `jre/jre25.zip` 추가. 해당 major 가 없으면 25 → 21 → 17 순으로 폴백.
- `caciocavallo/` — 1.12.2 이하 Legacy AWT 지원용: `cacio-shared-1.10-SNAPSHOT.jar`, `cacio-androidnw-1.10-SNAPSHOT.jar`, `ResConfHack.jar`
- `lwjgl3/lwjgl-glfw-classes.jar` — PojavLauncher 패치 LWJGL fat jar (누락 시 GLFW 3.4 스텁을 런타임에 주입)
- `forge-runtime/processor-launcher.jar` — Forge/NeoForge 설치 프로세서를 임베디드 JVM 안에서 실행

#### 빌드 명령

```bash
./gradlew :app:assembleDebug
# 또는 릴리즈
./gradlew :app:assembleRelease
```

ABI는 `arm64-v8a` 단일이며, 다른 ABI는 빌드 시 자동 제외됩니다.

### 📂 프로젝트 구조

```
app/src/main/
├── cpp/                              # 네이티브 코어 (C/C++)
│   ├── pingjvm.cpp                   # JVM 부팅 + grab 후킹 + DNS/SRV 포트 치환 + showingWindow 워치독
│   ├── pojav_jni/                    # PojavLauncher core
│   │   ├── ctxbridges/               # GL / EGL / OSMesa 컨텍스트 브릿지
│   │   ├── jvm_hooks/                # LWJGL dlopen / forkAndExec / EMUI 링커 후킹
│   │   ├── native_hooks/             # exit / chmod / getaddrinfo / resolv.conf 후킹 (bytehook)
│   │   ├── awt_xawt/xawt_fake.c      # libfontmanager.so resolve 통과용 X11FontScaler 스텁
│   │   ├── awt_bridge.c              # Cacio AWT 브릿지 (별도 .so 로 빌드)
│   │   └── driver_helper/            # Adreno Turnip 로더 / 네임스페이스 우회
│   └── CMakeLists.txt
├── java/kr/co/donghyun/FlameLauncher/
│   ├── data/                         # 도메인 모델
│   │   ├── auth/  curseforge/  instance/  jvm/  key/  mojang/  renderer/  setting/
│   └── presentation/                 # Compose UI + 실행 로직
│       ├── MainActivity.kt           # 버전 목록 + 실행 디스패처
│       ├── MinecraftActivity.kt      # 실제 JVM 부팅 + 클래스패스 조립 + 입력 라우팅
│       ├── ContentPackBrowserActivity.kt   # CurseForge 브라우저
│       ├── CrashReportActivity.kt
│       └── util/
│           ├── curseforge/           # 모드팩 설치, 의존성 해결
│           ├── fabric/               # Fabric meta + installer
│           ├── forge/                # Forge / NeoForge installer + processor 직렬화
│           ├── minecraft/            # Mojang downloader + JRE 추출
│           ├── dns/  hosts/          # DNS + hosts 리다이렉트 후킹
│           └── jni/                  # JavaNativeLauncher (JNI 진입)
└── java/
    ├── net/kdt/pojavlaunch/          # PojavLauncher 호환 진입점
    ├── org/lwjgl/glfw/               # CallbackBridge
    └── kr/.../forge/                 # ProcessorLauncher (임베디드 JVM 안에서 실행)
```

### 🚀 실행 흐름 (요약)

1. **버전 선택** → Mojang `version_manifest.json` 에서 메타 조회
2. **로더 선택** → Fabric / Forge / NeoForge 메타 API 호출
3. **MinecraftDownloader** → client jar + libraries + assets 를 인스턴스 디렉토리로 다운로드
4. **로더 설치** → FabricInstaller / ForgeInstaller 가 libraries 머지 (모던 Forge 는 프로세서를 직렬화해 최초 실행 시 client jar 패칭)
5. **JRE 추출** → `assets/jre/jreN.zip` → `filesDir/jreN_runtime/`
6. **MinecraftActivity** → 렌더러 `.so` 로드 → `pingjvm.cpp::bootMinecraftJVM` 호출
7. **JNI_CreateJavaVM** → `mainClass.main(args)` 호출 → 게임 부팅

### 📝 라이선스 및 기여

- PojavLauncher 코어: **GPLv3**
- **ZalithLauncher 2(ZL2)** 에서 차용한 방식: ZL2 측 라이선스를 따름
- 본 프로젝트의 추가 코드: 별도 라이선스 명시 전까지 저자에게 문의

---

<sub>🔥 Made with too much caffeine and not enough sleep.</sub>

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://coff.ee/ydh878787)
