# PingLauncher

Android에서 Minecraft: Java Edition을 실행하는 런처입니다. [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)를 기반으로 제작되었습니다.

## 기능

- **바닐라 마인크래프트** 지원 (1.21.4 이하)
- **Fabric 모드팩** — CurseForge API를 통한 설치
- **터치 컨트롤** — 커스터마이징 가능한 키 레이아웃 에디터
- **인스턴스 관리** — 모드팩/바닐라 버전별 독립 디렉토리
- **크래시 리포트 뷰어** — 크래시 감지 후 앱 내에서 모드 ON/OFF 토글
- **JVM 설정** — 힙 크기, 렌더 거리, 그래픽 모드 조정
- **핫바 슬롯 이동** — 화면 버튼(← / →)으로 슬롯 전환, 월드별 마지막 슬롯 저장
- **AWT 지원** — `java.awt`를 사용하는 모드(FancyMenu 등) 호환
- JRE 21 호환을 위한 `launchwrapper` 자동 바이트코드 패치 (Forge 1.12.2)

## 요구사항

- Android 8.0 이상 (API 26)
- ARM64-v8a 기기
- 여유 저장 공간 4GB 이상 권장
- JRE 21 (앱 에셋으로 번들 포함 `jre21.zip`)

## 아키텍처

```
MainActivity
└── 버전 선택 / 다운로드 UI
    └── MinecraftActivity
        ├── MinecraftSurface (SurfaceView — 게임 렌더링)
        ├── GameControllerView (Android View — 터치 버튼)
        └── JavaNativeLauncher → JNI → libjvm.so
```

### 주요 컴포넌트

| 파일 | 설명 |
|------|------|
| `MinecraftActivity.kt` | 게임 실행 액티비티, JVM 부트스트랩, 터치/키 처리 |
| `MinecraftSurface.kt` | SurfaceView 컴포저블, 터치 이벤트 처리 |
| `GameControllerView.kt` | 화면 컨트롤러 (Android View, 멀티터치 지원) |
| `MinecraftActivityBridge.kt` | JVM 콜백과 Android 브릿지 (그랩 상태, GUI scale, 월드 이름) |
| `ModPackBrowserActivity.kt` | CurseForge 모드팩 브라우저 (Fabric 전용) |
| `ModPackDetailActivity.kt` | 모드팩 상세 페이지 (스크린샷, 설명) |
| `CrashReportActivity.kt` | 크래시 리포트 뷰어 및 모드 토글 |
| `ForgeInstaller.kt` | Fabric 로더 설치 |
| `ModPackInstaller.kt` | CurseForge 모드팩 압축 해제 및 모드 다운로드 |
| `InstanceManager.kt` | 인스턴스 디렉토리 및 메타데이터 관리 |
| `pingjvm.cpp` | JNI 브릿지 — JVM 라이프사이클, `nativeSetGrabbing` 후킹, 윈도우 설정 |

## 기반 프로젝트 / 크레딧

이 프로젝트는 **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)** (LGPLv3)를 기반으로 하며, 다음과 같은 수정 및 추가 사항이 있습니다.

### PojavLauncher에서 가져온 것
- `jre_lwjgl3glfw` — 커스텀 LWJGL3/GLFW 스텁 JAR (`lwjgl-glfw-classes.jar`), 다음 항목 추가:
  - `sendKeycode()` / `sendKeyPress()` 메서드
  - `onGrabStateChanged()` Android 브릿지 콜백
  - `tryNotifyGuiScale()` — reflection으로 `net.minecraft.class_310`에서 GUI scale 읽기
- 네이티브 라이브러리: `libpojavexec.so`, `libglfw.so`, `liblwjgl.so`, `liblwjgl_opengl.so`, `libopenal.so`
- PojavLauncher APK에서 추출한 AWT 라이브러리: `libawt_xawt.so`, `libawt_headless.so`, `libpojavexec_awt.so`
- JRE 21 런타임 (`jre21.zip`)

### 자체 추가 사항
- Jetpack Compose 기반 전체 UI (핑크 테마)
- 인스턴스 기반 게임 디렉토리 구조 (`instances/<name>/`)
- CurseForge 모드팩 브라우저 (Fabric 전용 필터링)
- 비호환 모드 자동 감지 및 비활성화
- ASM을 이용한 `launchwrapper` 바이트코드 패치 (JRE 9+ 호환)
- `pingjvm.cpp`의 ARM64 함수 후킹으로 `nativeSetGrabbing` 그랩 상태 추적
- 커스텀 터치 처리: 카메라 회전, 롱프레스 블록 파괴, 우클릭 설치, 핫바 슬롯 선택
- 월드별 핫바 슬롯 저장
- OpenGL 호환성을 위한 NG-GL4ES (`libng_gl4es.so`) 통합

