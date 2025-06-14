#ifndef _UTIL_HPP_
#define _UTIL_HPP_

#include <jni.h>

extern "C" {
#include <stdlib.h>
}

#include <android/log.h>

#define JNI_FUNC(retType, bindClass, name)  JNIEXPORT retType JNICALL Java_com_vivlio_android_pdfium_##bindClass##_##name
#define JNI_ARGS    JNIEnv *env, jobject thiz

#define LOG_TAG "jniPdfium"
#define LOGI(...)   __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...)   __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...)   __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace utils {
    template<typename T>
    T clamp(T value, T minVal, T maxVal) {
        return (value < minVal) ? minVal : (value > maxVal) ? maxVal : value;
    }

    typedef struct {
        float left;
        float top;
        float right;
        float bottom;
    } RectF;
}


#endif