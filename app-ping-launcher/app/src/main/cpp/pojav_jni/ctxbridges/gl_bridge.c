#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

#define TAG __FILE_NAME__
#include <log.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/types.h>

//
// Created by maks on 17.09.2022.
//

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;

// 파일 상단 어디든 추가
static bool ltw_initialized = false;
static gl_render_window_t* g_lastInitializedBundle = NULL;

//static void try_init_ltw_once(void) {
//    if (ltw_initialized) {
//        if (g_lastInitializedBundle != NULL &&
//            eglGetCurrentContext_p() == EGL_NO_CONTEXT) {
//            long tid = syscall(SYS_gettid);
//            LOGI("LTW: cross-thread re-bind EGL (tid=%ld)", tid);
//            EGLBoolean r = eglMakeCurrent_p(g_EglDisplay,
//                                            g_lastInitializedBundle->surface,
//                                            g_lastInitializedBundle->surface,
//                                            g_lastInitializedBundle->context);
//            LOGI("LTW: re-bind result=%d", r);
//            currentBundle = g_lastInitializedBundle;
//        }
//        return;
//    }
//
//    // ★ RTLD_NOLOAD 가 SONAME 매칭에 실패할 수 있어서 명시적 dlopen 사용.
//    //   이미 로드된 .so 면 refcount 만 증가, 새로 안 로드함.
//    void* handle = dlopen("libltw.so", RTLD_NOW);
//    if (!handle) {
//        // LWJGL 이 아직 dlopen 안 한 상태. 조용히 다음 기회.
//        return;
//    }
//
//    void (*init_egl_p)(void)              = (void(*)(void)) dlsym(handle, "init_egl");
//    void (*init_extra_extensions_p)(void) = (void(*)(void)) dlsym(handle, "init_extra_extensions");
//    void (*init_noerror_p)(void)          = (void(*)(void)) dlsym(handle, "init_noerror");
//
//    if (!init_egl_p) {
//        LOGW("LTW: libltw.so handle 잡혔지만 init_egl 심볼 없음 — strip 됐거나 다른 빌드");
//        // dlclose 안 함 — 다른 곳에서 쓰는 중일 수 있음
//        ltw_initialized = true;  // 무한 재시도 방지
//        return;
//    }
//
//    // ★ EGL context 가 active 인지 확인 — init_egl 은 보통 현재 ctx 의 GLES 함수를
//    //   dlsym 으로 가져가므로 active 필수.
//    EGLContext cur_ctx = eglGetCurrentContext_p();
//    if (cur_ctx == EGL_NO_CONTEXT) {
//        LOGI("LTW: init_egl 발견, 그러나 EGL ctx 아직 inactive — 다음 makeCurrent 까지 대기");
//        return;   // ltw_initialized 는 여전히 false → 다음 기회에 재시도
//    }
//
//    LOGI("LTW: calling init_egl()  (ctx=%p)", cur_ctx);
//    init_egl_p();
//
//    if (init_extra_extensions_p && getenv("LTW_ENABLE_EXTRA_EXTENSIONS")) {
//        LOGI("LTW: calling init_extra_extensions()");
//        init_extra_extensions_p();
//    } else if (init_extra_extensions_p) {
//        LOGI("LTW: init_extra_extensions() SKIPPED (NULL-deref workaround)");
//    }
//
//    if (init_noerror_p && getenv("LIBGL_NOERROR")) {
//        LOGI("LTW: calling init_noerror()");
//        init_noerror_p();
//    }
//    ltw_initialized = true;
//}

static void try_init_ltw_once(void) {
    if (ltw_initialized) return;

    // ★ 매뉴얼 init 비활성: LWJGL 이 libltw.so 를 dlopen 할 때
    //   LTW 가 자체 생성자(.init_array)로 초기화하도록 위임.
    //   init_egl / init_extra_extensions 를 명시적으로 부르면 NULL deref 가
    //   나거나 dispatch 테이블이 불완전 채워져 glGetString → NULL 이 됨.
    LOGI("LTW: manual init disabled — LWJGL dlopen path 가 자체 초기화 담당");
    ltw_initialized = true;
}

