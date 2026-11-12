#pragma once

#include <cstdint>

namespace ggequalizer {

enum class BiquadType : std::int32_t {
    Peaking = 0,
    LowShelf = 1,
    HighShelf = 2
};

/**
 * Audio EQ Cookbook biquad parameters.
 * For shelf filters qFactor maps to cookbook slope S = clamp(1 / Q, 0.1, 1.0).
 */
struct BiquadParameters {
    BiquadType type{BiquadType::Peaking};
    float frequencyHz{1000.0F};
    float gainDb{0.0F};
    float qFactor{1.0F};
};

/** Normalized direct-form coefficients; a0 is 1 after normalization. */
struct BiquadCoefficients {
    float b0{1.0F};
    float b1{0.0F};
    float b2{0.0F};
    float a0{1.0F};
    float a1{0.0F};
    float a2{0.0F};
};

/** Stateful transposed-direct-form-II biquad. Use one instance per channel. */
class BiquadFilter final {
public:
    BiquadFilter() = default;

    bool configure(float sampleRateHz, const BiquadParameters& parameters) noexcept;
    float processSample(float input) noexcept;
    void reset() noexcept;

    const BiquadCoefficients& coefficients() const noexcept;
    const BiquadParameters& parameters() const noexcept;

private:
    BiquadCoefficients coefficients_{};
    BiquadParameters parameters_{};
    float z1_{0.0F};
    float z2_{0.0F};
};

}  // namespace ggequalizer
