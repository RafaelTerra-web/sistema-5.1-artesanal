const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const directory = path.join(__dirname, 'browser-upmix-extensao');
const processorSource = fs.readFileSync(path.join(directory, 'source-worklet.js'), 'utf8');

function processor(originalAudioCodec = null, originalAudioChannels = 2) {
  let Type;
  const messages = [];
  const environment = vm.createContext({sampleRate: 48000,
    AudioWorkletProcessor: class {constructor() {this.port = {postMessage: message => messages.push(message)};}},
    registerProcessor(name, type) {assert.equal(name, 'sistema51-source-upmix'); Type = type;}});
  vm.runInContext(processorSource, environment);
  const instance = new Type();
  if (originalAudioCodec) instance.port.onmessage({data: {type: 'set-source', generation: 1, originalAudioCodec,
    originalAudioChannels, confirmed: true}});
  return {instance, messages};
}
function blocks(count, length = 128, value = .25) {
  return Array.from({length: count}, (_, channel) => new Float32Array(length).fill(value * (channel + 1)));
}
function output(length = 128) {return blocks(6, length, 0);}

test('decoded six-channel input stays native even with ten seconds of empty center/LFE/surrounds', () => {
  const {instance, messages} = processor();
  const input = blocks(6); input.slice(2).forEach(channel => channel.fill(0));
  for (let frame = 0; frame < 480000; frame += 128) {
    const result = output();
    assert.equal(instance.process([input], [result]), true);
    result.forEach((channel, index) => assert.deepEqual(channel, input[index]));
  }
  assert.equal(messages[0].channels, 6);
  assert.equal(messages[0].mode, 'Unknown');
  assert.ok(messages.every(message => !message.upmixed));
});

test('native passthrough preserves every float bit including signed zero and nonfinite values', () => {
  const {instance} = processor();
  const input = blocks(6, 4, 0);
  input[2].set([NaN, Infinity, -0, -.3]);
  const result = output(4);
  instance.process([input], [result]);
  result.forEach((channel, index) => assert.deepEqual(Buffer.from(channel.buffer), Buffer.from(input[index].buffer)));
});

test('selected Opus stereo generates normalized center/LFE and surrounds without changing fronts', () => {
  const {instance, messages} = processor('Opus');
  const input = blocks(2, 48000, .25), result = output(48000);
  instance.process([input], [result]);
  assert.deepEqual(result[0], input[0]); assert.deepEqual(result[1], input[1]);
  const i = 47999;
  assert.equal(result[2][i], .375);
  assert.ok(Math.abs(result[3][i] - .1875) < 1e-6);
  assert.equal(result[4][i], .125); assert.equal(result[5][i], .25);
  assert.ok(result[2][0] > 0 && result[2][0] < .001);
  assert.equal(messages[0].mode, 'Stereo');
});

test('verified mono expands its single decoded channel', () => {
  const {instance} = processor('AAC', 1), input = blocks(1, 48000), result = output(48000);
  instance.process([input], [result]);
  assert.deepEqual(result[0], input[0]); assert.deepEqual(result[1], input[0]);
  assert.equal(result[2][47999], .25);
  assert.ok(Math.abs(result[3][47999] - .125) < 1e-6);
});

test('stereo to native switches on the first decoded six-channel block with no lingering synthesized tail', () => {
  const {instance, messages} = processor('Opus');
  instance.process([blocks(2, 48000)], [output(48000)]);
  const native = blocks(6); native.slice(2).forEach(channel => channel.fill(0));
  instance.port.onmessage({data: {type: 'set-source', generation: 2, originalAudioCodec: 'Opus', originalAudioChannels: 6, confirmed: true}});
  const result = output(); instance.process([native], [result]);
  result.forEach((channel, index) => assert.deepEqual(channel, native[index]));
  assert.equal(messages.at(-1).mode, 'Native');
});

test('unknown quad layout preserves surrounds in the Web Audio speaker positions', () => {
  const {instance, messages} = processor(), input = blocks(4), result = output();
  instance.process([input], [result]);
  assert.deepEqual(result[4], input[2]); assert.deepEqual(result[5], input[3]);
  assert.ok(result[2].every(sample => sample === 0)); assert.ok(result[3].every(sample => sample === 0));
  assert.equal(messages[0].mode, 'Unknown'); assert.equal(messages[0].upmixed, false);
});

test('empty render input remains unknown and clears reusable buffers', () => {
  const {instance, messages} = processor(), result = blocks(6);
  instance.process([[]], [result]);
  assert.ok(result.every(channel => channel.every(sample => sample === 0)));
  assert.equal(messages[0].channels, 0); assert.equal(messages[0].mode, 'Unknown');
});

test('native mode disables synthesis and resets filter history', () => {
  const {instance, messages} = processor('Opus');
  instance.process([blocks(2, 48000)], [output(48000)]);
  instance.port.onmessage({data: {type: 'set-upmix', enabled: false}});
  const result = output(); instance.process([blocks(2)], [result]);
  assert.ok(result.slice(2).every(channel => channel.every(sample => sample === 0)));
  assert.equal(messages.at(-1).upmixed, false);
});

