package br.com.sistema51.a34.dsp;

/** Validated delay-only updates. No other profile setting is changed. */
public final class DelayProfileUpdate {
    private DelayProfileUpdate() {}

    public static int[] parseSamples(String csv) {
        if (csv == null) throw new IllegalArgumentException("Informe seis atrasos em amostras.");
        String[] values = csv.split(",", -1);
        if (values.length != AudioProfile.CHANNEL_COUNT)
            throw new IllegalArgumentException("A ordem é FL, FR, FC, LFE, SL, SR; são necessários seis valores.");
        int[] samples = new int[values.length];
        for (int channel = 0; channel < values.length; channel++) {
            String value = values[channel].trim();
            if (!value.matches("[0-9]{1,5}"))
                throw new IllegalArgumentException("Atrasos precisam ser amostras inteiras não negativas.");
            samples[channel] = Integer.parseInt(value);
            if (samples[channel] > AudioProfile.MAX_DELAY_SAMPLES)
                throw new IllegalArgumentException("O atraso máximo é 12000 amostras (250 ms).");
        }
        return samples;
    }

    public static AudioProfile apply(AudioProfile original, int[] samples) {
        if (original == null || samples == null || samples.length != AudioProfile.CHANNEL_COUNT)
            throw new IllegalArgumentException("Perfil e seis atrasos são obrigatórios.");
        AudioProfile.Builder builder = original.toBuilder();
        for (int channel = 0; channel < samples.length; channel++) builder.delaySamples(channel, samples[channel]);
        return builder.build();
    }
}
