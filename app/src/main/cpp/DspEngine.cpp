#include "DspEngine.h"

#include <algorithm>
#include <cmath>

namespace ggequalizer {
namespace {

constexpr float kMinimumSampleRateHz = 8000.0F;
constexpr float kMaximumSampleRateHz = 192000.0F;
constexpr float kDefaultBassFrequencyHz = 120.0F;
constexpr float kDefaultTrebleFrequencyHz = 8000.0F;
constexpr float kMaximumToneGainDb = 12.0F;

bool isFiniteBand(const EqualizerBand& band) noexcept {
    return std::isfinite(band.frequencyHz) &&
           std::isfinite(band.gainDb) &&
           std::isfinite(band.qFactor) &&
           band.frequencyHz > 0.0F &&
           band.qFactor > 0.0F;
}

bool isSupportedType(BiquadType type) noexcept {
    return type == BiquadType::Peaking ||
           type == BiquadType::LowShelf ||
           type == BiquadType::HighShelf;
}

}  // namespace

DspEngine::DspEngine() {
    for (std::size_t index = 0U; index < kMaximumBands; ++index) {
        const float position = static_cast<float>(index) /
            static_cast<float>(kMaximumBands - 1U);
        const float frequency = 20.0F * std::pow(1000.0F, position);

        bandSettings_[index] = EqualizerBand{
            BiquadType::Peaking, frequency, 0.0F, 1.0F, false
        };

        const BiquadParameters parameters{
            BiquadType::Peaking, frequency, 0.0F, 1.0F
        };
        leftBands_[index].configure(sampleRateHz_, parameters);
        rightBands_[index].configure(sampleRateHz_, parameters);
    }

    limiter_.prepare(sampleRateHz_);
    limiter_.setParameters(limiterParameters_);
    configureToneFilters();
}

bool DspEngine::setSampleRate(float sampleRateHz) {
    if (!std::isfinite(sampleRateHz) ||
        sampleRateHz < kMinimumSampleRateHz ||
        sampleRateHz > kMaximumSampleRateHz) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    sampleRateHz_ = sampleRateHz;

    for (std::size_t index = 0U; index < kMaximumBands; ++index) {
        const EqualizerBand& band = bandSettings_[index];
        const BiquadParameters parameters{
            band.type, band.frequencyHz, band.gainDb, band.qFactor
        };
        if (!leftBands_[index].configure(sampleRateHz_, parameters) ||
            !rightBands_[index].configure(sampleRateHz_, parameters)) {
            return false;
        }
    }

    return configureToneFilters() &&
           limiter_.prepare(sampleRateHz_) &&
           limiter_.setParameters(limiterParameters_);
}

bool DspEngine::setBand(std::size_t index, const EqualizerBand& band) {
    if (index >= kMaximumBands ||
        !isFiniteBand(band) ||
        !isSupportedType(band.type)) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    EqualizerBand normalized = band;
    normalized.frequencyHz = std::clamp(
        normalized.frequencyHz, 10.0F, sampleRateHz_ * 0.49F);
    normalized.gainDb = std::clamp(normalized.gainDb, -24.0F, 24.0F);
    normalized.qFactor = std::clamp(normalized.qFactor, 0.1F, 10.0F);

    const BiquadParameters parameters{
        normalized.type,
        normalized.frequencyHz,
        normalized.gainDb,
        normalized.qFactor
    };

    if (!leftBands_[index].configure(sampleRateHz_, parameters) ||
        !rightBands_[index].configure(sampleRateHz_, parameters)) {
        return false;
    }

    bandSettings_[index] = normalized;
    return true;
}

bool DspEngine::setBandEnabled(std::size_t index, bool enabled) {
    if (index >= kMaximumBands) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    bandSettings_[index].enabled = enabled;
    return true;
}

bool DspEngine::configureToneFilters() noexcept {
    const BiquadParameters bassParameters{
        BiquadType::LowShelf,
        kDefaultBassFrequencyHz,
        bassGainDb_,
        1.0F
    };

    const BiquadParameters trebleParameters{
        BiquadType::HighShelf,
        std::min(kDefaultTrebleFrequencyHz, sampleRateHz_ * 0.45F),
        trebleGainDb_,
        1.0F
    };

    return leftBass_.configure(sampleRateHz_, bassParameters) &&
           rightBass_.configure(sampleRateHz_, bassParameters) &&
           leftTreble_.configure(sampleRateHz_, trebleParameters) &&
           rightTreble_.configure(sampleRateHz_, trebleParameters);
}

bool DspEngine::setTone(float bassGainDb, float trebleGainDb) {
    if (!std::isfinite(bassGainDb) || !std::isfinite(trebleGainDb)) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    bassGainDb_ = std::clamp(bassGainDb, -kMaximumToneGainDb, kMaximumToneGainDb);
    trebleGainDb_ = std::clamp(trebleGainDb, -kMaximumToneGainDb, kMaximumToneGainDb);
    return configureToneFilters();
}

bool DspEngine::setPreampGain(float gainDb) {
    if (!std::isfinite(gainDb)) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    LimiterParameters updated = limiterParameters_;
    updated.preampGainDb = std::clamp(gainDb, -24.0F, 24.0F);
    if (!limiter_.setParameters(updated)) {
        return false;
    }
    limiterParameters_ = updated;
    return true;
}

bool DspEngine::setLimiterParameters(const LimiterParameters& parameters) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!limiter_.setParameters(parameters)) {
        return false;
    }
    limiterParameters_ = parameters;
    return true;
}

