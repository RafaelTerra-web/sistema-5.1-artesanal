const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, 'youtube-dolby51-extensao', 'audio51.js'), 'utf8');

function environment(existingConfig) {
  const timers = [];
  const calls = [];
  const document = {
    addEventListener() {}, documentElement: {setAttribute() {}},
    getElementById() { return {setUserAudio51Preference(...args) { calls.push(args); }}; }
  };
  const context = vm.createContext({URLSearchParams, document,
    MediaSource: {isTypeSupported(type) { return !String(type).includes('unsupported-codec'); }},
    setTimeout(fn) { timers.push(fn); }});
  context.window = context;
  if (existingConfig) context.ytcfg = existingConfig;
  vm.runInContext(source, context);
  return {context, timers, calls};
}

function defineConfigMethods(config) {
  config.data_ = {};
  config.get = function(key, fallback) { return Object.hasOwn(this.data_, key) ? this.data_[key] : fallback; };
  config.set = function(data) { Object.assign(this.data_, data); return 'original-return'; };
}

// Inicialização posterior: window.ytcfg e seus métodos são atribuídos depois
// do script da extensão. A flag deve chegar ao consumidor já corrigida.
const delayed = environment();
delayed.context.ytcfg = {};
defineConfigMethods(delayed.context.ytcfg);
const flags = {html5_enable_ac3:false, other_feature:17};
assert.equal(delayed.context.ytcfg.set({EXPERIMENT_FLAGS:flags}), 'original-return');
assert.equal(delayed.context.ytcfg.get('EXPERIMENT_FLAGS').html5_enable_ac3, true);
assert.equal(flags.other_feature, 17);
assert.equal(delayed.context.ytcfg.get('missing', 42), 42);
const args = {fflags:'unrelated_flag=value', video_id:'example'};
delayed.context.ytcfg.set({PLAYER_CONFIG:{args}});
assert.equal(new URLSearchParams(args.fflags).get('unrelated_flag'), 'value');
assert.equal(new URLSearchParams(args.fflags).get('html5_enable_ac3'), 'true');
assert.equal(args.video_id, 'example');

// O codec original sem suporte deve continuar sem suporte. Apenas a
// consulta inválida de canais que bloqueia o player muda de resultado.
const media = delayed.context.MediaSource;
assert.equal(media.isTypeSupported('audio/mp4; codecs="ac-3"; channels=2'), true);
assert.equal(media.isTypeSupported('audio/mp4; codecs="ac-3"; channels=6'), true);
assert.equal(media.isTypeSupported('audio/mp4; codecs="ac-3"; channels=99'), false);
assert.equal(media.isTypeSupported('audio/mp4; codecs="unsupported-codec"; channels=6'), false);
assert.equal(media.isTypeSupported('video/mp4; codecs="example"; channels=99'), true);
const patchedFunction = media.isTypeSupported;
vm.runInContext(source, delayed.context);
assert.equal(media.isTypeSupported, patchedFunction);
delayed.timers.forEach(fn => fn());
assert.deepEqual(delayed.calls, [[1,true]]);

// Configuração existente: preserve this, retorno e demais opções do site.
const existingConfig = {};
defineConfigMethods(existingConfig);
existingConfig.set({EXPERIMENT_FLAGS:{other_feature:5}});
const existing = environment(existingConfig);
assert.equal(existing.context.ytcfg.get('EXPERIMENT_FLAGS').other_feature, 5);
assert.equal(existing.context.ytcfg.get('EXPERIMENT_FLAGS').html5_enable_ac3, true);
console.log('PASS: bootstrap posterior/existente, flags preservadas, codecs sem suporte, consulta 99 e preferência 5.1.');
