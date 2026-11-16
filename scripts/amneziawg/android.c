// SPDX-License-Identifier: MIT
#include <jni.h>
#include <unistd.h>
#include "_cgo_export.h"

JNIEXPORT jint JNICALL
Java_com_example_vpn_engine_AmneziaNativeEngine_startNative(JNIEnv *env, jobject receiver, jint fd, jstring config) {
    const char *text = (*env)->GetStringUTFChars(env, config, NULL);
    if (!text) return -5;
    int tun = dup(fd);
    int result = tun < 0 ? -5 : maximusAwgStart(tun, (char *)text);
    (*env)->ReleaseStringUTFChars(env, config, text);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_example_vpn_engine_AmneziaNativeEngine_socketNative(JNIEnv *env, jobject receiver, jboolean ipv6) {
    return maximusAwgSocket(ipv6 ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_example_vpn_engine_AmneziaNativeEngine_stopNative(JNIEnv *env, jobject receiver) {
    maximusAwgStop();
}