bool gl_init() {
    if(!dlsym_EGL()) return false;
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGE("%s", "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        LOGE("eglInitialize_p() failed: %04x", eglGetError_p());
        return false;
    }
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if(currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    // Fetch dimensions from the EGL surface - the most reliable way
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if(!result_width || !result_height) goto zero;
    return;

    zero:
    // No idea what to do, but feeding gl4es incorrect or non-initialized dimensions may be
    // a bad idea. Set to zero in case of errors.
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    memset(bundle, 0, sizeof(gl_render_window_t));
    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT|EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLint num_configs = 0;

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE) {
        LOGE("eglChooseConfig_p() failed: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    if (num_configs == 0) {
        LOGE("%s", "eglChooseConfig_p() found no matching config");
        free(bundle);
        return NULL;
    }

    // Get the first matching config
    eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        // gl_bridge.c — gl_init_context 안
        EGLBoolean bindResult;
        const char* renderer = getenv("POJAV_RENDERER");
        bool wantDesktopGL = (renderer && strncmp(renderer, "opengles3_desktopgl", 19) == 0);

        if (wantDesktopGL) {
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
            if (!bindResult) {
                LOGW("Desktop GL bind 실패(0x%x) — ES 바인딩으로 폴백", eglGetError_p());
                bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
                // ★ 폴백한 사실을 환경변수에도 기록해서 LIBGL_GL 같은 후속 설정이
                //   "데스크톱 GL인 척"하지 않도록 한다
                setenv("POJAV_RENDERER", "opengles2", 1);
            }
        } else {
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) {
            LOGE("eglBindAPI 완전 실패: 0x%x", eglGetError_p());
            free(bundle);
            return NULL;   // ← 지금은 그냥 진행하는데 여기서 끊어야 합니다
        }
    }

    const char* libgl_es_env = getenv("LIBGL_ES");
    int libgl_es = libgl_es_env ? strtol(libgl_es_env, NULL, 0) : 2;
    if (libgl_es < 0 || libgl_es > INT16_MAX) libgl_es = 2;
    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, egl_context_attributes);

    if (bundle->context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext_p() finished with error: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    return bundle;
}

void gl_swap_surface(gl_render_window_t* bundle) {
    LOGI("gl_swap_surface: enter, nativeSurface=%p newNativeSurface=%p surface=%p",
         bundle->nativeSurface, bundle->newNativeSurface, bundle->surface);

    if(bundle->nativeSurface != NULL) {
        ANativeWindow_release(bundle->nativeSurface);
    }
    if(bundle->surface != NULL) {
        EGLBoolean ds = eglDestroySurface_p(g_EglDisplay, bundle->surface);
        LOGI("gl_swap_surface: destroy old EGLSurface result=%d", ds);
    }

    if(bundle->newNativeSurface != NULL) {
        LOGI("Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        int32_t w = ANativeWindow_getWidth(bundle->nativeSurface);
        int32_t h = ANativeWindow_getHeight(bundle->nativeSurface);
        LOGI("gl_swap_surface: ANativeWindow size=%dx%d", w, h);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config,
                                                   bundle->nativeSurface, NULL);
        if (bundle->surface == EGL_NO_SURFACE) {
            LOGE("gl_swap_surface: eglCreateWindowSurface FAILED error=0x%04x",
                 eglGetError_p());
            bundle->surface = NULL;
        } else {
            EGLint sw=0, sh=0;
            eglQuerySurface_p(g_EglDisplay, bundle->surface, EGL_WIDTH, &sw);
            eglQuerySurface_p(g_EglDisplay, bundle->surface, EGL_HEIGHT, &sh);
            LOGI("gl_swap_surface: new EGLSurface=%p size=%dx%d", bundle->surface, sw, sh);
        }
    }else{
        LOGI("No new native surface, switching to 1x1 pbuffer");
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
    }
    //eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context);
}

