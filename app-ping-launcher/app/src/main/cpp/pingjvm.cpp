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
    // g_originalSetGrabbing 호출 제거 — 원본이 패치됐으므로 무한루프 발생

    // libpojavexec.so의 내부 grabbing 처리를 직접 호출
    // nativeSetGrabbing의 실제 동작: grabbing 상태 저장 + CallbackBridge.onGrabStateChanged 호출
    // onGrabStateChanged는 jre_lwjgl3glfw에서 처리하므로 JVM에서 직접 호출
    if (env) {
        jclass cbClass = env->FindClass("org/lwjgl/glfw/CallbackBridge");
        if (cbClass) {
            jmethodID onGrab = env->GetStaticMethodID(cbClass, "onGrabStateChanged", "(Z)V");
            if (onGrab) env->CallStaticVoidMethod(cbClass, onGrab, grabbing);
        }
    }
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
    LOGI("MinecraftActivity: nativeSetupBridgeWindow 호출됨");
    typedef void (*SetupBridgeWindow_t)(JNIEnv*, jclass, jobject);
    void* handle = dlopen("libglfw.so", RTLD_NOLOAD | RTLD_NOW);
    if (!handle) {
        LOGE("libglfw.so not loaded: %s", dlerror());
        return;
    }
    SetupBridgeWindow_t fn = (SetupBridgeWindow_t)dlsym(handle,
                                                        "Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow");
    if (!fn) {
        LOGE("setupBridgeWindow 심볼 없음: %s", dlerror());
        return;
    }
    fn(env, nullptr, surface);
    LOGI("MinecraftActivity: nativeSetupBridgeWindow 완료");
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

