cmake_minimum_required(VERSION 3.22.1)
project("pingjvm")

# ── bytehook (AAR에서 제공하는 CMake config) ───────────────────
find_package(bytehook REQUIRED CONFIG)

# ── 시스템 라이브러리 ─────────────────────────────────────────
find_library(log-lib log)
find_library(EGL-lib EGL)
find_library(GLESv2-lib GLESv2)
find_library(android-lib android)
find_library(dl-lib dl)

# ── pingjvm (JVM 부팅) ────────────────────────────────────────
add_library(pingjvm SHARED pingjvm.cpp)
target_link_libraries(pingjvm ${log-lib})

# ── PojavLauncher core 소스 (awt_bridge.c 제외) ───────────────
set(POJAV_SOURCES
        pojav_jni/egl_bridge.c
        pojav_jni/utils.c
        pojav_jni/stdio_is.c
        pojav_jni/bigcoreaffinity.c
        pojav_jni/input_bridge_v3.c
        pojav_jni/environ/environ.c
        pojav_jni/ctxbridges/gl_bridge.c
        pojav_jni/ctxbridges/egl_loader.c
        pojav_jni/ctxbridges/osm_bridge.c
        pojav_jni/ctxbridges/osmesa_loader.c
        pojav_jni/ctxbridges/loader_dlopen.c
        pojav_jni/ctxbridges/swap_interval_no_egl.c
        pojav_jni/driver_helper/nsbypass.c
        pojav_jni/driver_helper/hook.c
        pojav_jni/awt_xawt/xawt_fake.c
        pojav_jni/jvm_hooks/lwjgl_dlopen_hook.c
        pojav_jni/jvm_hooks/java_exec_hooks.c
        pojav_jni/jvm_hooks/emui_iterator_fix_hook.c
        pojav_jni/native_hooks/exit_hook.c
        pojav_jni/native_hooks/chmod_hook.c
        pojav_jni/jre_launcher.c
)

set(POJAV_INCLUDES
        pojav_jni
        pojav_jni/GL
        pojav_jni/ctxbridges
        pojav_jni/environ
        pojav_jni/driver_helper
        pojav_jni/jvm_hooks
        pojav_jni/native_hooks
        pojav_jni/awt_xawt
)

set(POJAV_WARN_FLAGS
        -Wno-implicit-function-declaration
        -Wno-incompatible-pointer-types
        -Wno-int-conversion
        -Wno-deprecated-declarations
        -Wno-unused-parameter
)

# ── libglfw.so ────────────────────────────────────────────────
add_library(glfw SHARED ${POJAV_SOURCES})
target_include_directories(glfw PRIVATE ${POJAV_INCLUDES})
target_compile_options(glfw PRIVATE ${POJAV_WARN_FLAGS})
target_link_libraries(glfw
        ${log-lib}
        ${EGL-lib}
        ${GLESv2-lib}
        ${android-lib}
        ${dl-lib}
        bytehook::bytehook
)

# ── libpojavexec.so ──────────────────────────────────────────
add_library(pojavexec SHARED ${POJAV_SOURCES})
target_include_directories(pojavexec PRIVATE ${POJAV_INCLUDES})
target_compile_options(pojavexec PRIVATE ${POJAV_WARN_FLAGS})
target_link_libraries(pojavexec
        ${log-lib}
        ${EGL-lib}
        ${GLESv2-lib}
        ${android-lib}
        ${dl-lib}
        bytehook::bytehook
)

# ── libpojavexec_awt.so (AWT/Cacio 브릿지 분리) ───────────────
# awt_bridge.c 의 JNI_OnLoad 가 input_bridge_v3.c 의 것과 충돌하므로
# PojavLauncher 본가처럼 별도 .so 로 분리한다.
# MinecraftActivity 가 jre lib 디렉토리에 이 이름으로 복사하고 있음.
add_library(pojavexec_awt SHARED
        pojav_jni/awt_bridge.c
)
target_include_directories(pojavexec_awt PRIVATE ${POJAV_INCLUDES})
target_compile_options(pojavexec_awt PRIVATE ${POJAV_WARN_FLAGS})
target_link_libraries(pojavexec_awt
        ${log-lib}
        ${android-lib}
        ${dl-lib}
)