void gl_make_current(gl_render_window_t* bundle) {
    if(bundle == NULL) {
        if (currentBundle != NULL) {
            LOGI("gl_bridge: ignoring makeCurrent(NULL) — preserving active context");
            return;
        }
        if(eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;

            EGLBoolean mc_result = eglMakeCurrent_p(g_EglDisplay, bundle->surface,
                                                    bundle->surface, bundle->context);
            EGLint mc_error = eglGetError_p();
            LOGI("eglMakeCurrent result=%d error=0x%04x", mc_result, mc_error);

            if(mc_result) {
                currentBundle = bundle;
                try_init_ltw_once();    // ★ 추가
            }
        }
        return;
    }

    try_init_ltw_once();

    // ── 이하 기존 코드 ──

    bool hasSetMainWindow = false;
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
    }

    // redundant 체크
    if (currentBundle == bundle && bundle->surface != NULL
        && bundle->newNativeSurface == NULL
        && bundle->state == STATE_RENDERER_ALIVE) {
        EGLContext cur_ctx = eglGetCurrentContext_p();
        EGLSurface cur_surf = eglGetCurrentSurface_p(EGL_DRAW);
        if (cur_ctx == bundle->context && cur_surf == bundle->surface) {
            return;
        }
        LOGI("gl_bridge: bundle stale — rebinding");
    }

    if(bundle->surface == NULL) {
        gl_swap_surface(bundle);
    }
    EGLBoolean mc_result = eglMakeCurrent_p(g_EglDisplay, bundle->surface,
                                            bundle->surface, bundle->context);
    EGLint mc_error = eglGetError_p();
    LOGI("eglMakeCurrent result=%d error=0x%04x", mc_result, mc_error);

    if(mc_result) {
        currentBundle = bundle;
        g_lastInitializedBundle = bundle;   // ★ 전역 백업
        try_init_ltw_once();
    }
}

void gl_swap_buffers() {
    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        LOGI("gl_swap_buffers: STATE_RENDERER_NEW_WINDOW detected, "
             "old surface=%p newNativeSurface=%p",
             currentBundle->surface, currentBundle->newNativeSurface);
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        gl_swap_surface(currentBundle);
        LOGI("gl_swap_buffers: after swap_surface, new EGLSurface=%p nativeSurface=%p",
             currentBundle->surface, currentBundle->nativeSurface);
        EGLBoolean mc = eglMakeCurrent_p(g_EglDisplay, currentBundle->surface,
                                         currentBundle->surface, currentBundle->context);
        LOGI("gl_swap_buffers: makeCurrent after swap result=%d error=0x%04x",
             mc, eglGetError_p());
        currentBundle->state = STATE_RENDERER_ALIVE;
    }
    if(currentBundle->surface != NULL) {
        if(!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface)
           && eglGetError_p() == EGL_BAD_SURFACE) {

            EGLint err = eglGetError_p();
            LOGE("gl_swap_buffers: eglSwapBuffers FAILED error=0x%04x surface=%p",
                 err, currentBundle->surface);

            // ★ 이미 새 surface가 예약되어 있으면 폴백하지 말고 그걸 사용
            if (currentBundle->newNativeSurface != NULL) {
                LOGI("Swap failed but new surface pending — switching to it");
                eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                gl_swap_surface(currentBundle);
                eglMakeCurrent_p(g_EglDisplay, currentBundle->surface,
                                 currentBundle->surface, currentBundle->context);
                currentBundle->state = STATE_RENDERER_ALIVE;
            } else {
                eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                gl_swap_surface(currentBundle);  // 1x1 pbuffer로
                LOGI("The window has died, awaiting window change");
            }
        }
    }
}

void gl_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        LOGI("Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void gl_swap_interval(int swapInterval) {
    if(pojav_environ->force_vsync) swapInterval = 1;

    eglSwapInterval_p(g_EglDisplay, swapInterval);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    LOGI("GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        LOGI("GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}