test('variable render quantum sizes produce the same stereo filter and fade state', () => {
  const a = processor('Opus').instance, b = processor('Opus').instance;
  const large = output(512); a.process([blocks(2, 512)], [large]);
  let start = 0;
  for (const length of [64, 128, 64, 256]) {
    const part = output(length); b.process([blocks(2, length)], [part]);
    part.forEach((channel, index) => assert.deepEqual(channel, large[index].slice(start, start + length)));
    start += length;
  }
});

test('manifest has no background, networking, account, cookie or history permissions', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'manifest.json'), 'utf8'));
  assert.equal(manifest.permissions, undefined); assert.equal(manifest.host_permissions, undefined);
  assert.equal(manifest.background, undefined);
  manifest.content_scripts.forEach(script => assert.deepEqual(script.matches, ['https://www.youtube.com/*']));
});

async function pageEnvironment({maxChannels = 6, mediaOrigin = 'blob:https://www.youtube.com/id', protectedMedia = false,
  moduleFailure = false, raceToForeign = false, connectionFailure = false, listenerFailure = false,
  contextSuspended = false, bootstrapOrigin = 'https://www.youtube.com'} = {}) {
  const listeners = {}, clicks = {}, videoListeners = {}, messages = [];
  const calls = {bind: 0, close: 0, addModule: 0, sourceConnect: 0, directConnect: 0};
  const video = {currentSrc: mediaOrigin, readyState: 4, mediaKeys: protectedMedia ? {} : null,
    addEventListener(type, listener) {if (listenerFailure) throw Error('simulated handler failure'); videoListeners[type] = listener;}};
  const elements = [], copied = [];
  const button = {style: {}, attributes: {}, append() {}, setAttribute(name, value) {this.attributes[name] = value;},
    addEventListener(type, fn) {clicks[type] = fn;}};
  let createdMainButton = false;
  const makeElement = type => {
    const element = type === 'button' && !createdMainButton ? button : {style: {}, attributes: {}, listeners: {}, append() {},
      setAttribute(name, value) {this.attributes[name] = value;}, addEventListener(name, fn) {this.listeners[name] = fn;}};
    if (element === button) createdMainButton = true;
    element.tagName = type; elements.push(element); return element;
  };
  const intervals = [], documentListeners = {}, playerListeners = {}, sourceMessages = [];
  const player = {querySelector: () => video,
    addEventListener(type, fn) {playerListeners[type] = fn;},
    getStatsForNerds: () => ({video_id: 'sample', afmt: 251, codecs: 'avc1.640028 (137) / opus (251)'}),
    getPlayerResponse: () => ({videoDetails: {videoId: 'sample'}, streamingData: {
      adaptiveFormats: [{itag: 251, mimeType: 'audio/webm; codecs="opus"', audioChannels: 2}]}}),
    getVideoData: () => ({video_id: 'sample'})};
  let node;
  class Context {
    constructor() {this.sampleRate = 48000; this.state = contextSuspended ? 'suspended' : 'running'; this.destination = {maxChannelCount: maxChannels};
      this.audioWorklet = {addModule: async () => {calls.addModule++; if (moduleFailure) throw Error('simulated module failure');
        if (raceToForeign) video.currentSrc = 'https://foreign.example/audio';}};}
    async resume() {}
    async close() {calls.close++;}
    createMediaElementSource() {calls.bind++; return {connect(target) {calls.sourceConnect++; if (target === node && connectionFailure)
      throw Error('simulated source connection failure'); if (target === this.destination) calls.directConnect++;},
      destination: this.destination, disconnect() {}};}
  }
  const environment = vm.createContext({URL, crypto: {randomUUID: () => 'test-session'},
    location: {origin: 'https://www.youtube.com', href: 'https://www.youtube.com/watch?v=sample'},
    AudioContext: Context, AudioWorkletNode: class {constructor() {node = this; this.port = {postMessage(message) {sourceMessages.push(message);}};} connect() {} disconnect() {}},
    navigator: {clipboard: {async writeText(value) {copied.push(value);}}},
    document: {createElement: makeElement, getElementById: () => player,
      addEventListener(type, fn) {documentListeners[type] = fn;},
      dispatchEvent(event) {documentListeners[event.type]?.(event); return true;},
      documentElement: {append() {}, setAttribute() {}}},
    setInterval(fn) {intervals.push(fn);},
    addEventListener(type, listener) {listeners[type] = listener;}, postMessage(message) {messages.push(message);}});
  environment.window = environment; environment.top = environment;
  vm.runInContext(fs.readFileSync(path.join(directory, 'page.js'), 'utf8'), environment);
  vm.runInContext(`globalThis.bootstrap = function(origin, url) {
    __listeners.message({source: window, origin, data: {type: 'sistema51-source-bootstrap', version: 1, workletUrl: url}});
  }`, Object.assign(environment, {__listeners: listeners}));
  environment.bootstrap(bootstrapOrigin, 'chrome-extension://' + 'a'.repeat(32) + '/source-worklet.js');
  await clicks.click();
  return {calls, messages, button, node, video, videoListeners, player, playerListeners, documentListeners,
    sourceMessages, environment, elements, copied, tick: () => intervals.forEach(fn => fn()), click: clicks.click};
}

