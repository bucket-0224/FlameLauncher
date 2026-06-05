#include <jni.h>
#include <dlfcn.h>
#include <vector>
#include <string>
#include <algorithm>
#include <android/log.h>
#include <sys/mman.h>


#include <unistd.h>
#include <pthread.h>
#include <stdio.h>

#include "environ/environ.h"  // 기존 include 옆에


#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

#define LOG_TAG "PingLauncherJVM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
// JNI_CreateJavaVM 함수 포인터 타입 정의
typedef jint (*CreateJavaVM_t)(JavaVM**, void**, void*);

// 💡 [핵심 추가] stdout/stderr에 찍히는 자바 로그를 실시간으로 낚아채 로그캣에 뿌려주는 백그라운드 쓰레드
static int pfd[2];
static pthread_t logging_thread;


void* stdout_logger_thread_func(void*) {
    ssize_t read_size;
    char buffer[512];
    while ((read_size = read(pfd[0], buffer, sizeof(buffer) - 1)) > 0) {
        buffer[read_size] = 0;
        // 로그캣에 "MinecraftJVM_IO"라는 태그로 마인크래프트 내부 출력을 중계합니다.
        __android_log_print(ANDROID_LOG_INFO, "MinecraftJVM_IO", "%s", buffer);
    }
    return nullptr;
}

// pingjvm.cpp에 추가
static bool g_isGrabbing = false;
typedef void (*SetGrabbing_t)(JNIEnv*, jclass, jboolean);
static SetGrabbing_t g_originalSetGrabbing = nullptr;

static int g_guiScale = 0;


extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_MinecraftActivity_nativeSetGuiScale(
        JNIEnv* env, jobject thiz, jint scale) {
    g_guiScale = scale;
    LOGI("GUI Scale 설정: %d", scale);
}

extern "C" JNIEXPORT jint JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_MinecraftActivity_nativeGetGuiScale(
        JNIEnv* env, jobject thiz) {
    return g_guiScale;
}

