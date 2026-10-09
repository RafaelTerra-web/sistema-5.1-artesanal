"""Inspect PRIVATE stereo PCM16/48 kHz captures against the known 5 s test loop.

Offline only: never opens an audio device, records, plays or uploads audio.
Requires NumPy and SciPy. Inputs/derived captures must remain in ignored paths.

Example:
  python scripts/optical-tests/analyze-pcm-capture.py PRIVATE/capture.pcm --output PRIVATE/report.json
  python scripts/optical-tests/analyze-pcm-capture.py --self-test --output android-a34/app/build/analysis-tests/pcm-self-test.json

A common reference offset is used for BOTH channels. The source phase in a loop
is not an end-to-end latency measurement. Spectral/envelope identity can survive
TV resampling or gain changes; it does not establish unaltered transport bits.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import unittest

import numpy as np
from scipy.signal import fftconvolve

ROOT = Path(__file__).resolve().parents[2]
REFERENCE = ROOT / "android-a34/artifacts/optical-2026-10-09/vectors/pcm-stereo-48k-minus36dbfs-5s.wav"
RATE = 48000
MAX_BYTES = 16 * 1024 * 1024
MAX_FRAMES = RATE * 10
REFERENCE_SHA256 = "d85c4aa0cc6dac66f366bb9dfc5b1bd4a85c31108431b2d209c2d927e6046ffa"
VALIDATED_REFERENCE_SHA256 = {
    REFERENCE_SHA256,
    # Same source pattern, locally generated PCM16 at -24 dBFS for TV/loop tests.
    "2a3085c54fbcf801d8245c65222bc3959b4aa9fd3fdca737f2b221ff38620b48",
}
FREQUENCIES = (400.0, 700.0)
WINDOW = 3840  # 80 ms; both known frequencies fall exactly on FFT bins.
HOP = 480  # 10 ms; the same time grid is used on all channels/frequencies.
THRESHOLDS = {
    "silence_rms_dbfs": -90.0,
    "silence_peak_integer": 2,
    "known_tone_power_fraction": 0.70,
    "each_tone_max_channel_power_fraction": 0.04,
    "joint_envelope_correlation": 0.80,
    "correct_channel_power_fraction": 0.99,
    "high_waveform_correlation": 0.95,
    "maximum_drift_search_ppm": 2000.0,
}


def db(value):
    return float(20 * np.log10(value)) if value > 0 else None


def checked_samples(samples):
    samples = np.asarray(samples, dtype=np.float64)
    if samples.ndim != 2 or samples.shape[1] != 2 or len(samples) < WINDOW:
        raise ValueError("Require at least 80 ms of two-channel samples")
    if samples.nbytes > MAX_BYTES * 4:
        raise ValueError("Capture exceeds the 16 MiB PCM16 input bound")
    if len(samples) > MAX_FRAMES:
        raise ValueError("Capture exceeds the bounded 10 s analysis duration")
    if not np.isfinite(samples).all():
        raise ValueError("Capture contains non-finite samples")
    return samples


def read_raw(path):
    if not 0 < path.stat().st_size <= MAX_BYTES:
        raise ValueError("Capture must contain 1..16 MiB of PCM16 data")
    raw = path.read_bytes()
    if len(raw) % 4:
        raise ValueError("Incomplete interleaved stereo PCM16 frame")
    return checked_samples(np.frombuffer(raw, dtype="<i2").reshape(-1, 2) / 32768.0), raw


def read_reference(path):
    raw = path.read_bytes()
    if len(raw) < 44 or raw[:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError("Reference is not a RIFF/WAVE file")
    if struct.unpack_from("<I", raw, 4)[0] + 8 != len(raw):
        raise ValueError("Reference RIFF size mismatch")
    fmt = payload = None
    offset = 12
    while offset + 8 <= len(raw):
        name, length = struct.unpack_from("<4sI", raw, offset)
        end = offset + 8 + length
        if end > len(raw):
            raise ValueError("Truncated reference RIFF chunk")
        if name == b"fmt ":
            fmt = raw[offset + 8:end]
        elif name == b"data":
            payload = raw[offset + 8:end]
        offset = end + (length % 2)
    if fmt is None or len(fmt) < 16 or payload is None:
        raise ValueError("Reference requires fmt and data chunks")
    tag, channels, rate, byte_rate, align, bits = struct.unpack_from("<HHIIHH", fmt)
    if (channels, rate, byte_rate, align, bits) != (2, RATE, RATE * 4, 4, 16):
        raise ValueError("Reference must be stereo signed PCM16 at 48 kHz")
    if tag == 0xfffe:
        if len(fmt) < 40 or struct.unpack_from("<H", fmt, 16)[0] < 22:
            raise ValueError("Incomplete extensible reference format")
        if struct.unpack_from("<HI", fmt, 18) != (16, 3):
            raise ValueError("Reference valid-bit count/channel mask mismatch")
        if fmt[24:40] != bytes.fromhex("0100000000001000800000aa00389b71"):
            raise ValueError("Reference subtype is not PCM")
    elif tag != 1:
        raise ValueError("Reference format is not PCM")
    if len(payload) != RATE * 5 * 4:
        raise ValueError("Reference must be the complete 5 s test vector")
    if hashlib.sha256(raw).hexdigest() not in VALIDATED_REFERENCE_SHA256:
        raise ValueError("Reference SHA-256 differs from the validated synthetic test vector")
    return checked_samples(np.frombuffer(payload, dtype="<i2").reshape(-1, 2) / 32768.0), raw


def spectrum(samples):
    channels = []
    frequencies = np.fft.rfftfreq(len(samples), 1 / RATE)
    window = np.hanning(len(samples))
    for channel in range(2):
        values = samples[:, channel]
        centered = values - values.mean()
        # Untapered power treats short tones at a capture edge like tones in the
        # middle. A whole-capture Hann window would otherwise suppress an edge
        # tone and could falsely classify a quiet right channel as crosstalk.
        power = np.abs(np.fft.rfft(centered)) ** 2
        peak_power = np.abs(np.fft.rfft(centered * window)) ** 2
        power[0] = 0
        peak_power[0] = 0
        total = float(power.sum())
        bands = []
        for tone in FREQUENCIES:
            indices = np.flatnonzero(np.abs(frequencies - tone) <= 15)
            tone_power = float(power[indices].sum())
            frequency = None
            band_fraction = tone_power / total if total else 0.0
            significant = band_fraction >= THRESHOLDS["each_tone_max_channel_power_fraction"]
            if significant and tone_power > 0 and indices.size:
                index = int(indices[np.argmax(peak_power[indices])])
                # Log-parabolic interpolation reduces bin quantization, but is an estimate.
                correction = 0.0
                if 0 < index < len(power) - 1:
                    left, middle, right = np.log(np.maximum(peak_power[index - 1:index + 2], 1e-300))
                    denominator = left - 2 * middle + right
                    if denominator:
                        correction = float(np.clip(0.5 * (left - right) / denominator, -0.5, 0.5))
                frequency = float((index + correction) * RATE / len(samples))
            bands.append({"expected_hz": tone, "estimated_peak_hz": frequency,
                          "power": tone_power, "fraction_of_channel_power": band_fraction,
                          "significant_band_energy": bool(significant)})
        candidate_power = power.copy()
        candidate_power[frequencies < 20] = 0
        peaks = []
        if total:
            for _ in range(5):
                index = int(np.argmax(candidate_power))
                if not candidate_power[index] > 0:
                    break
                peaks.append({"frequency_hz": float(frequencies[index]),
                              "fraction_of_channel_power": float(power[index] / total)})
                candidate_power[np.abs(frequencies - frequencies[index]) <= 20] = 0
        channels.append({"channel": ("L", "R")[channel], "peak_integer": int(round(np.abs(values).max() * 32768)),
                         "rms_dbfs": db(float(np.sqrt(np.mean(values ** 2)))),
                         "ac_rms_dbfs": db(float(np.sqrt(np.mean(centered ** 2)))),
                         "rms_integer": float(np.sqrt(np.mean(values ** 2)) * 32768),
                         "ac_rms_integer": float(np.sqrt(np.mean(centered ** 2)) * 32768),
                         "min_integer": int(round(values.min() * 32768)),
                         "max_integer": int(round(values.max() * 32768)),
                         "dc_integer": float(values.mean() * 32768),
                         "clipped_samples": int(np.count_nonzero(np.abs(values) >= 32767 / 32768)),
                         "total_spectral_power": total, "tone_bands": bands, "spectral_peaks": peaks})
    return channels


def band_envelopes(samples, circular=False):
    if circular:
        centers = np.arange(0, len(samples), HOP)
        indices = (centers[:, None] + np.arange(-WINDOW // 2, WINDOW // 2)[None, :]) % len(samples)
        windows = samples[indices]
    else:
        starts = np.arange(0, len(samples) - WINDOW + 1, HOP)
        windows = samples[starts[:, None] + np.arange(WINDOW)[None, :]]
    window = np.hanning(WINDOW)
    values = windows - windows.mean(axis=1, keepdims=True)
    power = np.abs(np.fft.rfft(values * window[None, :, None], axis=1)) ** 2
    fft_hz = np.fft.rfftfreq(WINDOW, 1 / RATE)
    return np.stack([power[:, np.abs(fft_hz - tone) <= 15, :].sum(axis=1)
                     for tone in FREQUENCIES], axis=2)


def envelope_alignment(capture, reference):
    captured = band_envelopes(capture).sum(axis=1)  # time x tone; channels share one timeline.
    expected = band_envelopes(reference, circular=True).sum(axis=1)
    centered = captured - captured.mean(axis=0)
    energy = np.sum(centered ** 2, axis=0)
    offsets = np.arange(len(expected))
    positions = (offsets[:, None] + np.arange(len(captured))[None, :] + WINDOW // 2 // HOP) % len(expected)
    candidates = expected[positions]
    candidates = candidates - candidates.mean(axis=1, keepdims=True)
    dots = np.sum(candidates * centered[None, :, :], axis=1)
    denominator = np.sqrt(np.sum(candidates ** 2, axis=1) * energy[None, :])
    correlations = np.divide(dots, denominator, out=np.zeros_like(dots), where=denominator > 1e-30)
    scores = correlations.mean(axis=1)
    best = int(np.argmax(scores))
    return {"joint_correlation": float(scores[best]),
            "tone_correlations": dict(zip(("400_hz", "700_hz"), map(float, correlations[best]))),
            "common_reference_phase_seconds": best * HOP / RATE,
            "search_resolution_seconds": HOP / RATE,
            "phase_search_bounds_seconds": [0.0, len(reference) / RATE],
            "same_phase_for_both_channels": True,
            "absolute_signal_latency_measured": False}


def waveform_alignment(capture, reference, drift_ppm=0.0):
    """FFT NCC, one shared offset per mapping; no independent channel alignment."""
    speed = 1 + drift_ppm / 1e6
    phase_count = int(np.ceil(len(reference) / speed))
    count = phase_count + len(capture) - 1
    if drift_ppm == 0:
        repeated = reference[np.arange(count) % len(reference)]
    else:
        positions = np.arange(count) * speed
        first = positions.astype(np.int64) % len(reference)
        weight = (positions % 1)[:, None]
        repeated = reference[first] * (1 - weight) + reference[(first + 1) % len(reference)] * weight
    captured = capture - capture.mean(axis=0)
    captured_energy = np.sum(captured ** 2, axis=0)
    sums = np.vstack([np.zeros((1, 2)), np.cumsum(repeated, axis=0)])
    squares = np.vstack([np.zeros((1, 2)), np.cumsum(repeated ** 2, axis=0)])
    n = len(capture)
    moving_sum = sums[n:] - sums[:-n]
    reference_energy = np.maximum(0, squares[n:] - squares[:-n] - moving_sum ** 2 / n)
    results = []
    for mapping, name in (((0, 1), "L_to_L_R_to_R"), ((1, 0), "L_to_R_R_to_L")):
        correlations = []
        for destination, source in enumerate(mapping):
            dots = fftconvolve(repeated[:, source], captured[::-1, destination], mode="valid")
            denom = np.sqrt(reference_energy[:, source] * captured_energy[destination])
            correlations.append(np.divide(dots, denom, out=np.zeros_like(dots), where=denom > 1e-20))
        correlations = np.asarray(correlations)
        # Absolute correlation permits polarity inversion; signed gains are reported below.
        scores = np.mean(np.abs(correlations), axis=0)
        best = int(np.argmax(scores))
        aligned = repeated[best:best + n, mapping]
        aligned -= aligned.mean(axis=0)
        gains = np.divide(np.sum(aligned * captured, axis=0), np.sum(aligned ** 2, axis=0),
                          out=np.zeros(2), where=np.sum(aligned ** 2, axis=0) > 1e-20)
        residual = captured - aligned * gains
        residual_ratio = np.divide(np.sum(residual ** 2, axis=0), captured_energy,
                                   out=np.ones(2), where=captured_energy > 1e-20)
        results.append({"mapping": name, "common_reference_phase_seconds": best * speed / RATE,
                        "drift_search_ppm": float(drift_ppm), "mean_absolute_correlation": float(scores[best]),
                        "signed_channel_correlations": list(map(float, correlations[:, best])),
                        "fitted_channel_gains": list(map(float, gains)),
                        "relative_residual_rms_after_gain_fit": list(map(float, np.sqrt(residual_ratio))),
                        "same_phase_for_both_channels": True})
    return max(results, key=lambda result: result["mean_absolute_correlation"])


def analyze(capture, reference):
    capture, reference = checked_samples(capture), checked_samples(reference)
    if len(reference) != RATE * 5:
        raise ValueError("Expected a 5 s reference")
    spectral = spectrum(capture)
    power = np.asarray([[band["power"] for band in channel["tone_bands"]] for channel in spectral])
    total_power = sum(channel["total_spectral_power"] for channel in spectral)
    tone_fraction = float(power.sum() / total_power) if total_power else 0.0
    each_fraction = power.sum(axis=0) / total_power if total_power else np.zeros(2)
    each_channel_fraction = np.max(np.asarray([[band["fraction_of_channel_power"] for band in channel["tone_bands"]]
                                              for channel in spectral]), axis=0)
    routing = np.divide(power, power.sum(axis=0)[None, :], out=np.zeros_like(power), where=power.sum(axis=0)[None, :] > 0)
    silence = np.abs(capture).max() * 32768 <= THRESHOLDS["silence_peak_integer"] and float(np.sqrt(np.mean(capture ** 2))) < 10 ** (THRESHOLDS["silence_rms_dbfs"] / 20)
    envelope = envelope_alignment(capture, reference)
    tones_present = tone_fraction >= THRESHOLDS["known_tone_power_fraction"] and bool(np.all(each_channel_fraction >= THRESHOLDS["each_tone_max_channel_power_fraction"]))
    source_present = tones_present and envelope["joint_correlation"] >= THRESHOLDS["joint_envelope_correlation"]
    route_threshold = THRESHOLDS["correct_channel_power_fraction"]
    if routing[0, 0] >= route_threshold and routing[1, 1] >= route_threshold:
        mapping = "expected_stereo"
    elif routing[1, 0] >= route_threshold and routing[0, 1] >= route_threshold:
        mapping = "swapped_stereo"
    else:
        mapping = "mixed_or_incomplete_channels"
    drift_candidates = [0.0]
    estimates = []
    if tones_present:
        for tone_index, tone in enumerate(FREQUENCIES):
            strongest = int(np.argmax(power[:, tone_index]))
            frequency = spectral[strongest]["tone_bands"][tone_index]["estimated_peak_hz"]
            if frequency is not None:
                estimates.append((frequency / tone - 1) * 1e6)
        if len(estimates) == 2 and abs(estimates[0] - estimates[1]) < 250:
            estimated = float(np.mean(estimates))
            bound = THRESHOLDS["maximum_drift_search_ppm"]
            if 30 < abs(estimated) <= bound:
                drift_candidates.extend(float(np.clip(estimated + delta, -bound, bound)) for delta in (-25, 0, 25))
    waveform_results = [waveform_alignment(capture, reference, drift) for drift in drift_candidates]
    waveform = max(waveform_results, key=lambda result: result["mean_absolute_correlation"])
    if silence:
        classification = "silence"
    elif source_present:
        classification = {"expected_stereo": "known_source_present_expected_stereo",
                          "swapped_stereo": "known_source_present_swapped_channels",
                          "mixed_or_incomplete_channels": "known_source_present_mixed_channels"}[mapping]
    elif tones_present:
        classification = "known_tones_detected_timing_inconclusive"
    else:
        classification = "noise_or_other_signal"
    return {"format": {"encoding": "signed_pcm16_little_endian", "sample_rate_hz": RATE, "channels": 2},
            "capture_frames": len(capture), "capture_seconds": len(capture) / RATE,
            "classification": classification, "known_source_identity_supported": bool(source_present),
            "stereo_routing": mapping if tones_present else "not_established",
            "spectral": spectral, "combined_known_tone_power_fraction": tone_fraction,
            "each_tone_fraction_of_total_power": list(map(float, each_fraction)),
            "each_tone_max_channel_power_fraction": list(map(float, each_channel_fraction)),
            "channel_power_distribution_by_source_tone": {"400_hz": {"L": float(routing[0, 0]), "R": float(routing[1, 0])},
                                                         "700_hz": {"L": float(routing[0, 1]), "R": float(routing[1, 1])}},
            "envelope_alignment": envelope, "best_waveform_alignment": waveform,
            "nominal_rate_waveform_alignment": waveform_results[0],
            "tone_based_clock_drift_estimates_ppm": list(map(float, estimates)),
            "high_waveform_correlation_observed": waveform["mean_absolute_correlation"] >= THRESHOLDS["high_waveform_correlation"],
            "bit_perfect_validated": False, "absolute_signal_latency_measured": False,
            "physical_optical_route_independently_validated": False,
            "thresholds": THRESHOLDS,
            "limits": ["Identity concerns the known synthetic PCM stereo signal only; it does not validate AC-3, CRC, or six discrete channels.",
                       "Both channels use one common phase; no independent channel offsets are fitted.",
                       "Loop phase cannot determine latency without synchronized playback/capture timestamps.",
                       "Spectral peaks and drift estimates are window-dependent measurements, not clock calibration.",
                       "Known-source identity is consistent with the intended route; physical provenance also requires verified device routing.",
                       "Correlation, gain fitting and drift compensation do not prove unaltered transport bits."]}


def synthetic_reference():
    samples = np.zeros((RATE * 5, 2))
    for channel, frequency, first, last in ((0, 400, .5, 1.5), (1, 700, 1.75, 2.75), (0, 400, 3, 4), (1, 700, 3, 4)):
        count = round((last - first) * RATE)
        values = (10 ** (-36 / 20)) * np.sin(2 * np.pi * frequency * np.arange(count) / RATE)
        ramp = np.linspace(0, 1, 480)
        values[:480] *= ramp
        values[-480:] *= ramp[::-1]
        samples[round(first * RATE):round(last * RATE), channel] = values
    return np.rint(samples * 32768).astype("<i2").astype(np.float64) / 32768


def synthetic_capture(reference, phase, seconds=3, drift_ppm=0):
    positions = np.arange(round(seconds * RATE)) * (1 + drift_ppm / 1e6) + phase * RATE
    first = positions.astype(np.int64) % len(reference)
    weight = (positions % 1)[:, None]
    return reference[first] * (1 - weight) + reference[(first + 1) % len(reference)] * weight


class AnalyzerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.reference = synthetic_reference()

    def test_partial_repeat_and_independent_gains(self):
        capture = synthetic_capture(self.reference, 4.371) * np.array([.45, .7])
        report = analyze(capture, self.reference)
        self.assertEqual(report["classification"], "known_source_present_expected_stereo")
        match = report["best_waveform_alignment"]
        self.assertAlmostEqual(match["common_reference_phase_seconds"], 4.371, places=4)
        np.testing.assert_allclose(match["fitted_channel_gains"], [.45, .7], atol=1e-8)
        self.assertGreater(match["mean_absolute_correlation"], .99999)
        self.assertFalse(report["bit_perfect_validated"])

    def test_swapped_channels(self):
        report = analyze(synthetic_capture(self.reference, 1.23)[:, ::-1], self.reference)
        self.assertEqual(report["classification"], "known_source_present_swapped_channels")
        self.assertEqual(report["best_waveform_alignment"]["mapping"], "L_to_R_R_to_L")

    def test_mixed_channels(self):
        capture = synthetic_capture(self.reference, 2.82)
        mixed = np.repeat(capture.sum(axis=1)[:, None] * .5, 2, axis=1)
        self.assertEqual(analyze(mixed, self.reference)["classification"], "known_source_present_mixed_channels")

    def test_clock_drift_is_supported_without_exact_claim(self):
        report = analyze(synthetic_capture(self.reference, 2.213, drift_ppm=500), self.reference)
        self.assertEqual(report["classification"], "known_source_present_expected_stereo")
        self.assertGreater(report["best_waveform_alignment"]["mean_absolute_correlation"], .98)
        self.assertLess(report["nominal_rate_waveform_alignment"]["mean_absolute_correlation"], .90)
        self.assertFalse(report["bit_perfect_validated"])

    def test_noise_silence_and_unrelated_signal(self):
        silence = np.zeros((RATE * 3, 2))
        self.assertEqual(analyze(silence, self.reference)["classification"], "silence")
        noise = np.random.default_rng(1234).normal(0, .002, silence.shape)
        self.assertEqual(analyze(noise, self.reference)["classification"], "noise_or_other_signal")
        continuous = np.column_stack([.01 * np.sin(2 * np.pi * frequency * np.arange(RATE * 3) / RATE) for frequency in FREQUENCIES])
        self.assertEqual(analyze(continuous, self.reference)["classification"], "known_tones_detected_timing_inconclusive")

    def test_no_independent_channel_realignment(self):
        capture = synthetic_capture(self.reference, 1.21)
        capture[:, 1] = synthetic_capture(self.reference, 1.617)[:, 1]
        report = analyze(capture, self.reference)
        self.assertTrue(report["best_waveform_alignment"]["same_phase_for_both_channels"])
        self.assertLess(report["best_waveform_alignment"]["mean_absolute_correlation"], .95)

    def test_weak_tone_at_partial_capture_edge(self):
        capture = synthetic_capture(self.reference, 4.0) * np.array([.6, .02])
        report = analyze(capture, self.reference)
        self.assertEqual(report["classification"], "known_source_present_expected_stereo")

    def test_invalid_arrays(self):
        for invalid in (np.zeros((10, 2)), np.zeros((RATE, 1)), np.full((WINDOW, 2), np.nan)):
            with self.subTest(shape=invalid.shape):
                with self.assertRaises(ValueError):
                    analyze(invalid, self.reference)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", nargs="?", type=Path)
    parser.add_argument("--reference", type=Path, default=REFERENCE)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(AnalyzerTests))
        report = {"synthetic_offline_only": True, "tests_run": result.testsRun, "failures": len(result.failures),
                  "errors": len(result.errors), "passed": result.wasSuccessful(), "capture_or_playback_performed": False}
        code = 0 if result.wasSuccessful() else 1
    else:
        if args.capture is None:
            parser.error("Private PCM capture is required unless --self-test is selected")
        try:
            reference, ref_raw = read_reference(args.reference)
            capture, raw = read_raw(args.capture)
            report = analyze(capture, reference)
        except (ValueError, OSError) as error:
            parser.error(str(error))
        report["capture_sha256"] = hashlib.sha256(raw).hexdigest()
        report["reference_sha256"] = hashlib.sha256(ref_raw).hexdigest()
        report["reference_file"] = args.reference.name
        code = 0
    output = json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    print(output, end="")
    raise SystemExit(code)


if __name__ == "__main__":
    main()