test('stereo destination is rejected before binding the media element', async () => {
  const {calls} = await pageEnvironment({maxChannels: 2});
  assert.equal(calls.bind, 0); assert.equal(calls.close, 1);
});

test('same-origin blob binds only after module and multichannel preflight', async () => {
  const {calls} = await pageEnvironment();
  assert.equal(calls.addModule, 1); assert.equal(calls.bind, 1);
});

test('cross-origin and protected media are rejected without touching their audio route', async () => {
  for (const options of [{mediaOrigin: 'https://foreign.example/audio'}, {protectedMedia: true}]) {
    const {calls} = await pageEnvironment(options); assert.equal(calls.bind, 0); assert.equal(calls.addModule, 0);
  }
});

test('module errors and suspended contexts do not bind or mute the original video', async () => {
  for (const options of [{moduleFailure: true}, {contextSuspended: true}]) {
    const {calls} = await pageEnvironment(options);
    assert.equal(calls.bind, 0); assert.equal(calls.close, 1);
  }
});

test('resource changes during asynchronous setup are revalidated before media binding', async () => {
  const {calls} = await pageEnvironment({raceToForeign: true});
  assert.equal(calls.bind, 0); assert.equal(calls.close, 1);
});

test('unexpected setup failure after binding always connects the source directly', async () => {
  for (const options of [{connectionFailure: true}, {listenerFailure: true}]) {
    const {calls} = await pageEnvironment(options);
    assert.equal(calls.bind, 1); assert.equal(calls.directConnect, 1);
  }
});

test('a failed processor uses direct audio and cannot be accidentally reused by a second click', async () => {
  const {calls, node, click, messages} = await pageEnvironment();
  node.onprocessorerror();
  assert.equal(calls.directConnect, 1);
  await click();
  assert.equal(calls.bind, 1); assert.equal(calls.directConnect, 1);
  assert.equal(messages.at(-1).active, true);
  assert.equal(messages.at(-2).mode, 'Unknown');
});

test('protection changes after binding request F5 and never claim native 5.1 validation', async () => {
  const {video, videoListeners, messages, calls} = await pageEnvironment();
  video.mediaKeys = {};
  videoListeners.loadedmetadata();
  assert.equal(calls.directConnect, 1);
  assert.equal(messages.at(-1).mode, 'Unknown');
  assert.equal(messages.at(-1).upmixed, false);
});

test('foreign-origin bootstrap messages cannot authorize a worklet module', async () => {
  const {calls} = await pageEnvironment({bootstrapOrigin: 'https://foreign.example'});
  assert.equal(calls.addModule, 0); assert.equal(calls.bind, 0);
});

test('new media resets stereo filter history without changing native channel selection', () => {
  const {instance, messages} = processor('Opus');
  instance.process([blocks(2, 48000)], [output(48000)]);
  instance.port.onmessage({data: {type: 'source-reset'}});
  const silence = output(); instance.process([blocks(2, 128, 0)], [silence]);
  assert.ok(silence.every(channel => channel.every(sample => sample === 0)));
  assert.equal(messages.at(-1).channels, 2);
  assert.equal(messages.at(-1).originalAudioCodec, 'Unknown');
  assert.equal(messages.at(-1).upmixed, false);
});

test('AC3, EAC3 and unknown codecs preserve every stereo sample and never synthesize', () => {
  for (const codec of ['AC3', 'EAC3', 'Unknown']) {
    const {instance, messages} = processor(codec);
    const input = blocks(2), result = blocks(6);
    instance.process([input], [result]);
    assert.deepEqual(result[0], input[0]); assert.deepEqual(result[1], input[1]);
    assert.ok(result.slice(2).every(channel => channel.every(sample => sample === 0)));
    assert.equal(messages.at(-1).upmixed, false);
    assert.equal(messages.at(-1).blockedReason, codec === 'Unknown' ? 'codec-unconfirmed' : 'dolby-protected');
  }
});

test('known non-Dolby codec still preserves six channels bit for bit', () => {
  const {instance, messages} = processor('Opus', 6);
  const input = blocks(6), result = output();
  instance.process([input], [result]);
  result.forEach((channel, index) => assert.deepEqual(channel, input[index]));
  assert.equal(messages.at(-1).upmixed, false);
});

