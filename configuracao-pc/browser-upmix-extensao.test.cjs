const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const directory = path.join(__dirname, 'browser-upmix-extensao');
const processorSource = fs.readFileSync(path.join(directory, 'source-worklet.js'), 'utf8');

function processor() {
  let Type;
  const messages = [];
  const environment = vm.createContext({sampleRate: 48000,
    AudioWorkletProcessor: class {constructor() {this.port = {postMessage: message => messages.push(message)};}},
    registerProcessor(name, type) {assert.equal(name, 'sistema51-source-upmix'); Type = type;}});
  vm.runInContext(processorSource, environment);
  return {instance: new Type(), messages};
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
  assert.equal(messages[0].mode, 'Native');
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

test('verified stereo generates normalized center/LFE and surrounds without changing fronts', () => {
  const {instance, messages} = processor();
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
  const {instance} = processor(), input = blocks(1, 48000), result = output(48000);
  instance.process([input], [result]);
  assert.deepEqual(result[0], input[0]); assert.deepEqual(result[1], input[0]);
  assert.equal(result[2][47999], .25);
  assert.ok(Math.abs(result[3][47999] - .125) < 1e-6);
});

test('stereo to native switches on the first decoded six-channel block with no lingering synthesized tail', () => {
  const {instance, messages} = processor();
  instance.process([blocks(2, 48000)], [output(48000)]);
  const native = blocks(6); native.slice(2).forEach(channel => channel.fill(0));
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
  const {instance, messages} = processor();
  instance.process([blocks(2, 48000)], [output(48000)]);
  instance.port.onmessage({data: {type: 'set-upmix', enabled: false}});
  const result = output(); instance.process([blocks(2)], [result]);
  assert.ok(result.slice(2).every(channel => channel.every(sample => sample === 0)));
  assert.equal(messages.at(-1).upmixed, false);
});

test('variable render quantum sizes produce the same stereo filter and fade state', () => {
  const a = processor().instance, b = processor().instance;
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
  const button = {style: {}, addEventListener(type, fn) {clicks[type] = fn;}};
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
    AudioContext: Context, AudioWorkletNode: class {constructor() {node = this; this.port = {postMessage() {}};} connect() {} disconnect() {}},
    document: {createElement: () => button, getElementById: () => ({querySelector: () => video}),
      documentElement: {append() {}, setAttribute() {}}},
    addEventListener(type, listener) {listeners[type] = listener;}, postMessage(message) {messages.push(message);}});
  environment.window = environment; environment.top = environment;
  vm.runInContext(fs.readFileSync(path.join(directory, 'page.js'), 'utf8'), environment);
  vm.runInContext(`globalThis.bootstrap = function(origin, url) {
    __listeners.message({source: window, origin, data: {type: 'sistema51-source-bootstrap', version: 1, workletUrl: url}});
  }`, Object.assign(environment, {__listeners: listeners}));
  environment.bootstrap(bootstrapOrigin, 'chrome-extension://' + 'a'.repeat(32) + '/source-worklet.js');
  await clicks.click();
  return {calls, messages, button, node, video, videoListeners, click: clicks.click};
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
  const {instance, messages} = processor();
  instance.process([blocks(2, 48000)], [output(48000)]);
  instance.port.onmessage({data: {type: 'source-reset'}});
  const silence = output(); instance.process([blocks(2, 128, 0)], [silence]);
  assert.ok(silence.every(channel => channel.every(sample => sample === 0)));
  assert.equal(messages.at(-1).channels, 2);
});
