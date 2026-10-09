package br.com.sistema51.a34.dsp;

/** RBJ cookbook biquad, direct form II transposed, double precision state. */
final class Biquad {
    private double b0 = 1, b1, b2, a1, a2, z1, z2;

    void coefficients(double[] c) {
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4];
    }
    double process(double x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1 = 0; z2 = 0; }

    static double[] lowPass(double frequency, double q) { return pass(frequency, q, false); }
    static double[] highPass(double frequency, double q) { return pass(frequency, q, true); }
    private static double[] pass(double frequency, double q, boolean high) {
        double omega = 2 * Math.PI * frequency / AudioProfile.SAMPLE_RATE;
        double cosine = Math.cos(omega), alpha = Math.sin(omega) / (2 * q);
        double numerator = high ? 1 + cosine : 1 - cosine;
        double divisor = 1 + alpha;
        return new double[] { numerator / (2 * divisor), (high ? -numerator : numerator) / divisor,
                numerator / (2 * divisor), -2 * cosine / divisor, (1 - alpha) / divisor };
    }
    static double[] peak(double frequency, double q, double gainDb) {
        if (gainDb == 0) return new double[] {1, 0, 0, 0, 0};
        double omega = 2 * Math.PI * frequency / AudioProfile.SAMPLE_RATE;
        double amplitude = Math.pow(10, gainDb / 40);
        double alpha = Math.sin(omega) / (2 * q), cosine = Math.cos(omega);
        double divisor = 1 + alpha / amplitude;
        return new double[] {(1 + alpha * amplitude) / divisor, -2 * cosine / divisor,
                (1 - alpha * amplitude) / divisor, -2 * cosine / divisor,
                (1 - alpha / amplitude) / divisor};
    }
}