### 서드파티 라이브러리
- [NG-GL4ES (BZLZHH fork)](https://github.com/BZLZHH/NG-GL4ES) — OpenGL ES 변환기
- [lwjgl-boat](https://github.com/AOF-Dev/lwjgl-boat) — LWJGL2 연구 참고
- [ASM](https://asm.ow2.io/) — launchwrapper 패치를 위한 바이트코드 조작
- [Coil](https://coil-kt.github.io/coil/) — 이미지 로딩
- [Gson](https://github.com/google/gson) — JSON 직렬화
- [OkHttp](https://square.github.io/okhttp/) — HTTP 클라이언트

## 지원 환경

| 로더 | 버전 | 상태 |
|------|------|------|
| Vanilla | 1.8 – 1.21.4 | ✅ |
| Fabric | 전 버전 | ✅ |
| Forge | 1.12.2 | ⚠️ 부분 지원 (launchwrapper 패치됨, LWJGL2 미지원) |
| Forge | 1.17+ | ❌ Android JRE에 `jdk.nio.zipfs` 없음 |

## 알려진 비호환 모드

설치 시 자동으로 비활성화되는 모드 목록:

- `sodium`, `iris`, `reeses-sodium`, `sodium-extra` — OpenGL/셰이더 비호환
- `xaerominimap`, `xaeroworldmap` — OpenGL 프레임버퍼 비호환
- `create-fabric` 및 관련 모드 — 스텐실 버퍼 미지원
- `fabricskyboxes`, `fsb-interop` — 초기화 순서 크래시
- `fancymenu`, `drippyloadingscreen`, `welcomescreen` — `/data/.minecraft` 경로 접근 불가
- `colorwheel`, `colorwheel_patcher` — iris 의존
- `friendsforlife` — 서버 전용 네트워크 핸들러
- `particlerain` / `aaa_particles` — AMD64 네이티브 라이브러리로 ARM64 비호환

## 라이선스

이 프로젝트는 PojavLauncher를 계승하여 **GNU Lesser General Public License v3.0 (LGPLv3)** 라이선스를 따릅니다.

자세한 내용은 [LICENSE](LICENSE)를 참고하세요.

> PojavLauncher는 PojavLauncher Team의 저작물이며 LGPLv3 라이선스로 배포됩니다.
> 이 프로젝트는 PojavLauncher의 수정된 부분을 포함합니다. 모든 수정 사항은 위에 명시되어 있습니다.

## 면책 조항

이 런처는 정품 Minecraft: Java Edition을 구매한 사용자를 위한 것입니다.
Minecraft는 Mojang Studios / Microsoft의 상표입니다. 이 프로젝트는 Mojang 또는 Microsoft와 무관하며 공식적으로 승인된 것이 아닙니다.

---

# PingLauncher (English)

An Android launcher for Minecraft: Java Edition, built on top of [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher).

## Features

- **Vanilla Minecraft** support (up to 1.21.4)
- **Fabric modpack** installation via CurseForge API
- **Touch controls** with customizable key layout editor
- **Instance management** — each modpack/vanilla version in its own isolated directory
- **Crash report viewer** — detects crashes and lets you toggle mods on/off without leaving the app
- **JVM settings** — configurable heap size, render distance, and graphics mode
- **Hotbar slot navigation** via on-screen buttons (← / →), persisted per world save
- **AWT support** for mods that depend on `java.awt` (e.g. FancyMenu)
- Automatic patching of `launchwrapper` for JRE 21 compatibility (Forge 1.12.2)

## Requirements

- Android 8.0+ (API 26)
- ARM64-v8a device
- ~4 GB free storage recommended
- JRE 21 (bundled as `jre21.zip` asset)

## Architecture

```
MainActivity
└── Version selection / download UI
    └── MinecraftActivity
        ├── MinecraftSurface (SurfaceView — renders game)
        ├── GameControllerView (Android View — touch buttons)
        └── JavaNativeLauncher → JNI → libjvm.so
```

### Key Components

| File | Description |
|------|-------------|
| `MinecraftActivity.kt` | Game launch activity, JVM bootstrap, touch/key routing |
| `MinecraftSurface.kt` | SurfaceView composable, touch event handling |
| `GameControllerView.kt` | On-screen controller (Android View, multi-touch) |
| `MinecraftActivityBridge.kt` | Bridge between JVM callbacks and Android (grab state, GUI scale, world name) |
| `ModPackBrowserActivity.kt` | CurseForge modpack browser (Fabric only) |
| `ModPackDetailActivity.kt` | Modpack detail page (screenshots, description) |
| `CrashReportActivity.kt` | Crash report viewer with per-mod toggle |
| `ForgeInstaller.kt` | Fabric loader installer |
| `ModPackInstaller.kt` | CurseForge modpack unpacker and mod downloader |
| `InstanceManager.kt` | Per-instance directory and metadata management |
| `pingjvm.cpp` | JNI bridge — JVM lifecycle, `nativeSetGrabbing` hook, window setup |

## Based On / Credits

This project is heavily based on **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)** (LGPLv3), with the following modifications and additions:

### From PojavLauncher
- `jre_lwjgl3glfw` — Custom LWJGL3/GLFW stub JAR (`lwjgl-glfw-classes.jar`), modified to add:
  - `sendKeycode()` / `sendKeyPress()` methods
  - `onGrabStateChanged()` callback to Android bridge
  - `tryNotifyGuiScale()` — reads GUI scale from `net.minecraft.class_310` via reflection
- Native libraries: `libpojavexec.so`, `libglfw.so`, `liblwjgl.so`, `liblwjgl_opengl.so`, `libopenal.so`
- AWT libraries extracted from PojavLauncher APK: `libawt_xawt.so`, `libawt_headless.so`, `libpojavexec_awt.so`
- JRE 21 runtime (`jre21.zip`)

### Custom Additions
- Full Jetpack Compose UI (pink theme)
- Instance-based game directory structure (`instances/<name>/`)
- CurseForge modpack browser with Fabric-only filtering
- Automatic incompatible mod detection and disabling
- `launchwrapper` bytecode patching via ASM for JRE 9+ compatibility
- `nativeSetGrabbing` ARM64 function hook in `pingjvm.cpp` for grab state tracking
- Custom touch handling: camera rotation, long-press block break, right-click place, hotbar slot selection
- Per-world hotbar slot persistence
- NG-GL4ES (`libng_gl4es.so`) integration for OpenGL compatibility

### Third-party Libraries
- [NG-GL4ES (BZLZHH fork)](https://github.com/BZLZHH/NG-GL4ES) — OpenGL ES translator
- [lwjgl-boat](https://github.com/AOF-Dev/lwjgl-boat) — Referenced for LWJGL2 research
- [ASM](https://asm.ow2.io/) — Bytecode manipulation for launchwrapper patching
- [Coil](https://coil-kt.github.io/coil/) — Image loading
- [Gson](https://github.com/google/gson) — JSON serialization
- [OkHttp](https://square.github.io/okhttp/) — HTTP client

## Supported Configurations

| Loader | Version | Status |
|--------|---------|--------|
| Vanilla | 1.8 – 1.21.4 | ✅ |
| Fabric | All versions | ✅ |
| Forge | 1.12.2 | ⚠️ Partial (launchwrapper patched, LWJGL2 not supported) |
| Forge | 1.17+ | ❌ Requires `jdk.nio.zipfs` (not in Android JRE) |

## Known Incompatible Mods

The following mods are automatically disabled on install:

- `sodium`, `iris`, `reeses-sodium`, `sodium-extra` — OpenGL/shader incompatibility
- `xaerominimap`, `xaeroworldmap` — OpenGL framebuffer incompatibility
- `create-fabric` and related — Stencil buffer not supported
- `fabricskyboxes`, `fsb-interop` — Initialization order crash
- `fancymenu`, `drippyloadingscreen`, `welcomescreen` — `/data/.minecraft` path inaccessible
- `colorwheel`, `colorwheel_patcher` — Requires iris
- `friendsforlife` — Server-only network handlers
- `particlerain` / `aaa_particles` — AMD64 native library incompatible with ARM64

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPLv3)**, inherited from PojavLauncher.

See [LICENSE](LICENSE) for details.

> PojavLauncher is Copyright (C) 2020-2024 PojavLauncher Team, licensed under LGPLv3.
> This project includes modified portions of PojavLauncher. All modifications are documented above.

## Disclaimer

This launcher is intended for use with legitimately purchased copies of Minecraft: Java Edition.
Minecraft is a trademark of Mojang Studios / Microsoft. This project is not affiliated with or endorsed by Mojang or Microsoft.