test('a new generation immediately revokes Opus synthesis and rejects stale codec confirmations', () => {
  const {instance, messages} = processor('Opus');
  instance.process([blocks(2)], [output()]);
  assert.equal(messages.at(-1).upmixed, true);
  instance.port.onmessage({data: {type: 'set-source', generation: 2, originalAudioCodec: 'Unknown', confirmed: false}});
  instance.port.onmessage({data: {type: 'set-source', generation: 1, originalAudioCodec: 'Opus', confirmed: true}});
  const result = output(); instance.process([blocks(2)], [result]);
  assert.ok(result.slice(2).every(channel => channel.every(sample => sample === 0)));
  assert.equal(messages.at(-1).mediaGeneration, 2);
  assert.equal(messages.at(-1).originalAudioCodec, 'Unknown');
});

test('codec permission expires when selected source evidence stops arriving', () => {
  const {instance, messages} = processor('Opus');
  instance.process([blocks(2, 48000)], [output(48000)]);
  const result = output(); instance.process([blocks(2)], [result]);
  assert.ok(result.slice(2).every(channel => channel.every(sample => sample === 0)));
  assert.equal(messages.at(-1).upmixed, false);
});

test('explicit original multichannel Opus metadata vetoes synthesis from decoded stereo', () => {
  const {instance, messages} = processor('Opus', 6);
  const input = blocks(2), result = output();
  instance.process([input], [result]);
  assert.deepEqual(result[0], input[0]); assert.deepEqual(result[1], input[1]);
  assert.ok(result.slice(2).every(channel => channel.every(sample => sample === 0)));
  assert.equal(messages.at(-1).upmixed, false);
  assert.equal(messages.at(-1).blockedReason, 'original-multichannel');
});

test('known Opus with no original channel metadata uses decoded stereo before the Windows mixer', () => {
  const {instance, messages} = processor('Opus', null);
  const result = output(); instance.process([blocks(2)], [result]);
  assert.equal(messages.at(-1).upmixed, true);
  assert.ok(result[2].some(sample => sample !== 0));
});

test('page reports codec separately from missing original channel information', async () => {
  const {player, sourceMessages, tick} = await pageEnvironment();
  player.getPlayerResponse = () => ({videoDetails: {videoId: 'sample'}, streamingData: {
    adaptiveFormats: [{itag: 251, mimeType: 'audio/webm; codecs="opus"'}]}});
  tick(); tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Opus');
  assert.equal(sourceMessages.at(-1).originalAudioChannels, null);
});

test('page starts native and authorizes only the selected format after a second observation', async () => {
  const {sourceMessages, tick} = await pageEnvironment();
  assert.ok(sourceMessages.length > 0);
  assert.ok(sourceMessages.every(message => message.originalAudioCodec === 'Unknown' && !message.confirmed));
  tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Opus');
  assert.equal(sourceMessages.at(-1).confirmed, true);
});

test('an offered Opus format without selected audio evidence cannot authorize upmix', async () => {
  const {player, sourceMessages, tick} = await pageEnvironment();
  player.getStatsForNerds = () => ({video_id: 'sample'});
  tick(); tick();
  assert.ok(sourceMessages.every(message => !message.confirmed));
});

test('page classifies AC3 and EAC3 selected streams even when their format declares two channels', async () => {
  for (const [token, expected] of [['ac-3', 'AC3'], ['ec-3', 'EAC3'], ['mp4a.a5', 'AC3'], ['mp4a.a6', 'EAC3']]) {
    const {player, sourceMessages, tick} = await pageEnvironment();
    player.getStatsForNerds = () => ({video_id: 'sample', afmt: 999, codecs: `avc1.640028 (137) / ${token} (999)`});
    player.getPlayerResponse = () => ({videoDetails: {videoId: 'sample'}, streamingData: {
      adaptiveFormats: [{itag: 999, mimeType: `audio/mp4; codecs="${token}"`, audioChannels: 2}]}});
    tick(); tick();
    assert.equal(sourceMessages.at(-1).originalAudioCodec, expected);
    assert.equal(sourceMessages.at(-1).confirmed, true);
  }
});

test('navigation invalidates codec before source change and refuses old video evidence', async () => {
  const {environment, sourceMessages, tick, documentListeners} = await pageEnvironment();
  tick(); assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Opus');
  documentListeners['yt-navigate-start']();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
  const generation = sourceMessages.at(-1).generation;
  environment.location.href = 'https://www.youtube.com/watch?v=next';
  documentListeners['yt-navigate-finish'](); tick(); tick();
  assert.equal(sourceMessages.at(-1).generation, generation);
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
});

test('track changes immediately revoke the codec and ambiguous same-itag tracks stay unknown', async () => {
  const {sourceMessages, tick, player, playerListeners} = await pageEnvironment();
  tick();
  playerListeners.onAudioTrackChanged();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
  player.getPlayerResponse = () => ({videoDetails: {videoId: 'sample'}, streamingData: {adaptiveFormats: [
    {itag: 251, mimeType: 'audio/webm; codecs="opus"', audioTrack: {id: 'a'}},
    {itag: 251, mimeType: 'audio/mp4; codecs="ec-3"', audioTrack: {id: 'b'}}]}});
  tick(); tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
});

