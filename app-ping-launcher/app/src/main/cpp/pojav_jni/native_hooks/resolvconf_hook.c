//
// /etc/resolv.conf 접근을 사용자 정의 경로로 리다이렉트.
// JDK 의 sun.net.dns.ResolverConfigurationImpl 가 부팅 후 첫 DNS 조회 시점에
// 이 파일을 hardcode 로 읽는다. Android 에는 이 파일이 없거나 비어있어서
// JNDI DNS (SRV 레코드 조회) 가 실패한다. open()/openat()/fopen() 을
// bytehook 으로 후킹해서 우리가 만든 resolv.conf 로 돌린다.
//

#include "native_hooks.h"
#include <jni.h>
#include <bytehook.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <stdbool.h>

#define TAG __FILE_NAME__
#include <log.h>

static char g_resolv_redirect[512] = {0};
static bool g_hooks_installed = false;
static const char* RESOLV_TARGET = "/etc/resolv.conf";

static inline const char* maybe_redirect(const char* path) {
    if (path && g_resolv_redirect[0] && strcmp(path, RESOLV_TARGET) == 0) {
        return g_resolv_redirect;
    }
    return path;
}

typedef int (*open_func)(const char*, int, mode_t);
typedef int (*openat_func)(int, const char*, int, mode_t);
typedef FILE* (*fopen_func)(const char*, const char*);

static int custom_open(const char* pathname, int flags, mode_t mode) {
    const char* real = maybe_redirect(pathname);
    if (real != pathname) LOGI("open(%s) → %s", pathname, real);
    int result = BYTEHOOK_CALL_PREV(custom_open, open_func, real, flags, mode);
    BYTEHOOK_POP_STACK();
    return result;
}

static int custom_open64(const char* pathname, int flags, mode_t mode) {
    const char* real = maybe_redirect(pathname);
    if (real != pathname) LOGI("open64(%s) → %s", pathname, real);
    int result = BYTEHOOK_CALL_PREV(custom_open64, open_func, real, flags, mode);
    BYTEHOOK_POP_STACK();
    return result;
}

static int custom_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    const char* real = maybe_redirect(pathname);
    if (real != pathname) LOGI("openat(%s) → %s", pathname, real);
    int result = BYTEHOOK_CALL_PREV(custom_openat, openat_func, dirfd, real, flags, mode);
    BYTEHOOK_POP_STACK();
    return result;
}

static FILE* custom_fopen(const char* pathname, const char* mode) {
    const char* real = maybe_redirect(pathname);
    if (real != pathname) LOGI("fopen(%s) → %s", pathname, real);
    FILE* result = BYTEHOOK_CALL_PREV(custom_fopen, fopen_func, real, mode);
    BYTEHOOK_POP_STACK();
    return result;
}

/**
 * Kotlin/Java 에서 호출. resolv.conf 가 있을 경로를 설정하고,
 * 아직 설치 안 됐으면 open* fopen 후킹을 설치한다.
*/
JNIEXPORT void JNICALL
Java_kr_co_donghyun_pinglauncher_presentation_util_dns_DnsHookNative_installResolvConfRedirect(
        JNIEnv* env, jclass clazz, jstring jpath) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    strncpy(g_resolv_redirect, path, sizeof(g_resolv_redirect) - 1);
    g_resolv_redirect[sizeof(g_resolv_redirect) - 1] = 0;
    LOGI("resolv.conf 리다이렉트 대상 설정: %s", g_resolv_redirect);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (g_hooks_installed) {
        LOGI("후킹 이미 설치됨 — 경로만 갱신");
        return;
    }

    // bytehook init — 이미 다른 곳에서 init 됐어도 그 결과는 무시하고 진행.
    // hook_all 자체는 init 안 돼있으면 그냥 NULL 반환할 뿐이라 안전.
    bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);

    bytehook_stub_t s1 = bytehook_hook_all(NULL, "open",    &custom_open,    NULL, NULL);
    bytehook_stub_t s2 = bytehook_hook_all(NULL, "open64",  &custom_open64,  NULL, NULL);
    bytehook_stub_t s3 = bytehook_hook_all(NULL, "openat",  &custom_openat,  NULL, NULL);
    bytehook_stub_t s4 = bytehook_hook_all(NULL, "fopen",   &custom_fopen,   NULL, NULL);
    bytehook_stub_t s5 = bytehook_hook_all(NULL, "fopen64", &custom_fopen,   NULL, NULL);

    LOGI("resolv.conf hooks 설치: open=%p open64=%p openat=%p fopen=%p fopen64=%p",
         s1, s2, s3, s4, s5);
    g_hooks_installed = true;
}