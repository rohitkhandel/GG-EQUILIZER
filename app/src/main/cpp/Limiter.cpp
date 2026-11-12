#include "Limiter.h"

#include <algorithm>
#include <cmath>

namespace ggequalizer {
namespace {

constexpr float kMinimumSampleRateHz = 8000.0F;
constexpr float kMaximumSampleRateHz = 192000.0F;
constexpr float kMinimumPreampDb = -24.0F;
constexpr float kMaximumPreampDb = 24.0F;
constexpr float kMinimumThresholdDb = -12.0F;
constexpr float kMaximumThresholdDb = 0.0F;
constexpr float kMaximumKneeDb = 12.0F;
constexpr float kMaximumLookAheadMs = 20.0F;
constexpr float kMinimumReleaseMs = 10.0F;
constexpr float kMaximumReleaseMs = 300.0F;
constexpr float kSilenceFloor = 1.0e-12F;

bool validParameters(const LimiterParameters& p) noexcept {
    return std::isfinite(p.preampGainDb) &&
           std::isfinite(p.thresholdDb) &&
           std::isfinite(p.kneeDb) &&
           std::isfinite(p.lookAheadMs) &&
           std::isfinite(p.releaseMs) &&
           p.preampGainDb >= kMinimumPreampDb &&
           p.preampGainDb <= kMaximumPreampDb &&
           p.thresholdDb >= kMinimumThresholdDb &&
           p.thresholdDb <= kMaximumThresholdDb &&
           p.kneeDb >= 0.0F &&
           p.kneeDb <= kMaximumKneeDb &&
           p.lookAheadMs >= 0.0F &&
           p.lookAheadMs <= kMaximumLookAheadMs &&
           p.releaseMs >= kMinimumReleaseMs &&
           p.releaseMs <= kMaximumReleaseMs;
}

float decibelsToLinear(float db) noexcept {
    return std::pow(10.0F, db / 20.0F);
}

}  // namespace

Limiter::Limiter() noexcept {
    prepare(sampleRateHz_);
}

bool Limiter::prepare(float sampleRateHz) noexcept {
    if (!std::isfinite(sampleRateHz) ||
        sampleRateHz < kMinimumSampleRateHz ||
        sampleRateHz > kMaximumSampleRateHz) {
        return false;
    }

    sampleRateHz_ = sampleRateHz;
    updateDerivedParameters();
    reset();
    return true;
}

bool Limiter::setParameters(const LimiterParameters& parameters) noexcept {
    if (!validParameters(parameters)) {
        return false;
    }

    const bool delayChanged =
        parameters.lookAheadMs != parameters_.lookAheadMs;
    parameters_ = parameters;
    updateDerivedParameters();

    if (delayChanged) {
        reset();
    }
    return true;
}

void Limiter::updateDerivedParameters() noexcept {
    const double requestedFrames =
        static_cast<double>(parameters_.lookAheadMs) *
        static_cast<double>(sampleRateHz_) / 1000.0;
    const auto roundedFrames =
        static_cast<std::size_t>(std::llround(requestedFrames));

    lookAheadFrames_ = std::min(
        roundedFrames,
        kMaximumLookAheadFrames);
    delayBufferLength_ = lookAheadFrames_ + 1U;

    preampLinear_ = decibelsToLinear(parameters_.preampGainDb);

    const double releaseSeconds =
        static_cast<double>(parameters_.releaseMs) / 1000.0;
    const double releaseSamples =
        std::max(1.0, static_cast<double>(sampleRateHz_) * releaseSeconds);
    releaseCoefficient_ =
        static_cast<float>(std::exp(-1.0 / releaseSamples));
}

void Limiter::reset() noexcept {
    delayLeft_.fill(0.0F);
    delayRight_.fill(0.0F);
    queueHead_ = 0U;
    queueSize_ = 0U;
    frameIndex_ = 0U;
    writeIndex_ = 0U;
    currentGain_ = 1.0F;
}

float Limiter::gainForPeak(float peak) const noexcept {
    const float peakDb =
        20.0F * std::log10(std::max(peak, kSilenceFloor));
    const float threshold = parameters_.thresholdDb;
    const float knee = parameters_.kneeDb;

    if (knee <= 0.0001F) {
        return peakDb <= threshold
            ? 1.0F
            : decibelsToLinear(threshold - peakDb);
    }

    const float halfKnee = knee * 0.5F;
    if (peakDb <= threshold - halfKnee) {
        return 1.0F;
    }
    if (peakDb >= threshold + halfKnee) {
        return decibelsToLinear(threshold - peakDb);
    }

    const float distanceIntoKnee = peakDb - (threshold - halfKnee);
    const float outputDb =
        peakDb - (distanceIntoKnee * distanceIntoKnee) / (2.0F * knee);
    return std::clamp(decibelsToLinear(outputDb - peakDb), 0.0F, 1.0F);
}

void Limiter::process(float* interleavedStereo,
                      std::size_t frameCount) noexcept {
    if (interleavedStereo == nullptr || frameCount == 0U) {
        return;
    }

    for (std::size_t frame = 0U; frame < frameCount; ++frame) {
        const std::size_t offset = frame * 2U;
        float inputLeft = interleavedStereo[offset];
        float inputRight = interleavedStereo[offset + 1U];

        if (!std::isfinite(inputLeft)) {
            inputLeft = 0.0F;
        }
        if (!std::isfinite(inputRight)) {
            inputRight = 0.0F;
        }

        inputLeft = std::clamp(inputLeft * preampLinear_, -64.0F, 64.0F);
        inputRight = std::clamp(inputRight * preampLinear_, -64.0F, 64.0F);

        delayLeft_[writeIndex_] = inputLeft;
        delayRight_[writeIndex_] = inputRight;

        const float currentPeak = std::max(
            std::abs(inputLeft),
            std::abs(inputRight));

        const std::uint64_t lookAhead =
            static_cast<std::uint64_t>(lookAheadFrames_);
        const std::uint64_t oldestRequired =
            frameIndex_ > lookAhead ? frameIndex_ - lookAhead : 0U;

        while (queueSize_ > 0U &&
               peakQueue_[queueHead_].frameIndex < oldestRequired) {
            queueHead_ = (queueHead_ + 1U) % kPeakQueueCapacity;
            --queueSize_;
        }

        while (queueSize_ > 0U) {
            const std::size_t backIndex =
                (queueHead_ + queueSize_ - 1U) % kPeakQueueCapacity;
            if (peakQueue_[backIndex].peak > currentPeak) {
                break;
            }
            --queueSize_;
        }

        const std::size_t insertIndex =
            (queueHead_ + queueSize_) % kPeakQueueCapacity;
        peakQueue_[insertIndex] = PeakNode{frameIndex_, currentPeak};
        ++queueSize_;

        const float windowPeak =
            queueSize_ > 0U ? peakQueue_[queueHead_].peak : currentPeak;

        if (!parameters_.enabled) {
            currentGain_ = 1.0F;
        } else {
            const float targetGain = gainForPeak(windowPeak);
            if (targetGain < currentGain_) {
                currentGain_ = targetGain;
            } else {
                currentGain_ =
                    targetGain + releaseCoefficient_ * (currentGain_ - targetGain);
            }
        }

        const std::size_t readIndex =
            (writeIndex_ + 1U) % delayBufferLength_;

        float outputLeft = 0.0F;
        float outputRight = 0.0F;
        if (frameIndex_ >= lookAhead) {
            outputLeft = delayLeft_[readIndex] * currentGain_;
            outputRight = delayRight_[readIndex] * currentGain_;
        }

        if (!std::isfinite(outputLeft)) {
            outputLeft = 0.0F;
        }
        if (!std::isfinite(outputRight)) {
            outputRight = 0.0F;
        }

        if (parameters_.enabled) {
            // The look-ahead gain computer should keep this below full scale.
            // This final guard only catches numeric overshoot while limiting.
            interleavedStereo[offset] = std::clamp(outputLeft, -1.0F, 1.0F);
            interleavedStereo[offset + 1U] = std::clamp(outputRight, -1.0F, 1.0F);
        } else {
            // Limiter bypass must not silently become a hard clipper.
            interleavedStereo[offset] = std::clamp(outputLeft, -64.0F, 64.0F);
            interleavedStereo[offset + 1U] = std::clamp(outputRight, -64.0F, 64.0F);
        }

        writeIndex_ = (writeIndex_ + 1U) % delayBufferLength_;
        ++frameIndex_;
    }
}

std::size_t Limiter::latencyFrames() const noexcept {
    return lookAheadFrames_;
}

float Limiter::sampleRateHz() const noexcept {
    return sampleRateHz_;
}

const LimiterParameters& Limiter::parameters() const noexcept {
    return parameters_;
}

}  // namespace ggequalizer
