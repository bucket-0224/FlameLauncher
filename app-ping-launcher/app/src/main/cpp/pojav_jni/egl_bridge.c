#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/osmesa_loader.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"
#include <vulkan/vulkan.h>

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))


struct PotatoBridge {

    /* EGLContext */ void* eglContext;
    /* EGLDisplay */ void* eglDisplay;
    /* EGLSurface */ void* eglSurface;
/*
    void* eglSurfaceRead;
    void* eglSurfaceDraw;
*/
};
EGLConfig config;
struct PotatoBridge potatoBridge;

#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"
#include "log.h"

#define RENDERER_GL4ES 1
#define RENDERER_VK_ZINK 2
#define RENDERER_VULKAN 4

EXTERNAL_API void pojavTerminate() {
    printf("EGLBridge: Terminating\n");

    switch (pojav_environ->config_renderer) {
        case RENDERER_GL4ES: {
            eglMakeCurrent_p(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            eglDestroyContext_p(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

            //case RENDERER_VIRGL:
        case RENDERER_VK_ZINK: {
            // Nothing to do here
        } break;
    }
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(
        JNIEnv* env, jclass clazz, jobject surface)
{
    LOGI("setupBridgeWindow: enter, old pojavWindow=%p", pojav_environ->pojavWindow);

    if (pojav_environ->pojavWindow != NULL) {
        ANativeWindow_release(pojav_environ->pojavWindow);
        pojav_environ->pojavWindow = NULL;
    }

    pojav_environ->pojavWindow = ANativeWindow_fromSurface(env, surface);
    if (pojav_environ->pojavWindow == NULL) {
        LOGE("setupBridgeWindow: ANativeWindow_fromSurface returned NULL");
        return;
    }
    int32_t w = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    int32_t h = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    LOGI("setupBridgeWindow: new pojavWindow=%p size=%dx%d, mainWindowBundle=%p",
         pojav_environ->pojavWindow, w, h, pojav_environ->mainWindowBundle);

    if(br_setup_window != NULL) br_setup_window();

    LOGI("setupBridgeWindow: after br_setup_window, state=%d newNativeSurface=%p",
         pojav_environ->mainWindowBundle ? pojav_environ->mainWindowBundle->state : -1,
         pojav_environ->mainWindowBundle ? pojav_environ->mainWindowBundle->newNativeSurface : NULL);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass clazz) {
    ANativeWindow_release(pojav_environ->pojavWindow);
}

#define ADRENO_POSSIBLE
#ifdef ADRENO_POSSIBLE
void* load_turnip_vulkan() {
    if(getenv("POJAV_LOAD_TURNIP") == NULL) return NULL;
    const char* native_dir = getenv("POJAV_NATIVEDIR");
    const char* cache_dir = getenv("TMPDIR");
    if(!linker_ns_load(native_dir)) return NULL;
    void* linkerhook = linker_ns_dlopen("liblinkerhook.so", RTLD_LOCAL | RTLD_NOW);
    if(linkerhook == NULL) return NULL;
    void* turnip_driver_handle = linker_ns_dlopen("libvulkan_freedreno.so", RTLD_LOCAL | RTLD_NOW);
    if(turnip_driver_handle == NULL) {
        printf("AdrenoSupp: Failed to load Turnip!\n%s\n", dlerror());
        dlclose(linkerhook);
        return NULL;
    }
    void* dl_android = linker_ns_dlopen("libdl_android.so", RTLD_LOCAL | RTLD_LAZY);
    if(dl_android == NULL) {
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }
    void* android_get_exported_namespace = dlsym(dl_android, "android_get_exported_namespace");
    void (*linkerhook_pass_handles)(void*, void*, void*) = dlsym(linkerhook, "app__pojav_linkerhook_pass_handles");
    if(linkerhook_pass_handles == NULL || android_get_exported_namespace == NULL) {
        dlclose(dl_android);
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }
    linkerhook_pass_handles(turnip_driver_handle, android_dlopen_ext, android_get_exported_namespace);
    void* libvulkan = linker_ns_dlopen_unique(cache_dir, "libvulkan.so", RTLD_LOCAL | RTLD_NOW);
    return libvulkan;
}
#endif

static void set_vulkan_ptr(void* ptr) {
    char envval[64];
    sprintf(envval, "%"PRIxPTR, (uintptr_t)ptr);
    setenv("VULKAN_PTR", envval, 1);
}

void load_vulkan() {
    if(android_get_device_api_level() >= 28) { // the loader does not support below that
#ifdef ADRENO_POSSIBLE
        void* result = load_turnip_vulkan();
        if(result != NULL) {
            printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
            set_vulkan_ptr(result);
            return;
        }
#endif
    }
    printf("OSMDroid: loading vulkan regularly...\n");
    void* vulkan_ptr = dlopen("libvulkan.so", RTLD_LAZY | RTLD_LOCAL);
    printf("OSMDroid: loaded vulkan, ptr=%p\n", vulkan_ptr);
    set_vulkan_ptr(vulkan_ptr);
}



static bool probe_vulkan_works() {
    void* h = dlopen("libvulkan.so", RTLD_NOW);
    if (!h) { printf("Zink probe: libvulkan.so 못 찾음\n"); return false; }

    typedef PFN_vkVoidFunction (*PFN_vkGetInstanceProcAddr_t)(VkInstance, const char*);
    typedef VkResult (*PFN_vkCreateInstance_t)(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);

    PFN_vkGetInstanceProcAddr_t gipa = (PFN_vkGetInstanceProcAddr_t)dlsym(h, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance_t createInst = (PFN_vkCreateInstance_t)dlsym(h, "vkCreateInstance");
    if (!gipa || !createInst) { dlclose(h); return false; }

    VkApplicationInfo app = {0};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci = {0};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;

    VkInstance inst = VK_NULL_HANDLE;
    if (createInst(&ci, NULL, &inst) != VK_SUCCESS) {
        printf("Zink probe: vkCreateInstance 실패\n");
        dlclose(h); return false;
    }

    PFN_vkEnumeratePhysicalDevices enumPD =
            (PFN_vkEnumeratePhysicalDevices)gipa(inst, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceFeatures getFeats =
            (PFN_vkGetPhysicalDeviceFeatures)gipa(inst, "vkGetPhysicalDeviceFeatures");
    PFN_vkGetPhysicalDeviceProperties getProps =
            (PFN_vkGetPhysicalDeviceProperties)gipa(inst, "vkGetPhysicalDeviceProperties");
    PFN_vkDestroyInstance destInst =
            (PFN_vkDestroyInstance)gipa(inst, "vkDestroyInstance");

    uint32_t deviceCount = 0;
    enumPD(inst, &deviceCount, NULL);
    if (deviceCount == 0) {
        printf("Zink probe: no Vulkan physical devices\n");
        if (destInst) destInst(inst, NULL);
        dlclose(h); return false;
    }

    if (deviceCount > 8) deviceCount = 8;
    VkPhysicalDevice phys[8];
    enumPD(inst, &deviceCount, phys);

    bool any_compatible = false;
    for (uint32_t i = 0; i < deviceCount; i++) {
        VkPhysicalDeviceProperties props = {0};
        VkPhysicalDeviceFeatures   feats = {0};
        getProps(phys[i], &props);
        getFeats(phys[i], &feats);
        bool ok = true;
        printf("Zink probe: device #%u (%s) logicOp=%d fillModeNonSolid=%d shaderClipDistance=%d -> %s\n",
               i, props.deviceName,
               feats.logicOp, feats.fillModeNonSolid, feats.shaderClipDistance,
               ok ? "COMPATIBLE" : "INCOMPATIBLE");
        if (ok) { any_compatible = true; }
    }

    if (destInst) destInst(inst, NULL);
    dlclose(h);

    if (!any_compatible) {
        printf("Zink probe: no Vulkan device meets Zink base requirements\n");
    }
    return any_compatible;
}


int pojavInitOpenGL() {
    // Only affects GL4ES as of now
    const char *forceVsync = getenv("FORCE_VSYNC");
    if (forceVsync && strcmp(forceVsync, "true") == 0)
        pojav_environ->force_vsync = true;

    const char *renderer = getenv("POJAV_RENDERER");
    if (!renderer) {
        printf("POJAV_RENDERER not set! defaulting to vulkan_zink\n");
        renderer = "vulkan_zink";
    }
    printf("OpenGL: renderer = %s\n", renderer);

    // ── 렌더러별 bridge table 설정 ─────────────────────────────
    if (strncmp("opengles", renderer, 8) == 0) {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
        printf("OpenGL: set_gl_bridge_tbl() done (GL4ES path)\n");
    } else if (strcmp(renderer, "vulkan_zink") == 0) {
        if (!probe_vulkan_works()) {
            printf("OpenGL: Vulkan/Zink unavailable on this device — falling back to GL4ES\n");
            // ★ 환경변수도 일관되게 — 후속 코드가 POJAV_RENDERER를 다시 읽는 경우 대비
            printf("OpenGL: Vulkan/Zink unavailable on this device — falling back to GL4ES\n");
            setenv("POJAV_RENDERER", "opengles2", 1);
            setenv("LIBGL_NAME",     "libgl4es_114.so", 1);
            setenv("DLOPEN",         "libgl4es_114.so", 1);
            setenv("LIBGL_ES",       "2",         1);
            unsetenv("GALLIUM_DRIVER");
            unsetenv("MESA_LOADER_DRIVER_OVERRIDE");
            pojav_environ->config_renderer = RENDERER_GL4ES;
            set_gl_bridge_tbl();

            printf("OpenGL: switched to GL4ES bridge table\n");
        } else {
            pojav_environ->config_renderer = RENDERER_VK_ZINK;
            load_vulkan();
            setenv("GALLIUM_DRIVER", "zink", 1);
            // zink가 OpenGL 4.6 보고하게 강제
            if (!getenv("MESA_GL_VERSION_OVERRIDE"))     setenv("MESA_GL_VERSION_OVERRIDE", "4.6", 1);
            if (!getenv("MESA_GLSL_VERSION_OVERRIDE"))   setenv("MESA_GLSL_VERSION_OVERRIDE", "460", 1);
            if (!getenv("force_glsl_extensions_warn"))   setenv("force_glsl_extensions_warn", "true", 1);
            if (!getenv("allow_higher_compat_version"))  setenv("allow_higher_compat_version", "true", 1);
            if (!getenv("allow_glsl_extension_directive_midshader"))
                setenv("allow_glsl_extension_directive_midshader", "true", 1);
            if (!getenv("MESA_LOADER_DRIVER_OVERRIDE"))  setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
            set_osm_bridge_tbl();
            printf("OpenGL: set_osm_bridge_tbl() done (Zink path)\n");

            set_osm_bridge_tbl();
        }
    }  else {
        printf("OpenGL: unknown renderer '%s', defaulting to vulkan_zink\n", renderer);
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("GALLIUM_DRIVER", "zink", 1);
        set_osm_bridge_tbl();
        printf("OpenGL: set_osm_bridge_tbl() done (fallback path)\n");
    }

    // ── bridge table 채워졌는지 검증 ───────────────────────────
    if (br_init == NULL || br_init_context == NULL ||
        br_make_current == NULL || br_swap_buffers == NULL) {
        printf("OpenGL: FATAL — bridge_tbl not populated! "
               "br_init=%p br_init_context=%p br_make_current=%p br_swap_buffers=%p\n",
               (void*)br_init, (void*)br_init_context,
               (void*)br_make_current, (void*)br_swap_buffers);
        return -1;
    }
    printf("OpenGL: bridge_tbl OK — br_init=%p br_init_context=%p br_make_current=%p\n",
           (void*)br_init, (void*)br_init_context, (void*)br_make_current);

    // ── 실제 렌더 백엔드 초기화 (EGL or OSMesa) ───────────────
    printf("OpenGL: calling br_init() ...\n");
    if (!br_init()) {
        printf("OpenGL: br_init() FAILED — EGL/OSMesa 초기화 실패\n");
        return -1;
    }
    printf("OpenGL: br_init() succeeded\n");

    return 0;
}

extern void updateMonitorSize(int width, int height);

EXTERNAL_API int pojavInit() {
    pojav_environ->glfwThreadVmEnv = get_attached_env(pojav_environ->runtimeJavaVMPtr);
    if(pojav_environ->glfwThreadVmEnv == NULL) {
        printf("Failed to attach Java-side JNIEnv to GLFW thread\n");
        return 0;
    }
    ANativeWindow_acquire(pojav_environ->pojavWindow);
    pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    ANativeWindow_setBuffersGeometry(
            pojav_environ->pojavWindow,
            pojav_environ->savedWidth, pojav_environ->savedHeight,
            AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);

    if (pojavInitOpenGL() != 0) {
        printf("pojavInit: pojavInitOpenGL() failed\n");
        return 0;   // LWJGL에 실패 알림
    }
    return 1;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) return;
    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            // pojavInitVulkan();
            break;
        case GLFW_OPENGL_API:
            /* Nothing to do: initialization is called in pojavCreateContext */
            // pojavInitOpenGL();
            break;
        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

extern void pojavBootDispatchFramebufferSize(void);

EXTERNAL_API void pojavSwapBuffers() {
    static int counter = 0;
    if ((++counter % 60) == 0) {
        printf("pojavSwapBuffers: tid=%d counter=%d\n", gettid(), counter);
    }

    pojavBootDispatchFramebufferSize();   // ★ 추가: 첫 swap 직전 한 번 발화

    br_swap_buffers();
}

EXTERNAL_API void* pojavGetCurrentContext() {
    void* current = br_get_current();

    // ★ OSMesa 경로일 때: 이 스레드에 context가 안 묶여 있으면 강제 rebind
    if (current != NULL && pojav_environ->config_renderer == RENDERER_VK_ZINK) {
        OSMesaContext osmCur = OSMesaGetCurrentContext_p ? OSMesaGetCurrentContext_p() : NULL;
        if (osmCur == NULL) {
            printf("pojavGetCurrentContext: rebinding OSMesa to current thread (tid=%d)\n",
                   gettid());
            br_make_current((basic_render_window_t*)current);
        }
    }
    return current;
}

EXTERNAL_API void pojavMakeCurrent(void* window) {
    printf("pojavMakeCurrent: tid=%d window=%p\n", gettid(), window);
    if (br_make_current == NULL) {
        printf("pojavMakeCurrent: br_make_current is NULL!\n");
        return;
    }
    br_make_current((basic_render_window_t*)window);
}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    printf("pojavCreateContext: tid=%d contextSrc=%p\n", gettid(), contextSrc);

    if (pojav_environ->config_renderer == RENDERER_VULKAN) {
        return (void *) pojav_environ->pojavWindow;
    }
    if (br_init_context == NULL) {
        printf("pojavCreateContext: br_init_context is NULL!\n");
        return NULL;
    }
    void* result = br_init_context((basic_render_window_t*)contextSrc);
    if (result == NULL) {
        // ★ 추가: zink/osmesa가 context 생성에 실패한 케이스
        printf("pojavCreateContext: br_init_context returned NULL — "
               "renderer=%d, likely Vulkan device incompatible with zink "
               "(no suitable physical device / missing extensions)\n",
               pojav_environ->config_renderer);
    }
    printf("pojavCreateContext returned %p\n", result);
    return result;
}

void* maybe_load_vulkan() {
    // We use the env var because
    // 1. it's easier to do that
    // 2. it won't break if something will try to load vulkan and osmesa simultaneously
    if(getenv("VULKAN_PTR") == NULL) load_vulkan();
    return (void*) strtoul(getenv("VULKAN_PTR"), NULL, 0x10);
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    br_swap_interval(interval);
}