test('a second activation revokes known codec before allowing the resumed graph to synthesize', async () => {
  const {sourceMessages, tick, click} = await pageEnvironment();
  tick();
  const start = sourceMessages.length;
  await click();
  assert.equal(sourceMessages[start].type, 'set-source');
  assert.equal(sourceMessages[start].originalAudioCodec, 'Unknown');
  assert.equal(sourceMessages[start].confirmed, false);
  assert.equal(sourceMessages[start + 1].type, 'set-upmix');
});

test('conflicting selected codec, unknown MIME codec and malformed private stats preserve the source', async () => {
  for (const codecStats of ['avc1.640028 (137) / ac-3 (251)', 'unrecognized format']) {
    const {player, sourceMessages, tick} = await pageEnvironment();
    player.getStatsForNerds = () => ({video_id: 'sample', afmt: 251, codecs: codecStats});
    tick(); tick();
    assert.ok(sourceMessages.every(message => !message.confirmed));
  }
  const {player, sourceMessages, tick} = await pageEnvironment();
  player.getPlayerResponse = () => ({videoDetails: {videoId: 'sample'}, streamingData: {
    adaptiveFormats: [{itag: 251, mimeType: 'audio/mp4; codecs="mp4a"', audioChannels: 2}]}});
  tick(); tick();
  assert.ok(sourceMessages.every(message => !message.confirmed));
});

test('page status explains Dolby preservation without claiming a stereo source is native 5.1', async () => {
  const {node, sourceMessages, messages, button} = await pageEnvironment();
  node.port.onmessage({data: {type: 'source-channels', version: 1, channels: 2, mode: 'Unknown', upmixed: false,
    originalAudioCodec: 'AC3', codecConfirmed: true, originalAudioChannels: 2, blockedReason: 'dolby-protected',
    sampleRate: 48000, mediaGeneration: sourceMessages.at(-1).generation}});
  assert.match(button.textContent, /AC3 preservado/);
  assert.equal(messages.at(-1).blockedReason, 'dolby-protected');
  assert.equal(messages.at(-1).channels, 2);
  assert.equal(messages.at(-1).upmixed, false);
});

test('changed same-element resource clears old proof and leaves the first observation native', async () => {
  const {sourceMessages, tick, video, videoListeners} = await pageEnvironment();
  tick();
  video.currentSrc = 'blob:https://www.youtube.com/next-resource';
  videoListeners.loadstart();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
  tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
  tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Opus');
});

function installCapture(environment = null) {
  const changes = [], nativeCalls = {add: 0, change: 0, url: 0}, failure = Error('native failure');
  class Events {
    constructor() {this.listeners = {};}
    addEventListener(type, fn) {(this.listeners[type] ||= []).push(fn);}
    emit(type) {for (const listener of this.listeners[type] || []) listener({type});}
  }
  class List extends Array {
    constructor() {super(); this.events = new Events();}
    addEventListener(type, fn) {this.events.addEventListener(type, fn);}
    emit(type) {this.events.emit(type);}
  }
  class SourceBuffer extends Events {
    constructor(mime) {super(); this.nativeMime = mime;}
    changeType(mime) {
      if (!(this instanceof SourceBuffer)) throw failure;
      nativeCalls.change++;
      const value = String(mime);
      if (value === 'fail') throw failure;
      this.nativeMime = value;
      return undefined;
    }
  }
  class MediaSource extends Events {
    constructor() {super(); this.readyState = 'open'; this.sourceBuffers = new List(); this.activeSourceBuffers = new List();}
    addSourceBuffer(mime) {
      if (!(this instanceof MediaSource)) throw failure;
      nativeCalls.add++;
      const value = String(mime);
      if (value === 'fail') throw failure;
      const result = new SourceBuffer(value);
      this.sourceBuffers.push(result); this.sourceBuffers.emit('addsourcebuffer');
      this.activeSourceBuffers.push(result); this.activeSourceBuffers.emit('addsourcebuffer');
      return result;
    }
    removeSourceBuffer(buffer) {
      if (!(this instanceof MediaSource)) throw failure;
      if (!this.sourceBuffers.includes(buffer)) throw failure;
      this.sourceBuffers.splice(this.sourceBuffers.indexOf(buffer), 1); this.sourceBuffers.emit('removesourcebuffer');
      const index = this.activeSourceBuffers.indexOf(buffer);
      if (index >= 0) {this.activeSourceBuffers.splice(index, 1); this.activeSourceBuffers.emit('removesourcebuffer');}
      return undefined;
    }
    setActive(buffers) {
      this.activeSourceBuffers.splice(0, this.activeSourceBuffers.length, ...buffers);
      this.activeSourceBuffers.emit('addsourcebuffer');
    }
  }
  let serial = 0;
  class CaptureURL extends URL {
    static createObjectURL(value) {
      if (!value) throw failure;
      nativeCalls.url++;
      return `blob:https://www.youtube.com/mse-${++serial}`;
    }
  }
  const native = {add: MediaSource.prototype.addSourceBuffer, change: SourceBuffer.prototype.changeType,
    remove: MediaSource.prototype.removeSourceBuffer, url: CaptureURL.createObjectURL};
  if (!environment) {
    environment = vm.createContext({location: {origin: 'https://www.youtube.com'},
      document: {dispatchEvent(event) {changes.push(event.type);}}});
    environment.window = environment; environment.top = environment;
  }
  Object.assign(environment, {MediaSource, SourceBuffer, URL: CaptureURL, CustomEvent: class {constructor(type) {this.type = type;}}});
  vm.runInContext(fs.readFileSync(path.join(directory, 'codec-capture.js'), 'utf8'), environment);
  return {environment, capture: environment.__sistema51SourceCodecCapture, MediaSource, SourceBuffer, URL: CaptureURL,
    native, nativeCalls, failure, changes};
}