extern "C" JNIEXPORT jint JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_util_jni_JavaNativeLauncher_bootMinecraftJVM(
        JNIEnv* env, jobject thiz, jstring lib_jvm_path, jobjectArray jvm_args, jobjectArray mc_args) {

    setenv("POJAV_RENDERER", "vulkan_ltw", 1);
    setenv("LIBGL_STRING", "VulkanGL", 1);
    setenv("LIBGL_NAME", "libltw.so", 1);
    setenv("DLOPEN", "libltw.so", 1);
    setenv("MESA_GLSL_CACHE_DIR", "/data/data/kr.co.donghyun.pinglauncher/cache", 1);
    setenv("FORCE_VSYNC", "false", 1);
    setenv("POJAV_VSYNC", "1", 1);


    // =========================================================================
    // 🎯 [여기서부터 새로 추가] 자바 가상머신 표준 출력 가로채기 파이프라인 가동
    // =========================================================================
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IOLBF, 0);
    pipe(pfd);
    dup2(pfd[1], STDOUT_FILENO);
    dup2(pfd[1], STDERR_FILENO);
    pthread_create(&logging_thread, nullptr, stdout_logger_thread_func, nullptr);
    pthread_detach(logging_thread);
    // =========================================================================


    const char* path = env->GetStringUTFChars(lib_jvm_path, nullptr);
    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL); // 대장 먼저 켜기!

    if (!handle) {
        LOGE("libjvm.so 최종 로드 실패: %s", dlerror());
        env->ReleaseStringUTFChars(lib_jvm_path, path);
        return -1;
    }

    // 경로 파싱
    std::string jvm_path_str(path);
    size_t last_slash = jvm_path_str.find_last_of("/");
    std::string server_lib_dir = jvm_path_str.substr(0, last_slash);
    size_t parent_slash = server_lib_dir.find_last_of("/");
    std::string java_lib_dir = server_lib_dir.substr(0, parent_slash);

    env->ReleaseStringUTFChars(lib_jvm_path, path);

    // =========================================================================
    // 🎯 1-1. OpenJDK 의존성 순서에 맞춰 필수 라이브러리들을 차례대로 전역 로드
    // =========================================================================
    std::string pojavexec_path = java_lib_dir + "/../libpojavexec.so";
    std::string verify_path = java_lib_dir + "/libverify.so";
    std::string java_path   = java_lib_dir + "/libjava.so";
    std::string zip_path    = java_lib_dir + "/libzip.so"; // jar 파일 읽기에 필수
    std::string net_path    = java_lib_dir + "/libnet.so";
    std::string nio_path    = java_lib_dir + "/libnio.so";

    dlopen(pojavexec_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    // 반드시 이 순서대로 열어야 서로를 참조하며 성공적으로 메모리에 올라갑니다.
    dlopen(verify_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    dlopen(java_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    dlopen(zip_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    dlopen(net_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    dlopen(nio_path.c_str(), RTLD_NOW | RTLD_GLOBAL);

    LOGI("자바 핵심 의존성 라이브러리 선행 로드 완료!");
    // =========================================================================

    // ───────── 여기 추가 ─────────
    unsetenv("POJAV_RENDERER");
    LOGI("POJAV_RENDERER env 제거됨 (Sodium 시그니처 회피)");
    // ─────────────────────────────

    // 2. OpenJDK의 JNI_CreateJavaVM 함수 포인터 획득
    CreateJavaVM_t createJavaVM = (CreateJavaVM_t)dlsym(handle, "JNI_CreateJavaVM");
    if (!createJavaVM) {
        LOGE("JNI_CreateJavaVM 심볼을 찾을 수 없습니다.");
        dlclose(handle);
        return -2;
    }

    // 3. JVM Arguments 조립
    jsize jvm_arg_count = env->GetArrayLength(jvm_args);
    std::vector<JavaVMOption> options(jvm_arg_count);

    for (int i = 0; i < jvm_arg_count; ++i) {
        jstring arg = (jstring)env->GetObjectArrayElement(jvm_args, i);
        const char* raw_arg = env->GetStringUTFChars(arg, nullptr);

        // strdup을 사용해 문자열 복제
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


    for (int i = 0; i < jvm_arg_count; i++) {
        const char* opt = options[i].optionString;
        if (strncmp(opt, "-Duser.dir=", 11) == 0) {
            const char* dir = opt + 11;
            LOGI("chdir to: %s", dir);
            chdir(dir);
            break;
        }
    }

    // 4. 새로운 내장 자바 가상머신 부팅!
    JavaVM* customVM = nullptr;
    JNIEnv* customEnv = nullptr; // 🎯 이 녀석이 진짜 마인크래프트를 돌릴 엔진입니다!

    LOGI("JNI_CreateJavaVM 호출 중...");
    jint res = createJavaVM(&customVM, (void**)&customEnv, &vm_args);

    if (res != JNI_OK) {
        LOGE("JVM 생성 실패: 코드 %d", res);
        return -3;
    }
    LOGI("내장 JVM 부팅 성공!");
    installGrabbingHook();  // ← 추가

    // ==========================================================
    // 🔥 [핵심 수정 구간] 이제부터는 무조건 customEnv만 사용해야 합니다!
    // ==========================================================

    // 5. 마인크래프트 메인 클래스 로드
// 시스템 프로퍼티에서 mainClass 읽기
    jclass systemClass = customEnv->FindClass("java/lang/System");
    jmethodID getPropMethod = customEnv->GetStaticMethodID(systemClass, "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring propKey = customEnv->NewStringUTF("ping.main.class");
    jstring propDefault = customEnv->NewStringUTF("net.minecraft.client.main.Main");
    jstring propValue = (jstring)customEnv->CallStaticObjectMethod(systemClass, getPropMethod, propKey, propDefault);
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

    jmethodID mainMethod = customEnv->GetStaticMethodID(mainClass, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) {
        LOGE("main 메서드를 찾을 수 없습니다.");
        return -5;
    }

    // 6. 마인크래프트 실행 인자(mcArgs) 전달용 자바 배열 생성
    // (길이는 안드로이드 env에서 가져오고, 생성은 customEnv에서 합니다)
    jsize mc_arg_count = env->GetArrayLength(mc_args);
    jclass stringClass = customEnv->FindClass("java/lang/String");
    jobjectArray mc_args_for_jvm = customEnv->NewObjectArray(mc_arg_count, stringClass, nullptr);

    for (int i = 0; i < mc_arg_count; ++i) {
        jstring arg = (jstring)env->GetObjectArrayElement(mc_args, i);
        const char* raw_arg = env->GetStringUTFChars(arg, nullptr);

        // customEnv에 넣을 새 문자열 생성
        jstring jvm_str = customEnv->NewStringUTF(raw_arg);
        customEnv->SetObjectArrayElement(mc_args_for_jvm, i, jvm_str);

        env->ReleaseStringUTFChars(arg, raw_arg);
    }

    // 🚀 드디어 마인크래프트 최초 구동
    LOGI("%s.main() 실행!", mainClassNameStr.c_str());
    customEnv->CallStaticVoidMethod(mainClass, mainMethod, mc_args_for_jvm);

    // =========================================================================
    // 🔥 [핵심 추가] 마인크래프트 메인 메서드 실행 중 발생한 자바 예외 감지 및 출력
    // =========================================================================
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