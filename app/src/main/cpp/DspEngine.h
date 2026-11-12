#pragma once

#include "BiquadFilter.h"
#include "Limiter.h"

#include <array>
#include <cstddef>
#include <mutex>

namespace ggequalizer {

struct EqualizerBand {
    BiquadType type{BiquadType::Peaking};
    float frequencyHz{1000.0F};
    float gainDb{0.0F};
    float qFactor{1.0F};
    bool enabled{false};
};

/**
 * Stereo pipeline: 32 cascaded parametric bands, bass/treble shelves,
 * preamp + look-ahead limiter, then an independent DVC output multiplier.
 * All processing buffers are fixed-size and process() allocates no memory.
 */
class DspEngine final {
public:
    static constexpr std::size_t kMaximumBands = 32U;

    DspEngine();

    bool setSampleRate(float sampleRateHz);
    bool setBand(std::size_t index, const EqualizerBand& band);
    bool setBandEnabled(std::size_t index, bool enabled);
    bool setTone(float bassGainDb, float trebleGainDb);
    bool setPreampGain(float gainDb);
    bool setLimiterParameters(const LimiterParameters& parameters);
    bool setDvcVolume(float linearGain);

    bool process(float* interleavedStereo, std::size_t frameCount);
    void reset();

    std::size_t latencyFrames() const;
    float sampleRateHz() const;

private:
    bool configureToneFilters() noexcept;

    mutable std::mutex mutex_;
    float sampleRateHz_{48000.0F};

    std::array<EqualizerBand, kMaximumBands> bandSettings_{};
    std::array<BiquadFilter, kMaximumBands> leftBands_{};
    std::array<BiquadFilter, kMaximumBands> rightBands_{};

    BiquadFilter leftBass_{};
    BiquadFilter rightBass_{};
    BiquadFilter leftTreble_{};
    BiquadFilter rightTreble_{};

    float bassGainDb_{0.0F};
    float trebleGainDb_{0.0F};
    float dvcVolume_{1.0F};

    LimiterParameters limiterParameters_{};
    Limiter limiter_{};
};

}  // namespace ggequalizer