test('MSE hooks preserve native signatures, result objects, argument coercion and thrown error identity', () => {
  const {MediaSource, SourceBuffer, URL, native, failure, nativeCalls, capture} = installCapture();
  for (const [wrapped, original] of [[MediaSource.prototype.addSourceBuffer, native.add],
    [SourceBuffer.prototype.changeType, native.change], [URL.createObjectURL, native.url]]) {
    assert.equal(wrapped.name, original.name); assert.equal(wrapped.length, original.length);
    assert.throws(() => new wrapped(), TypeError);
  }
  const source = new MediaSource(), url = URL.createObjectURL(source);
  const buffer = source.addSourceBuffer('audio/webm; codecs="opus"');
  assert.ok(buffer instanceof SourceBuffer);
  assert.equal(buffer.changeType('audio/webm; codecs="opus"'), undefined);
  assert.throws(() => source.addSourceBuffer('fail'), error => error === failure);
  assert.throws(() => buffer.changeType('fail'), error => error === failure);
  assert.throws(() => MediaSource.prototype.addSourceBuffer.call({}, 'audio/webm; codecs="opus"'), error => error === failure);
  assert.equal(capture.read({currentSrc: url}).buffers[0].mimes.length, 1);
  let conversions = 0;
  const unrecorded = source.addSourceBuffer({toString() {conversions++; return 'audio/mp4; codecs="ac-3"';}});
  assert.equal(conversions, 1);
  assert.equal(nativeCalls.add, 3); assert.equal(nativeCalls.change, 2);
  source.setActive([unrecorded]);
  assert.equal(capture.read({currentSrc: url}).unknown, true);
});

test('MSE capture associates the exact blob and inspects only its active buffers', () => {
  const {MediaSource, URL, capture} = installCapture();
  const a = new MediaSource(), b = new MediaSource();
  const first = URL.createObjectURL(a), second = URL.createObjectURL(b);
  const opus = a.addSourceBuffer('audio/webm; codecs="opus"');
  a.addSourceBuffer('audio/mp4; codecs="ec-3"'); a.setActive([opus]);
  b.addSourceBuffer('audio/mp4; codecs="ac-3"');
  const evidence = capture.read({currentSrc: first});
  assert.equal(evidence.buffers.length, 1);
  assert.equal(evidence.buffers[0].mimes[0], 'audio/webm; codecs="opus"');
  assert.equal(capture.read({currentSrc: second}).buffers[0].mimes[0], 'audio/mp4; codecs="ac-3"');
  assert.notEqual(evidence.identity, capture.read({currentSrc: second}).identity);
  assert.equal(capture.read({currentSrc: URL.createObjectURL({})}), null);
  assert.equal(capture.read({currentSrc: first, mediaKeys: {}}), null);
  assert.equal(capture.read({currentSrc: first, srcObject: {}}), null);
  a.readyState = 'closed'; a.emit('sourceclose');
  assert.equal(capture.read({currentSrc: first}), null);
});

test('MSE capture has priority and confirms unquoted Opus MIME without private player APIs', async () => {
  const {environment, video, player, sourceMessages, tick} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  player.getStatsForNerds = undefined; player.getPlayerResponse = undefined;
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  source.addSourceBuffer('video/mp4; codecs="avc1.640028"');
  source.addSourceBuffer('audio/webm; codecs=opus');
  tick(); assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
  tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Opus');
  assert.equal(sourceMessages.at(-1).codecEvidence, 'mse-active-source-buffer');
  assert.equal(sourceMessages.at(-1).confirmed, true);
});

test('MSE selected Dolby buffer overrides an inactive Opus offering and is preserved by the worklet', async () => {
  const {environment, video, sourceMessages, tick} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  source.addSourceBuffer('audio/webm; codecs="opus"');
  const dolby = source.addSourceBuffer('audio/mp4; codecs="ec-3"'); source.setActive([dolby]);
  tick(); tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'EAC3');
  const {instance, messages} = processor();
  instance.port.onmessage({data: sourceMessages.at(-1)});
  const input = blocks(2), result = output(); instance.process([input], [result]);
  assert.equal(messages.at(-1).blockedReason, 'dolby-protected');
  assert.deepEqual(result[0], input[0]); assert.deepEqual(result[1], input[1]);
  assert.ok(result.slice(2).every(channel => channel.every(sample => sample === 0)));
});

