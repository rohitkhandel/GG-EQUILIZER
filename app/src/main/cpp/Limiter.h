#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace ggequalizer {

struct LimiterParameters {
    bool enabled{true};
    float preampGainDb{0.0F};
    float thresholdDb{-1.0F};
    float kneeDb{6.0F};
    float lookAheadMs{5.0F};
    float releaseMs{80.0F};
};

/**
 * Stereo look-ahead limiter with a soft-knee infinite-ratio transfer curve.
 * Delay and monotonic-peak-deque storage are fixed-size; process() allocates nothing.
 */
class Limiter final {
public:
    static constexpr std::size_t kMaximumLookAheadFrames = 3840U;
    static constexpr std::size_t kPeakQueueCapacity =
        kMaximumLookAheadFrames + 2U;

    Limiter() noexcept;

    bool prepare(float sampleRateHz) noexcept;
    bool setParameters(const LimiterParameters& parameters) noexcept;
    void reset() noexcept;
    void process(float* interleavedStereo, std::size_t frameCount) noexcept;

    std::size_t latencyFrames() const noexcept;
    float sampleRateHz() const noexcept;
    const LimiterParameters& parameters() const noexcept;

private:
    struct PeakNode {
        std::uint64_t frameIndex{0U};
        float peak{0.0F};
    };

    void updateDerivedParameters() noexcept;
    float gainForPeak(float peak) const noexcept;

    float sampleRateHz_{48000.0F};
    LimiterParameters parameters_{};

    float preampLinear_{1.0F};
    float releaseCoefficient_{0.0F};

    std::size_t lookAheadFrames_{240U};
    std::size_t delayBufferLength_{241U};
    std::size_t writeIndex_{0U};

    std::array<float, kMaximumLookAheadFrames + 1U> delayLeft_{};
    std::array<float, kMaximumLookAheadFrames + 1U> delayRight_{};
    std::array<PeakNode, kPeakQueueCapacity> peakQueue_{};

    std::size_t queueHead_{0U};
    std::size_t queueSize_{0U};
    std::uint64_t frameIndex_{0U};
    float currentGain_{1.0F};
};

}  // namespace ggequalizer