// 우리가 실제로 실행할 함수
void hookedSetGrabbing(JNIEnv* env, jclass clazz, jboolean grabbing) {
    g_isGrabbing = grabbing;
    LOGI("🎯 GrabState: %d", grabbing);

    if (!env) return;

    jclass cbClass = env->FindClass("org/lwjgl/glfw/CallbackBridge");
    if (env->ExceptionCheck()) {
        LOGE("FindClass(CallbackBridge) threw, clearing");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }
    if (!cbClass) {
        LOGE("CallbackBridge class not found (no exception?)");
        return;
    }

    jmethodID onGrab = env->GetStaticMethodID(cbClass, "onGrabStateChanged", "(Z)V");
    if (env->ExceptionCheck()) {
        LOGE("GetStaticMethodID(onGrabStateChanged) threw, clearing");
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(cbClass);
        return;
    }
    if (!onGrab) {
        LOGE("onGrabStateChanged method not found");
        env->DeleteLocalRef(cbClass);
        return;
    }

    env->CallStaticVoidMethod(cbClass, onGrab, grabbing);
    if (env->ExceptionCheck()) {
        LOGE("CallStaticVoidMethod(onGrabStateChanged) threw, clearing");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    env->DeleteLocalRef(cbClass);
}

// JVM 생성 후 후킹 설치
static void installGrabbingHook() {
    void* handle = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (!handle) { LOGE("libpojavexec.so not found"); return; }

    void* sym = dlsym(handle, "Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing");
    if (!sym) { LOGE("nativeSetGrabbing symbol not found"); return; }

    g_originalSetGrabbing = (SetGrabbing_t)sym;

    // 함수 포인터 주소를 hookedSetGrabbing으로 교체
    uintptr_t addr = (uintptr_t)sym;
    uintptr_t pageStart = addr & ~(getpagesize() - 1);
    mprotect((void*)pageStart, getpagesize() * 2, PROT_READ | PROT_WRITE | PROT_EXEC);

    // arm64 절대 점프 패치
    uint32_t patch[4];
    patch[0] = 0x58000051; // ldr x17, #8
    patch[1] = 0xd61f0220; // br x17
    uint64_t target = (uint64_t)hookedSetGrabbing;
    memcpy(&patch[2], &target, 8);
    memcpy((void*)addr, patch, sizeof(patch));

    __builtin___clear_cache((char*)addr, (char*)addr + sizeof(patch));
    LOGI("✅ nativeSetGrabbing 후킹 완료");
}

static void registerGLFWGamepadNative(JNIEnv* customEnv) {
    LOGI("🎯 GLFW.internalGetGamepadDataPointer RegisterNatives 시도");

    jclass glfwClass = customEnv->FindClass("org/lwjgl/glfw/GLFW");
    if (customEnv->ExceptionCheck()) {
        LOGE("  FindClass 중 예외 발생");
        customEnv->ExceptionClear();
    }
    if (glfwClass == nullptr) {
        LOGE("  ❌ org/lwjgl/glfw/GLFW 클래스 못 찾음 (아직 classpath에 미로드)");
        return;
    }


    LOGI("  ✅ GLFW 클래스 찾음");

    // 우리가 빌드한 libglfw.so 우선, 그 다음 libpojavexec.so 폴백
    const char* libCandidates[] = { "libglfw.so", "libpojavexec.so", nullptr };
    void* fn = nullptr;
    for (int i = 0; libCandidates[i]; i++) {
        void* h = dlopen(libCandidates[i], RTLD_NOLOAD | RTLD_NOW);
        if (!h) {
            LOGI("  %s 로드 안 되어 있음 — 다음 후보 시도", libCandidates[i]);
            continue;
        }
        fn = dlsym(h, "Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer");
        if (fn) {
            LOGI("  ✅ 심볼 발견 in %s: %p", libCandidates[i], fn);
            break;
        }
        LOGI("  %s 안에 심볼 없음", libCandidates[i]);
    }

    if (!fn) {
        // 마지막 시도: 현재 프로세스 전체 심볼 테이블에서 검색
        fn = dlsym(RTLD_DEFAULT, "Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer");
        if (fn) {
            LOGI("  ✅ RTLD_DEFAULT에서 심볼 발견: %p", fn);
        } else {
            LOGE("  ❌ 어디에서도 심볼 못 찾음");
            customEnv->DeleteLocalRef(glfwClass);
            return;
        }
    }

    JNINativeMethod m[] = {
            { (char*)"internalGetGamepadDataPointer", (char*)"()J", fn }
    };
    jint r = customEnv->RegisterNatives(glfwClass, m, 1);
    if (r != 0 || customEnv->ExceptionCheck()) {
        LOGE("  ❌ RegisterNatives 실패: %d", r);
        if (customEnv->ExceptionCheck()) {
            customEnv->ExceptionDescribe();
            customEnv->ExceptionClear();
        }
    } else {
        LOGI("  ✅ RegisterNatives 성공");
    }

    customEnv->DeleteLocalRef(glfwClass);
}

// libpojavexec.so가 이 함수를 JNI로 호출함
// 우리가 먼저 등록하면 intercept 가능
extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(
        JNIEnv* env, jclass clazz, jboolean grabbing) {
    g_isGrabbing = grabbing;
    LOGI("🎯 GrabState 변경: %d", grabbing);

    // 원본 함수도 호출
    void* handle = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (handle) {
        typedef void (*SetGrabbing_t)(JNIEnv*, jclass, jboolean);
        SetGrabbing_t fn = (SetGrabbing_t)dlsym(handle,
                                                "Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing");
        if (fn) fn(env, clazz, grabbing);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_MinecraftActivity_nativeIsGrabbing(
        JNIEnv* env, jobject thiz) {
    return g_isGrabbing ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_MinecraftActivity_nativeSetupBridgeWindow(
        JNIEnv* env, jobject thiz, jobject surface) {
    LOGI("nativeSetupBridgeWindow 호출됨");
    typedef void (*SetupBridgeWindow_t)(JNIEnv*, jclass, jobject);

    const char* libs[] = { "libglfw.so", "libpojavexec.so", nullptr };
    for (int i = 0; libs[i]; i++) {
        void* h = dlopen(libs[i], RTLD_NOLOAD | RTLD_NOW);
        if (!h) continue;
        SetupBridgeWindow_t fn = (SetupBridgeWindow_t)dlsym(h,
                                                            "Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow");
        if (fn) {
            LOGI("setupBridgeWindow 호출 → %s (fn=%p)", libs[i], fn);
            fn(env, nullptr, surface);
        }
    }
}

// Caused by 체인까지 재귀 출력
static void printJavaException(JNIEnv* env, jthrowable ex, int depth) {
    if (!ex || depth > 8) return;

    jclass exClass = env->GetObjectClass(ex);
    if (!exClass) return;

    jmethodID toStringMethod = env->GetMethodID(exClass, "toString", "()Ljava/lang/String;");
    if (toStringMethod) {
        jstring msgString = (jstring)env->CallObjectMethod(ex, toStringMethod);
        if (msgString) {
            const char* msgC = env->GetStringUTFChars(msgString, nullptr);
            if (depth == 0) LOGE("❌ 예외: %s", msgC);
            else            LOGE("⤷ Caused by: %s", msgC);
            env->ReleaseStringUTFChars(msgString, msgC);
            env->DeleteLocalRef(msgString);
        }
    }

    jmethodID getStackTraceMethod = env->GetMethodID(exClass, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    if (getStackTraceMethod) {
        jobjectArray stackTrace = (jobjectArray)env->CallObjectMethod(ex, getStackTraceMethod);
        if (stackTrace) {
            jsize length = env->GetArrayLength(stackTrace);
            int maxFrames = (depth == 0) ? 30 : 20;
            for (int i = 0; i < length && i < maxFrames; i++) {
                jobject element = env->GetObjectArrayElement(stackTrace, i);
                if (element) {
                    jclass elementClass = env->GetObjectClass(element);
                    jmethodID elementToString = env->GetMethodID(elementClass, "toString", "()Ljava/lang/String;");
                    if (elementToString) {
                        jstring elementString = (jstring)env->CallObjectMethod(element, elementToString);
                        if (elementString) {
                            const char* elementC = env->GetStringUTFChars(elementString, nullptr);
                            LOGE("    at %s", elementC);
                            env->ReleaseStringUTFChars(elementString, elementC);
                            env->DeleteLocalRef(elementString);
                        }
                    }
                    env->DeleteLocalRef(elementClass);
                    env->DeleteLocalRef(element);
                }
            }
            env->DeleteLocalRef(stackTrace);
        }
    }

    jmethodID getCauseMethod = env->GetMethodID(exClass, "getCause", "()Ljava/lang/Throwable;");
    if (getCauseMethod) {
        jthrowable cause = (jthrowable)env->CallObjectMethod(ex, getCauseMethod);
        if (cause && !env->IsSameObject(cause, ex)) {
            printJavaException(env, cause, depth + 1);
        }
        if (cause) env->DeleteLocalRef(cause);
    }

    env->DeleteLocalRef(exClass);
}

extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_util_jni_JavaNativeLauncher_preloadAwtStubs(
        JNIEnv* env, jclass clazz, jstring nativeLibDir) {
    const char* dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    std::string path = std::string(dir) + "/libpojavexec.so";
    env->ReleaseStringUTFChars(nativeLibDir, dir);

    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (h) {
        LOGI("✅ libpojavexec.so EARLY RTLD_GLOBAL: %s", path.c_str());
    } else {
        LOGE("❌ EARLY-LOAD 실패: %s", dlerror());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_util_jni_JavaNativeLauncher_nativeSetEnv(
        JNIEnv* env, jobject thiz, jstring key, jstring value) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    setenv(k, v, 1);
    LOGI("setenv: %s=%s", k, v);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(value, v);
}


extern "C" JNIEXPORT jint JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_util_jni_JavaNativeLauncher_bootMinecraftJVM(
        JNIEnv* env, jobject thiz, jstring lib_jvm_path, jobjectArray jvm_args, jobjectArray mc_args) {

    // ── Renderer 환경변수 기본값 ─────────────────────────────────
    if (!getenv("POJAV_RENDERER"))  setenv("POJAV_RENDERER",  "vulkan_zink", 0);
    if (!getenv("LIBGL_STRING"))    setenv("LIBGL_STRING",    "VulkanGL",    0);
    if (!getenv("LIBGL_NAME"))      setenv("LIBGL_NAME",      "libltw.so",   0);
    if (!getenv("DLOPEN"))          setenv("DLOPEN",          "libltw.so",   0);
    if (!getenv("LIBGL_ES"))        setenv("LIBGL_ES",        "3",           0);
    if (!getenv("FORCE_VSYNC"))     setenv("FORCE_VSYNC",     "false",       0);
    if (!getenv("POJAV_VSYNC"))     setenv("POJAV_VSYNC",     "1",           0);

    LOGI("🎨 Renderer env at boot: POJAV_RENDERER=%s, LIBGL_NAME=%s, LIBGL_ES=%s",
         getenv("POJAV_RENDERER"), getenv("LIBGL_NAME"), getenv("LIBGL_ES"));

    // ── stdout/stderr → logcat 파이프 ────────────────────────────
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IOLBF, 0);
    pipe(pfd);
    dup2(pfd[1], STDOUT_FILENO);
    dup2(pfd[1], STDERR_FILENO);
    pthread_create(&logging_thread, nullptr, stdout_logger_thread_func, nullptr);
    pthread_detach(logging_thread);

    // ── libjvm.so 로드 ──────────────────────────────────────────
    const char* path = env->GetStringUTFChars(lib_jvm_path, nullptr);
    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);

    if (!handle) {
        LOGE("libjvm.so 최종 로드 실패: %s", dlerror());
        env->ReleaseStringUTFChars(lib_jvm_path, path);
        return -1;
    }

    // 경로 파싱: .../jre8_runtime/lib/aarch64/server/libjvm.so
    //  → server_lib_dir = .../jre8_runtime/lib/aarch64/server
    //  → java_lib_dir   = .../jre8_runtime/lib/aarch64
    std::string jvm_path_str(path);
    size_t last_slash = jvm_path_str.find_last_of("/");
    std::string server_lib_dir = jvm_path_str.substr(0, last_slash);
    size_t parent_slash = server_lib_dir.find_last_of("/");
    std::string java_lib_dir = server_lib_dir.substr(0, parent_slash);

    env->ReleaseStringUTFChars(lib_jvm_path, path);

    // ── 공통 헬퍼 ────────────────────────────────────────────────
    auto preload = [](const std::string& p, const char* label) -> bool {
        void* h = dlopen(p.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (h) {
            LOGI("  ✅ preload: %s (%s)", label, p.c_str());
            return true;
        } else {
            LOGE("  ❌ preload FAILED (%s): %s", label, dlerror());
            return false;
        }
    };

    auto is_already_loaded = [](const std::string& p) -> bool {
        void* h = dlopen(p.c_str(), RTLD_NOLOAD | RTLD_NOW);
        if (h) {
            dlclose(h); // refcount 원복
            return true;
        }
        return false;
    };

    // ── ★★★ 가장 먼저: APK 의 패치된 libawt_xawt.so ★★★ ──────────
    // 이유: JRE 의 libfontmanager.so 가 SONAME 으로 libawt_xawt.so 를 찾고
    // 그 안에서 AWTCountFonts/AWTFreeChar 등 17 개 X11FontScaler 심볼을 resolve 한다.
    // JRE 본가의 libawt_xawt.so 에는 이 심볼이 없으므로 (X11 없는 Android 라서),
    // 우리가 xawt_fake.c 의 노옵 스텁으로 만든 APK 버전을 SONAME 으로 먼저 점유시켜야
    // 이후 libfontmanager.so dlopen 이 성공한다.
    //
    // 순서가 뒤집히면 (JRE 가 먼저) → SONAME 이 JRE 버전으로 고정되어
    // 이후 APK 버전 dlopen 은 no-op 가 되고, libfontmanager.so 는 미해결 심볼로 실패.
    const char* apk_native_dir = getenv("POJAV_NATIVEDIR");

    if (apk_native_dir) {
        std::string pojavexec_awt_apk = std::string(apk_native_dir) + "/libpojavexec_awt.so";
        preload(pojavexec_awt_apk, "libpojavexec_awt.so (Cacio AWT bridge)");
    }

    if (apk_native_dir) {
        std::string apk_xawt = std::string(apk_native_dir) + "/libawt_xawt.so";
        if (!preload(apk_xawt, "libawt_xawt.so (APK patched, MUST be first)")) {
            LOGE("⚠️ APK libawt_xawt.so 로드 실패 — libfontmanager.so 도 곧 실패할 것");
        }
    } else {
        LOGE("⚠️ POJAV_NATIVEDIR 미설정 — APK libawt_xawt.so 로드 불가");
    }

    // ── pojavexec.so (Pojav 내부 JNI 브릿지) ────────────────────
    if (apk_native_dir) {
        std::string pojavexec_apk = std::string(apk_native_dir) + "/libpojavexec.so";
        preload(pojavexec_apk, "libpojavexec.so (APK)");
    }

    // ── OpenJDK 의존성 체인 (verify → java → zip → net → nio) ──
    std::string verify_path = java_lib_dir + "/libverify.so";
    std::string java_path   = java_lib_dir + "/libjava.so";
    std::string zip_path    = java_lib_dir + "/libzip.so";
    std::string net_path    = java_lib_dir + "/libnet.so";
    std::string nio_path    = java_lib_dir + "/libnio.so";

    preload(verify_path, "libverify.so");
    preload(java_path,   "libjava.so");
    preload(zip_path,    "libzip.so");
    preload(net_path,    "libnet.so");
    preload(nio_path,    "libnio.so");

    // ── Legacy AWT 체인 (1.5.2 ~ 1.12.2 호환) ───────────────────
    // libawt → libawt_xawt(JRE, 이미 SONAME 점유됨) → libawt_headless → libfontmanager
    std::string jre_lib_dir = java_path.substr(0, java_path.find_last_of('/'));

    preload(jre_lib_dir + "/libawt.so", "libawt.so");

    // JRE 의 libawt_xawt.so 는 명시적으로 호출만 — 이미 APK 버전이 SONAME 을 점유했기에
    // 이건 사실상 no-op 가 되어야 정상. 만약 여기서 "성공" 메시지가 뜨고 dlsym 으로
    // X11FontScaler 심볼을 못 찾는다면 SONAME 점유가 실패했다는 신호.
    if (is_already_loaded("libawt_xawt.so")) {
        LOGI("  ℹ️ libawt_xawt.so SONAME 이미 점유됨 (예상대로 APK 버전이 win)");
    } else {
        LOGE("  ⚠️ libawt_xawt.so SONAME 이 비어있음 — APK preload 실패 가능성");
        preload(jre_lib_dir + "/libawt_xawt.so", "libawt_xawt.so (JRE fallback)");
    }

    preload(jre_lib_dir + "/libawt_headless.so", "libawt_headless.so");

    // ── libfontmanager.so — 진실의 순간 ─────────────────────────
    // 여기서 실패하면 dlerror 에 어떤 심볼이 빠졌는지 정확히 찍힌다.
    // "cannot locate symbol 'AWTXxx'" 식으로 나오면 xawt_fake.c 에 해당 스텁 추가.
    if (!preload(jre_lib_dir + "/libfontmanager.so", "libfontmanager.so")) {
        LOGE("⚠️ libfontmanager.so 로드 실패 — 폰트 렌더링은 동작 안 할 수 있음");
        LOGE("    → xawt_fake.c 에 누락된 X11FontScaler 스텁 추가 필요");
    }

    LOGI("✅ 자바 핵심 의존성 라이브러리 선행 로드 완료");

    // ── JNI_CreateJavaVM 심볼 획득 ──────────────────────────────
    CreateJavaVM_t createJavaVM = (CreateJavaVM_t)dlsym(handle, "JNI_CreateJavaVM");
    if (!createJavaVM) {
        LOGE("JNI_CreateJavaVM 심볼을 찾을 수 없습니다: %s", dlerror());
        dlclose(handle);
        return -2;
    }

    // ── JVM Arguments 조립 ─────────────────────────────────────
    jsize jvm_arg_count = env->GetArrayLength(jvm_args);
    std::vector<JavaVMOption> options(jvm_arg_count);

    for (int i = 0; i < jvm_arg_count; ++i) {
        jstring arg = (jstring)env->GetObjectArrayElement(jvm_args, i);
        const char* raw_arg = env->GetStringUTFChars(arg, nullptr);
        options[i].optionString = strdup(raw_arg);
        options[i].extraInfo = nullptr;
        LOGI("전달된 JVM 인자 [%d]: %s", i, options[i].optionString);
        env->ReleaseStringUTFChars(arg, raw_arg);
    }

    JavaVMInitArgs vm_args;
    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = jvm_arg_count;
    vm_args.options = options.data();
    vm_args.ignoreUnrecognized = JNI_TRUE;

    // user.dir 처리
    for (int i = 0; i < jvm_arg_count; i++) {
        const char* opt = options[i].optionString;
        if (strncmp(opt, "-Duser.dir=", 11) == 0) {
            const char* dir = opt + 11;
            LOGI("chdir to: %s", dir);
            chdir(dir);
            break;
        }
    }

    // ── JVM 부팅 ────────────────────────────────────────────────
    JavaVM* customVM = nullptr;
    JNIEnv* customEnv = nullptr;

    LOGI("JNI_CreateJavaVM 호출 중...");
    jint res = createJavaVM(&customVM, (void**)&customEnv, &vm_args);

    if (res != JNI_OK) {
        LOGE("JVM 생성 실패: 코드 %d", res);
        return -3;
    }
    LOGI("✅ 내장 JVM 부팅 성공!");
    installGrabbingHook();

    // ── 마인크래프트 메인 클래스 로드 ───────────────────────────
    // 시스템 프로퍼티에서 mainClass 읽기
    jclass systemClass = customEnv->FindClass("java/lang/System");
    jmethodID getPropMethod = customEnv->GetStaticMethodID(
            systemClass, "getProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring propKey = customEnv->NewStringUTF("ping.main.class");
    jstring propDefault = customEnv->NewStringUTF("net.minecraft.client.main.Main");
    jstring propValue = (jstring)customEnv->CallStaticObjectMethod(
            systemClass, getPropMethod, propKey, propDefault);
    const char* mainClassNameRaw = customEnv->GetStringUTFChars(propValue, nullptr);

    // dot → slash 변환
    std::string mainClassNameStr(mainClassNameRaw);
    std::replace(mainClassNameStr.begin(), mainClassNameStr.end(), '.', '/');
    customEnv->ReleaseStringUTFChars(propValue, mainClassNameRaw);

    LOGI("mainClass: %s", mainClassNameStr.c_str());

    jclass mainClass = customEnv->FindClass(mainClassNameStr.c_str());
    if (mainClass == nullptr) {
        if (customEnv->ExceptionCheck()) {
            LOGE("🚨 OpenJDK 내부 예외 발생!");
            jthrowable ex = customEnv->ExceptionOccurred();
            customEnv->ExceptionClear();
            printJavaException(customEnv, ex, 0);
            customEnv->DeleteLocalRef(ex);
        }
        LOGE("마인크래프트 메인 클래스를 찾을 수 없습니다.");
        return -4;
    }

    jmethodID mainMethod = customEnv->GetStaticMethodID(
            mainClass, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) {
        LOGE("main 메서드를 찾을 수 없습니다.");
        return -5;
    }

    // ── 마인크래프트 실행 인자 배열 생성 ────────────────────────
    jsize mc_arg_count = env->GetArrayLength(mc_args);
    jclass stringClass = customEnv->FindClass("java/lang/String");
    jobjectArray mc_args_for_jvm = customEnv->NewObjectArray(mc_arg_count, stringClass, nullptr);

    for (int i = 0; i < mc_arg_count; ++i) {
        jstring arg = (jstring)env->GetObjectArrayElement(mc_args, i);
        const char* raw_arg = env->GetStringUTFChars(arg, nullptr);
        jstring jvm_str = customEnv->NewStringUTF(raw_arg);
        customEnv->SetObjectArrayElement(mc_args_for_jvm, i, jvm_str);
        env->ReleaseStringUTFChars(arg, raw_arg);
    }

    // ── 🚀 Minecraft 실행 ──────────────────────────────────────
    LOGI("%s.main() 실행!", mainClassNameStr.c_str());
    customEnv->CallStaticVoidMethod(mainClass, mainMethod, mc_args_for_jvm);

    if (customEnv->ExceptionCheck()) {
        LOGE("🚨 마인크래프트 실행 중 치명적인 자바 예외가 발생했습니다!");
        jthrowable ex = customEnv->ExceptionOccurred();
        customEnv->ExceptionClear();
        printJavaException(customEnv, ex, 0);
        customEnv->DeleteLocalRef(ex);
        return -6;
    }

    return 0;
}

// 한 방에 처리: environ 에서 mainWindowBundle 꺼내서 nglfwSetShowingWindow 호출
extern "C" JNIEXPORT jboolean JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_MinecraftActivity_nativeTrySetupShowingWindow(
        JNIEnv* env, jobject thiz) {

    // 1) environ 확인
    const char* env_str = getenv("POJAV_ENVIRON");
    if (!env_str) {
        return JNI_FALSE;
    }
    char* endptr;
    uintptr_t environ_ptr = strtoul(env_str, &endptr, 16);
    if (*endptr != '\0' || environ_ptr == 0) {
        return JNI_FALSE;
    }

    // 2) mainWindowBundle 위치는 환경구조체의 두 번째 필드 (offset = sizeof(void*))
    void** mainWindowBundle_loc = (void**)(environ_ptr + sizeof(void*));
    void* mainWindowBundle = *mainWindowBundle_loc;
    if (!mainWindowBundle) {
        return JNI_FALSE;
    }

    // 3) nglfwSetShowingWindow 호출
    void* h = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (!h) h = dlopen("libglfw.so", RTLD_NOLOAD | RTLD_NOW);
    if (!h) {
        LOGE("nativeTrySetupShowingWindow: libpojavexec/libglfw 미로드");
        return JNI_FALSE;
    }

    typedef void (*SetShowing_t)(JNIEnv*, jclass, jlong);
    SetShowing_t fn = (SetShowing_t)dlsym(
            h, "Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow");
    if (!fn) {
        LOGE("nglfwSetShowingWindow 심볼 없음");
        return JNI_FALSE;
    }

    fn(env, nullptr, (jlong)(uintptr_t)mainWindowBundle);
    LOGI("✅ showingWindow = mainWindowBundle = %p", mainWindowBundle);
    return JNI_TRUE;
}

// 현재 environ 의 상태를 한 번에 덤프
extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_MinecraftActivity_nativeDumpInputState(
        JNIEnv* env, jobject thiz) {
    const char* env_str = getenv("POJAV_ENVIRON");
    if (!env_str) {
        LOGI("DUMP: POJAV_ENVIRON 없음");
        return;
    }
    char* endptr;
    uintptr_t environ_ptr = strtoul(env_str, &endptr, 16);
    if (*endptr != '\0' || environ_ptr == 0) {
        LOGI("DUMP: environ 파싱 실패");
        return;
    }

    void* h = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (!h) h = dlopen("libglfw.so", RTLD_NOLOAD | RTLD_NOW);

    void** main_bundle = (void**)(environ_ptr + sizeof(void*));
    LOGI("DUMP: mainWindowBundle = %p", *main_bundle);
    LOGI("DUMP: pojavWindow      = %p", *(void**)environ_ptr);
}
extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_MinecraftActivity_nativeDumpCharCallback(
        JNIEnv* env, jobject thiz) {
    const char* env_str = getenv("POJAV_ENVIRON");
    if (!env_str) { LOGI("DUMP: no POJAV_ENVIRON"); return; }
    char* endptr;
    uintptr_t ptr = strtoul(env_str, &endptr, 16);
    if (*endptr != '\0' || ptr == 0) {
        LOGI("DUMP: environ parse fail");
        return;
    }

    void* h = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (h) {
        void* p1 = dlsym(h, "Java_org_lwjgl_glfw_GLFW_nglfwSetCharCallback");
        void* p2 = dlsym(h, "Java_org_lwjgl_glfw_GLFW_nglfwSetKeyCallback");
        void* p3 = dlsym(h, "Java_org_lwjgl_glfw_GLFW_nglfwSetMouseButtonCallback");
        LOGI("DUMP: dlsym nglfwSetCharCallback        = %p", p1);
        LOGI("DUMP: dlsym nglfwSetKeyCallback         = %p", p2);
        LOGI("DUMP: dlsym nglfwSetMouseButtonCallback = %p", p3);
    }

    struct pojav_environ_s* e = (struct pojav_environ_s*)ptr;

    LOGI("DUMP: GLFW_invoke_Char     = %p", (void*)e->GLFW_invoke_Char);
    LOGI("DUMP: GLFW_invoke_CharMods = %p", (void*)e->GLFW_invoke_CharMods);  // ★ 추가
    LOGI("DUMP: GLFW_invoke_Key      = %p", (void*)e->GLFW_invoke_Key);
    LOGI("DUMP: pojav_environ      = %p", e);
    LOGI("DUMP: mainWindowBundle   = %p", e->mainWindowBundle);
    LOGI("DUMP: showingWindow      = 0x%lx", (long)e->showingWindow);
    LOGI("DUMP: isInputReady       = %d", e->isInputReady);
    LOGI("DUMP: isGrabbing         = %d", e->isGrabbing);
    LOGI("DUMP: GLFW_invoke_Char   = %p", (void*)e->GLFW_invoke_Char);
    LOGI("DUMP: GLFW_invoke_Key    = %p", (void*)e->GLFW_invoke_Key);
    LOGI("DUMP: GLFW_invoke_MouseButton = %p", (void*)e->GLFW_invoke_MouseButton);
}