test('ambiguous active MSE audio buffers cannot be overridden by private Opus stats', async () => {
  const {environment, video, sourceMessages, tick} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  source.addSourceBuffer('audio/webm; codecs="opus"');
  source.addSourceBuffer('audio/mp4; codecs="ac-3"');
  tick(); tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
  assert.equal(sourceMessages.at(-1).confirmed, false);
});

test('MSE changeType immediately revokes old proof and mixed Dolby/Opus history remains unknown', async () => {
  const {environment, video, sourceMessages, tick} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  const audio = source.addSourceBuffer('audio/mp4; codecs="ac-3"');
  tick(); tick(); assert.equal(sourceMessages.at(-1).originalAudioCodec, 'AC3');
  audio.changeType('audio/webm; codecs="opus"');
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
  tick(); tick(); assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Unknown');
  source.removeSourceBuffer(audio); source.addSourceBuffer('audio/webm; codecs="opus"');
  tick(); tick(); assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Opus');
});

test('MSE video quality changes retain a single audio codec, and explicit MIME channels veto downmixed source', async () => {
  const {environment, video, sourceMessages, tick} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  const picture = source.addSourceBuffer('video/mp4; codecs="avc1.640028"');
  source.addSourceBuffer('audio/webm; codecs="opus"; channels=6');
  tick(); tick();
  picture.changeType('video/mp4; codecs="avc1.64002a"');
  tick(); tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Opus');
  assert.equal(sourceMessages.at(-1).originalAudioChannels, 6);
  const {instance, messages} = processor(); instance.port.onmessage({data: sourceMessages.at(-1)});
  instance.process([blocks(2)], [output()]);
  assert.equal(messages.at(-1).blockedReason, 'original-multichannel');
});

test('capture script runs in MAIN at document_start without adding permissions', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'manifest.json'), 'utf8'));
  const capture = manifest.content_scripts.find(script => script.js.includes('codec-capture.js'));
  assert.equal(capture.world, 'MAIN'); assert.equal(capture.run_at, 'document_start');
  assert.equal(manifest.permissions, undefined); assert.equal(manifest.host_permissions, undefined);
});

test('confirmed Opus stereo in six decoded slots synthesizes only from FL/FR, regardless of surplus energy', () => {
  const {instance, messages} = processor('Opus', 2);
  const input = blocks(6, 48000), result = output(48000);
  instance.process([input], [result]);
  assert.deepEqual(result[0], input[0]); assert.deepEqual(result[1], input[1]);
  assert.equal(result[2][47999], .375);
  assert.equal(result[4][47999], .125); assert.equal(result[5][47999], .25);
  assert.equal(messages.at(-1).channels, 6);
  assert.equal(messages.at(-1).upmixSourceChannels, 2);
  assert.equal(messages.at(-1).upmixed, true);
});

test('confirmed mono in six decoded slots uses its first original channel only', () => {
  const {instance, messages} = processor('AAC', 1);
  const input = blocks(6, 48000), result = output(48000);
  instance.process([input], [result]);
  assert.deepEqual(result[0], input[0]); assert.deepEqual(result[1], input[0]);
  assert.equal(result[2][47999], .25);
  assert.equal(result[4][47999], .125); assert.equal(result[5][47999], .125);
  assert.equal(messages.at(-1).upmixSourceChannels, 1);
});

test('six decoded slots with unknown original layout or Dolby stereo stay byte-preserved', () => {
  for (const [codec, original] of [['Opus', null], ['Unknown', 2], ['AC3', 2], ['EAC3', 2], ['Opus', 6]]) {
    const {instance, messages} = processor(codec, original);
    const input = blocks(6); input.slice(2).forEach(channel => channel.fill(0));
    const result = output(); instance.process([input], [result]);
    result.forEach((channel, index) => assert.deepEqual(Buffer.from(channel.buffer), Buffer.from(input[index].buffer)));
    assert.equal(messages.at(-1).upmixed, false);
    assert.equal(messages.at(-1).mode, original === 6 ? 'Native' : 'Unknown');
  }
});

test('padded stereo authorization is revoked before source reset or stale previous-generation channel metadata', () => {
  const {instance, messages} = processor('Opus', 2);
  instance.process([blocks(6)], [output()]);
  assert.equal(messages.at(-1).upmixed, true);
  instance.port.onmessage({data: {type: 'set-source', generation: 2, originalAudioCodec: 'Unknown', confirmed: false}});
  instance.port.onmessage({data: {type: 'set-source', generation: 1, originalAudioCodec: 'Opus', originalAudioChannels: 2, confirmed: true}});
  const input = blocks(6), result = output(); instance.process([input], [result]);
  result.forEach((channel, index) => assert.deepEqual(channel, input[index]));
  assert.equal(messages.at(-1).upmixed, false);
});

