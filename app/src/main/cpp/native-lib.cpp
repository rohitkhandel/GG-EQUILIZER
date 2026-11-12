#include <jni.h>

#include "DspEngine.h"

#include <atomic>
#include <cstddef>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>

namespace {

using ggequalizer::BiquadType;
using ggequalizer::DspEngine;
using ggequalizer::EqualizerBand;
using ggequalizer::LimiterParameters;

std::mutex gRegistryMutex;
std::unordered_map<jlong, std::shared_ptr<DspEngine>> gEngines;
std::atomic<jlong> gNextHandle{1};

std::shared_ptr<DspEngine> findEngine(jlong handle) {
    if (handle <= 0) {
        return {};
    }

    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const auto iterator = gEngines.find(handle);
    return iterator == gEngines.end() ? std::shared_ptr<DspEngine>{}
                                       : iterator->second;
}

bool decodeFilterType(jint value, BiquadType* output) noexcept {
    if (output == nullptr) {
        return false;
    }

    switch (value) {
        case 0:
            *output = BiquadType::Peaking;
            return true;
        case 1:
            *output = BiquadType::LowShelf;
            return true;
        case 2:
            *output = BiquadType::HighShelf;
            return true;
        default:
            return false;
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeVersion(
    JNIEnv* env,
    jobject) {
    return env->NewStringUTF("GG Equalizer Native DSP 2.0.0 (C++17)");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeCreate(
    JNIEnv*,
    jobject,
    jint sampleRateHz) {
    try {
        auto engine = std::make_shared<DspEngine>();
        if (!engine->setSampleRate(static_cast<float>(sampleRateHz))) {
            return 0;
        }

        const jlong handle =
            gNextHandle.fetch_add(1, std::memory_order_relaxed);
        if (handle <= 0) {
            return 0;
        }

        std::lock_guard<std::mutex> lock(gRegistryMutex);
        gEngines.emplace(handle, std::move(engine));
        return handle;
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle) {
    try {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        gEngines.erase(handle);
    } catch (...) {
        // No C++ exception may cross the JNI boundary.
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeSetSampleRate(
    JNIEnv*,
    jobject,
    jlong handle,
    jint sampleRateHz) {
    try {
        const auto engine = findEngine(handle);
        return engine && engine->setSampleRate(static_cast<float>(sampleRateHz))
            ? JNI_TRUE
            : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeSetBand(
    JNIEnv*,
    jobject,
    jlong handle,
    jint index,
    jint type,
    jfloat frequencyHz,
    jfloat gainDb,
    jfloat qFactor,
    jboolean enabled) {
    try {
        if (index < 0) {
            return JNI_FALSE;
        }

        BiquadType filterType = BiquadType::Peaking;
        if (!decodeFilterType(type, &filterType)) {
            return JNI_FALSE;
        }

        const auto engine = findEngine(handle);
        if (!engine) {
            return JNI_FALSE;
        }

        const EqualizerBand band{
            filterType,
            frequencyHz,
            gainDb,
            qFactor,
            enabled == JNI_TRUE
        };

        return engine->setBand(static_cast<std::size_t>(index), band)
            ? JNI_TRUE
            : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeSetBandEnabled(
    JNIEnv*,
    jobject,
    jlong handle,
    jint index,
    jboolean enabled) {
    try {
        if (index < 0) {
            return JNI_FALSE;
        }
        const auto engine = findEngine(handle);
        return engine && engine->setBandEnabled(
            static_cast<std::size_t>(index),
            enabled == JNI_TRUE)
            ? JNI_TRUE
            : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeSetTone(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat bassGainDb,
    jfloat trebleGainDb) {
    try {
        const auto engine = findEngine(handle);
        return engine && engine->setTone(bassGainDb, trebleGainDb)
            ? JNI_TRUE
            : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeSetPreampGain(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat gainDb) {
    try {
        const auto engine = findEngine(handle);
        return engine && engine->setPreampGain(gainDb)
            ? JNI_TRUE
            : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeSetLimiter(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled,
    jfloat thresholdDb,
    jfloat kneeDb,
    jfloat lookAheadMs,
    jfloat releaseMs) {
    try {
        const auto engine = findEngine(handle);
        if (!engine) {
            return JNI_FALSE;
        }

        LimiterParameters parameters;
        parameters.enabled = enabled == JNI_TRUE;
        parameters.thresholdDb = thresholdDb;
        parameters.kneeDb = kneeDb;
        parameters.lookAheadMs = lookAheadMs;
        parameters.releaseMs = releaseMs;

        return engine->setLimiterParameters(parameters)
            ? JNI_TRUE
            : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeSetDvcVolume(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat linearGain) {
    try {
        const auto engine = findEngine(handle);
        return engine && engine->setDvcVolume(linearGain)
            ? JNI_TRUE
            : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray interleavedStereo,
    jint frameCount) {
    if (interleavedStereo == nullptr || frameCount < 0) {
        return JNI_FALSE;
    }

    try {
        const auto engine = findEngine(handle);
        if (!engine) {
            return JNI_FALSE;
        }

        const jsize arrayLength = env->GetArrayLength(interleavedStereo);
        if (frameCount > arrayLength / 2) {
            return JNI_FALSE;
        }
        if (frameCount == 0) {
            return JNI_TRUE;
        }

        jfloat* samples =
            env->GetFloatArrayElements(interleavedStereo, nullptr);
        if (samples == nullptr) {
            return JNI_FALSE;
        }

        bool processed = false;
        try {
            processed = engine->process(
                samples,
                static_cast<std::size_t>(frameCount));
        } catch (...) {
            env->ReleaseFloatArrayElements(
                interleavedStereo, samples, JNI_ABORT);
            return JNI_FALSE;
        }

        env->ReleaseFloatArrayElements(interleavedStereo, samples, 0);
        return processed ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeReset(
    JNIEnv*,
    jobject,
    jlong handle) {
    try {
        const auto engine = findEngine(handle);
        if (engine) {
            engine->reset();
        }
    } catch (...) {
        // No C++ exception may cross the JNI boundary.
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rohitkhandel_ggequalizer_audio_NativeDspBridge_nativeGetLatencyFrames(
    JNIEnv*,
    jobject,
    jlong handle) {
    try {
        const auto engine = findEngine(handle);
        return engine ? static_cast<jint>(engine->latencyFrames()) : 0;
    } catch (...) {
        return 0;
    }
}
