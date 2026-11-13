#include "BiquadFilter.h"

#include <algorithm>
#include <cmath>

namespace ggequalizer {
namespace {

constexpr double kMinimumSampleRateHz = 8000.0;
constexpr double kMaximumSampleRateHz = 192000.0;
constexpr double kMinimumFrequencyHz = 10.0;
constexpr double kMinimumQ = 0.1;
constexpr double kMaximumQ = 12.0;
constexpr double kMinimumGainDb = -24.0;
constexpr double kMaximumGainDb = 24.0;
constexpr double kPi = 3.14159265358979323846;

bool isSupportedType(BiquadType type) noexcept {
    return type == BiquadType::Peaking ||
           type == BiquadType::LowShelf ||
           type == BiquadType::HighShelf ||
           type == BiquadType::LowPass ||
           type == BiquadType::HighPass ||
           type == BiquadType::BandPass;
}

bool finiteCoefficients(double b0, double b1, double b2,
                        double a0, double a1, double a2) noexcept {
    return std::isfinite(b0) && std::isfinite(b1) && std::isfinite(b2) &&
           std::isfinite(a0) && std::isfinite(a1) && std::isfinite(a2);
}

}  // namespace

bool BiquadFilter::configure(float sampleRateHz,
                             const BiquadParameters& parameters) noexcept {
    if (!std::isfinite(sampleRateHz) ||
        !std::isfinite(parameters.frequencyHz) ||
        !std::isfinite(parameters.gainDb) ||
        !std::isfinite(parameters.qFactor) ||
        sampleRateHz < kMinimumSampleRateHz ||
        sampleRateHz > kMaximumSampleRateHz ||
        parameters.frequencyHz <= 0.0F ||
        parameters.qFactor <= 0.0F ||
        !isSupportedType(parameters.type)) {
        return false;
    }

    const double sampleRate = static_cast<double>(sampleRateHz);
    const double frequency = std::clamp(
        static_cast<double>(parameters.frequencyHz),
        kMinimumFrequencyHz,
        sampleRate * 0.49);
    const double gainDb = std::clamp(
        static_cast<double>(parameters.gainDb),
        kMinimumGainDb,
        kMaximumGainDb);
    const double q = std::clamp(
        static_cast<double>(parameters.qFactor),
        kMinimumQ,
        kMaximumQ);

    parameters_ = parameters;
    parameters_.frequencyHz = static_cast<float>(frequency);
    parameters_.gainDb = static_cast<float>(gainDb);
    parameters_.qFactor = static_cast<float>(q);

    const bool usesBellOrShelfGain =
        parameters.type == BiquadType::Peaking ||
        parameters.type == BiquadType::LowShelf ||
        parameters.type == BiquadType::HighShelf;
    outputGainLinear_ = usesBellOrShelfGain
        ? 1.0F
        : static_cast<float>(std::pow(10.0, gainDb / 20.0));

    if (usesBellOrShelfGain && std::abs(gainDb) < 0.0001) {
        coefficients_ = BiquadCoefficients{};
        reset();
        return true;
    }

    const double amplitude = std::pow(10.0, gainDb / 40.0);
    const double omega = 2.0 * kPi * frequency / sampleRate;
    const double cosine = std::cos(omega);
    const double sine = std::sin(omega);
    const double alpha = sine / (2.0 * q);

    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a0 = 1.0;
    double a1 = 0.0;
    double a2 = 0.0;

    switch (parameters.type) {
        case BiquadType::Peaking:
            b0 = 1.0 + alpha * amplitude;
            b1 = -2.0 * cosine;
            b2 = 1.0 - alpha * amplitude;
            a0 = 1.0 + alpha / amplitude;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha / amplitude;
            break;

        case BiquadType::LowShelf:
        case BiquadType::HighShelf: {
            const double shelfSlope = std::clamp(1.0 / q, 0.1, 1.0);
            const double radicand =
                (amplitude + 1.0 / amplitude) * (1.0 / shelfSlope - 1.0) + 2.0;
            const double shelfAlpha =
                (sine / 2.0) * std::sqrt(std::max(radicand, 0.0));
            const double beta = 2.0 * std::sqrt(amplitude) * shelfAlpha;

            if (parameters.type == BiquadType::LowShelf) {
                b0 = amplitude * ((amplitude + 1.0) -
                                  (amplitude - 1.0) * cosine + beta);
                b1 = 2.0 * amplitude * ((amplitude - 1.0) -
                                        (amplitude + 1.0) * cosine);
                b2 = amplitude * ((amplitude + 1.0) -
                                  (amplitude - 1.0) * cosine - beta);
                a0 = (amplitude + 1.0) +
                     (amplitude - 1.0) * cosine + beta;
                a1 = -2.0 * ((amplitude - 1.0) +
                             (amplitude + 1.0) * cosine);
                a2 = (amplitude + 1.0) +
                     (amplitude - 1.0) * cosine - beta;
            } else {
                b0 = amplitude * ((amplitude + 1.0) +
                                  (amplitude - 1.0) * cosine + beta);
                b1 = -2.0 * amplitude * ((amplitude - 1.0) +
                                         (amplitude + 1.0) * cosine);
                b2 = amplitude * ((amplitude + 1.0) +
                                  (amplitude - 1.0) * cosine - beta);
                a0 = (amplitude + 1.0) -
                     (amplitude - 1.0) * cosine + beta;
                a1 = 2.0 * ((amplitude - 1.0) -
                            (amplitude + 1.0) * cosine);
                a2 = (amplitude + 1.0) -
                     (amplitude - 1.0) * cosine - beta;
            }
            break;
        }

        case BiquadType::LowPass:
            b0 = (1.0 - cosine) * 0.5;
            b1 = 1.0 - cosine;
            b2 = (1.0 - cosine) * 0.5;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;

        case BiquadType::HighPass:
            b0 = (1.0 + cosine) * 0.5;
            b1 = -(1.0 + cosine);
            b2 = (1.0 + cosine) * 0.5;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;

        case BiquadType::BandPass:
            b0 = alpha;
            b1 = 0.0;
            b2 = -alpha;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;
    }

    if (!finiteCoefficients(b0, b1, b2, a0, a1, a2) ||
        std::abs(a0) < 1.0e-12) {
        coefficients_ = BiquadCoefficients{};
        reset();
        return false;
    }

    const double inverseA0 = 1.0 / a0;
    coefficients_.b0 = static_cast<float>(b0 * inverseA0);
    coefficients_.b1 = static_cast<float>(b1 * inverseA0);
    coefficients_.b2 = static_cast<float>(b2 * inverseA0);
    coefficients_.a0 = 1.0F;
    coefficients_.a1 = static_cast<float>(a1 * inverseA0);
    coefficients_.a2 = static_cast<float>(a2 * inverseA0);

    const bool valid =
        std::isfinite(coefficients_.b0) &&
        std::isfinite(coefficients_.b1) &&
        std::isfinite(coefficients_.b2) &&
        std::isfinite(coefficients_.a1) &&
        std::isfinite(coefficients_.a2);

    if (!valid) {
        coefficients_ = BiquadCoefficients{};
        reset();
        return false;
    }

    reset();
    return true;
}

float BiquadFilter::processSample(float input) noexcept {
    if (!std::isfinite(input)) {
        reset();
        return 0.0F;
    }

    const float filteredOutput = coefficients_.b0 * input + z1_;
    const float output = filteredOutput * outputGainLinear_;
    const float nextZ1 =
        coefficients_.b1 * input - coefficients_.a1 * filteredOutput + z2_;
    const float nextZ2 =
        coefficients_.b2 * input - coefficients_.a2 * filteredOutput;

    if (!std::isfinite(output) ||
        !std::isfinite(nextZ1) ||
        !std::isfinite(nextZ2)) {
        reset();
        return 0.0F;
    }

    z1_ = std::abs(nextZ1) < 1.0e-20F ? 0.0F : nextZ1;
    z2_ = std::abs(nextZ2) < 1.0e-20F ? 0.0F : nextZ2;
    return output;
}

void BiquadFilter::reset() noexcept {
    z1_ = 0.0F;
    z2_ = 0.0F;
}

const BiquadCoefficients& BiquadFilter::coefficients() const noexcept {
    return coefficients_;
}

const BiquadParameters& BiquadFilter::parameters() const noexcept {
    return parameters_;
}

}  // namespace ggequalizer
