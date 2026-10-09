/* Decoded input channel count comes from the browser's render topology.
 * channelCountMode=max and discrete interpretation are required on the node.
 * Never infer source layout from codec, itag, gain or quiet sample values.
 */
class Sistema51SourceProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.enabled = true;
    this.channels = -1;
    this.fade = 0;
    this.framesUntilReport = 0;
    this.z11 = this.z12 = this.z21 = this.z22 = 0;
    const omega = 2 * Math.PI * 120 / sampleRate;
    const cosine = Math.cos(omega), alpha = Math.sin(omega) / (2 * Math.SQRT1_2), a0 = 1 + alpha;
    this.b0 = (1 - cosine) / (2 * a0);
    this.b1 = (1 - cosine) / a0;
    this.b2 = this.b0;
    this.a1 = -2 * cosine / a0;
    this.a2 = (1 - alpha) / a0;
    this.port.onmessage = event => {
      if (event.data?.type === 'source-reset') {
        this.reset();
        this.framesUntilReport = 0;
        return;
      }
      if (event.data?.type !== 'set-upmix' || typeof event.data.enabled !== 'boolean') return;
      this.enabled = event.data.enabled;
      this.reset();
      this.framesUntilReport = 0;
    };
  }

  reset() {
    this.fade = 0;
    this.z11 = this.z12 = this.z21 = this.z22 = 0;
  }

  lowpass(input) {
    const first = this.b0 * input + this.z11;
    this.z11 = this.b1 * input - this.a1 * first + this.z12;
    this.z12 = this.b2 * input - this.a2 * first;
    const second = this.b0 * first + this.z21;
    this.z21 = this.b1 * first - this.a1 * second + this.z22;
    this.z22 = this.b2 * first - this.a2 * second;
    return second;
  }

  process(inputs, outputs) {
    const input = inputs[0] || [], output = outputs[0] || [];
    const count = input.length, length = output[0]?.length || 0;
    if (count !== this.channels) {
      this.channels = count;
      this.reset();
      this.framesUntilReport = 0;
    }
    const synthesize = this.enabled && output.length >= 6 && (count === 1 || count === 2);
    if (this.framesUntilReport <= 0) {
      this.port.postMessage({type: 'source-channels', version: 1, channels: count,
        mode: synthesize ? 'Stereo' : count === 6 ? 'Native' : 'Unknown',
        upmixed: synthesize, preserved: !synthesize && count <= output.length,
        method: 'decoded-worklet-input', sampleRate});
      this.framesUntilReport = sampleRate;
    }
    this.framesUntilReport -= length;
    // Explicitly clear reusable buffers, including surplus output slots.
    for (let channel = 0; channel < output.length; channel++) output[channel].fill(0);
    if (!synthesize) {
      if (count === 4 && output.length >= 6) {
        // Web Audio quad order is FL FR SL SR; preserve these positions while
        // the page returns unsupported layouts to the direct speaker route.
        output[0].set(input[0]); output[1].set(input[1]);
        output[4].set(input[2]); output[5].set(input[3]);
      } else for (let channel = 0; channel < Math.min(count, output.length); channel++)
        output[channel].set(input[channel]);
      return true;
    }
    const left = input[0], right = count === 1 ? left : input[1];
    output[0].set(left);
    output[1].set(right);
    for (let frame = 0; frame < length; frame++) {
      const l = left[frame], r = right[frame];
      const mono = Number.isFinite(l) && Number.isFinite(r) ? .25 * (l + r) : 0;
      const filtered = this.lowpass(mono);
      this.fade = Math.min(this.fade + 1, sampleRate / 10);
      const gain = this.fade / (sampleRate / 10);
      output[2][frame] = .5 * (l + r) * gain;
      output[3][frame] = filtered * gain;
      output[4][frame] = .5 * l * gain;
      output[5][frame] = .5 * r * gain;
    }
    return true;
  }
}
registerProcessor('sistema51-source-upmix', Sistema51SourceProcessor);
