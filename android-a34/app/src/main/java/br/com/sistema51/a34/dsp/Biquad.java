package br.com.sistema51.a34.dsp;

/** RBJ cookbook biquad, direct form II transposed, double precision state. */
final class Biquad {
    private double b0 = 1, b1, b2, a1, a2, z1, z2;
    private final double[] target=new double[5],step=new double[5];
    private int remaining;

    void coefficients(double[] c) {
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4];
        remaining=0;
    }
    void smoothCoefficients(double[] c){
        if(b0==c[0]&&b1==c[1]&&b2==c[2]&&a1==c[3]&&a2==c[4]&&remaining==0)return;
        System.arraycopy(c,0,target,0,5);remaining=480;
        step[0]=(c[0]-b0)/remaining;step[1]=(c[1]-b1)/remaining;step[2]=(c[2]-b2)/remaining;
        step[3]=(c[3]-a1)/remaining;step[4]=(c[4]-a2)/remaining;
    }
    double process(double x) {
        if(remaining>0){b0+=step[0];b1+=step[1];b2+=step[2];a1+=step[3];a2+=step[4];if(--remaining==0)coefficients(target);}
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1 = 0; z2 = 0; }
    boolean active(){return remaining>0||b0!=1||b1!=0||b2!=0||a1!=0||a2!=0||z1!=0||z2!=0;}

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
