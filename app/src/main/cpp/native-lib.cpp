#include <jni.h>

namespace {

constexpr char kEngineVersion[] = "GG Equalizer DSP Core 1.0.0 (C++17)";

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeVersion(
    JNIEnv* env,
    jobject /* receiver */) {
    return env->NewStringUTF(kEngineVersion);
}