bool DspEngine::setDvcVolume(float linearGain) {
    if (!std::isfinite(linearGain) ||
        linearGain < 0.0F ||
        linearGain > 1.0F) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    dvcVolume_ = linearGain;
    return true;
}

bool DspEngine::process(float* interleavedStereo, std::size_t frameCount) {
    if (interleavedStereo == nullptr && frameCount > 0U) {
        return false;
    }
    if (frameCount == 0U) {
        return true;
    }

    std::lock_guard<std::mutex> lock(mutex_);

    for (std::size_t frame = 0U; frame < frameCount; ++frame) {
        const std::size_t offset = frame * 2U;
        float left = interleavedStereo[offset];
        float right = interleavedStereo[offset + 1U];

        if (!std::isfinite(left)) {
            left = 0.0F;
        }
        if (!std::isfinite(right)) {
            right = 0.0F;
        }

        for (std::size_t bandIndex = 0U;
             bandIndex < kMaximumBands;
             ++bandIndex) {
            if (!bandSettings_[bandIndex].enabled) {
                continue;
            }
            left = leftBands_[bandIndex].processSample(left);
            right = rightBands_[bandIndex].processSample(right);
        }

        left = leftBass_.processSample(left);
        right = rightBass_.processSample(right);
        left = leftTreble_.processSample(left);
        right = rightTreble_.processSample(right);

        interleavedStereo[offset] = left;
        interleavedStereo[offset + 1U] = right;
    }

    limiter_.process(interleavedStereo, frameCount);

    if (dvcVolume_ != 1.0F) {
        for (std::size_t sample = 0U; sample < frameCount * 2U; ++sample) {
            interleavedStereo[sample] *= dvcVolume_;
        }
    }

    return true;
}

void DspEngine::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (std::size_t index = 0U; index < kMaximumBands; ++index) {
        leftBands_[index].reset();
        rightBands_[index].reset();
    }
    leftBass_.reset();
    rightBass_.reset();
    leftTreble_.reset();
    rightTreble_.reset();
    limiter_.reset();
}

std::size_t DspEngine::latencyFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return limiter_.latencyFrames();
}

float DspEngine::sampleRateHz() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return sampleRateHz_;
}

}  // namespace ggequalizer