test('exact selected player stereo channels corroborate the current MSE codec and authorize padded stereo', async () => {
  const {environment, video, sourceMessages, tick} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  source.addSourceBuffer('audio/webm; codecs="opus"');
  tick(); tick();
  const selected = sourceMessages.at(-1);
  assert.equal(selected.originalAudioChannels, 2);
  assert.equal(selected.codecEvidence, 'mse-active-source-buffer');
  const {instance, messages} = processor(); instance.port.onmessage({data: selected});
  instance.process([blocks(6)], [output()]);
  assert.equal(messages.at(-1).upmixed, true);
});

test('offered player stereo channels without current selected identity cannot fill MSE original layout', async () => {
  const {environment, video, sourceMessages, tick, player} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  source.addSourceBuffer('audio/webm; codecs="opus"');
  player.getStatsForNerds = () => ({video_id: 'old', afmt: 251, codecs: 'avc1.640028 (137) / opus (251)'});
  tick(); tick();
  assert.equal(sourceMessages.at(-1).originalAudioCodec, 'Opus');
  assert.equal(sourceMessages.at(-1).originalAudioChannels, null);
  const {instance, messages} = processor(); instance.port.onmessage({data: sourceMessages.at(-1)});
  instance.process([blocks(6)], [output()]); assert.equal(messages.at(-1).upmixed, false);
});

test('MSE stereo-to-unspecified channel history revokes padded permission and cannot refill from private stats', async () => {
  const {environment, video, sourceMessages, tick} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  const buffer = source.addSourceBuffer('audio/webm; codecs="opus"; channels=2');
  tick(); tick(); assert.equal(sourceMessages.at(-1).originalAudioChannels, 2);
  buffer.changeType('audio/webm; codecs="opus"');
  tick(); tick(); assert.equal(sourceMessages.at(-1).originalAudioChannels, null);
  const {instance, messages} = processor(); instance.port.onmessage({data: sourceMessages.at(-1)});
  instance.process([blocks(6)], [output()]); assert.equal(messages.at(-1).upmixed, false);
});

test('explicit selected multichannel MIME vetoes conflicting stereo metadata, and conflicting track IDs stay unknown', async () => {
  const {environment, video, sourceMessages, tick, player} = await pageEnvironment();
  const {MediaSource, URL} = installCapture(environment);
  const source = new MediaSource(); video.currentSrc = URL.createObjectURL(source);
  source.addSourceBuffer('audio/webm; codecs="opus"');
  player.getPlayerResponse = () => ({videoDetails: {videoId: 'sample'}, streamingData: {adaptiveFormats: [
    {itag: 251, mimeType: 'audio/webm; codecs="opus"; channels=6', audioChannels: 2}]}});
  tick(); tick(); assert.equal(sourceMessages.at(-1).originalAudioChannels, 6);
  player.getStatsForNerds = () => ({video_id: 'sample', afmt: 251, audio_track_id: 'a', codecs: 'avc1.640028 (137) / opus (251)'});
  player.getAudioTrack = () => ({id: 'b'});
  tick(); tick(); assert.equal(sourceMessages.at(-1).originalAudioChannels, null);
});

test('visible and copied diagnostics distinguish source stereo from six PCM slots without leaking media URLs', async () => {
  const {node, sourceMessages, elements, copied, button, tick} = await pageEnvironment();
  tick();
  const value = {type: 'source-channels', version: 1, channels: 6, mode: 'Stereo', upmixed: true,
    originalAudioCodec: 'Opus', codecConfirmed: true, originalAudioChannels: 2, upmixSourceChannels: 2,
    blockedReason: null, sampleRate: 48000, mediaGeneration: sourceMessages.at(-1).generation};
  node.port.onmessage({data: value});
  assert.match(button.textContent, /^Upmix 2/);
  const caption = elements.find(element => element.attributes.role === 'status');
  assert.match(caption.textContent, /Origem: 2 canais \| PCM do navegador: 6 canais/);
  assert.match(caption.textContent, /canais: youtube-selected-format/);
  const copyButton = elements.find(element => element.textContent === 'Copiar diagnóstico');
  await copyButton.listeners.click();
  assert.equal(copied[0], caption.textContent);
  assert.doesNotMatch(copied[0], /https?:|blob:|sample|test-session/);
  node.port.onmessage({data: {...value, upmixed: false, blockedReason: 'upmix-disabled'}});
  assert.match(button.textContent, /Upmix desligado/);
  assert.doesNotMatch(button.textContent, /origem não confirmada/);
  node.port.onmessage({data: {...value, upmixed: false, originalAudioChannels: null, blockedReason: 'source-layout-unconfirmed'}});
  assert.match(button.textContent, /origem não confirmada/);
  assert.doesNotMatch(button.textContent, /nativo|5\.1 da origem/);
});
