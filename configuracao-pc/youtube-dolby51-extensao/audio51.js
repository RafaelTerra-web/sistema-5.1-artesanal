(() => {
  'use strict';
  const mark = Symbol.for('sistema-artesanal.youtube-dolby51');
  if (window[mark]) return;
  window[mark] = true;

  // O player considera "channels" utilizável apenas se o teste inválido de
  // 99 canais falhar. Chromium ignora esse parâmetro e aceita os dois testes.
  // Não anunciamos um codec novo: as consultas válidas usam a API original.
  const mediaSource = window.MediaSource;
  if (mediaSource && typeof mediaSource.isTypeSupported === 'function') {
    const original = mediaSource.isTypeSupported;
    mediaSource.isTypeSupported = function (type) {
      if (typeof type === 'string' && /^audio\//i.test(type)) {
        const channels = /(?:^|;)\s*channels\s*=\s*"?(\d+)"?\s*(?:;|$)/i.exec(type);
        if (channels && Number(channels[1]) === 99) return false;
      }
      return Reflect.apply(original, this, arguments);
    };
  }

  const audioFlags = {
    html5_enable_ac3: true,
    html5_enable_eac3: false,
    html5_onesie_51_audio: true,
    html5_enable_new_audio_settings_menu: true,
    html5_enable_audio_quality_setting: true,
    html5_enable_audio_quality_setting_feature: true
  };
  const patched = new WeakSet();

  function patchFlags(flags) {
    if (flags && typeof flags === 'object' && !Array.isArray(flags)) {
      for (const [key, value] of Object.entries(audioFlags)) {
        try { flags[key] = value; } catch (_) { /* objeto imutável: não interromper o site */ }
      }
    }
    return flags;
  }

  function patchArgs(args) {
    if (!args || typeof args !== 'object' || Array.isArray(args)) return args;
    try {
      const flags = new URLSearchParams(typeof args.fflags === 'string' ? args.fflags : '');
      for (const [key, value] of Object.entries(audioFlags)) flags.set(key, String(value));
      args.fflags = flags.toString();
    } catch (_) { }
    return args;
  }

  function patchConfigValue(key, value) {
    if (key === 'EXPERIMENT_FLAGS') patchFlags(value);
    else if (key === 'PLAYER_VARS') patchArgs(value);
    else if (key === 'PLAYER_CONFIG' && value?.args) patchArgs(value.args);
    return value;
  }

  function patchConfig(config) {
    if (!config || typeof config !== 'object' || patched.has(config)) return;
    patched.add(config);
    // Os métodos podem ser atribuídos depois de window.ytcfg. Os setters
    // interceptam essa inicialização, sem esperar uma consulta por timer.
    for (const name of ['get', 'set']) {
      const descriptor = Object.getOwnPropertyDescriptor(config, name);
      if (descriptor && !descriptor.configurable) continue;
      let original = config[name];
      let wrapped;
      const makeWrapper = () => {
        if (typeof original !== 'function') { wrapped = original; return; }
        const method = original;
        wrapped = function (...args) {
          if (name === 'set') {
            if (typeof args[0] === 'string') patchConfigValue(args[0], args[1]);
            else if (args[0] && typeof args[0] === 'object') {
              for (const key of ['EXPERIMENT_FLAGS', 'PLAYER_VARS', 'PLAYER_CONFIG']) {
                if (Object.prototype.hasOwnProperty.call(args[0], key)) patchConfigValue(key, args[0][key]);
              }
            }
          }
          const result = Reflect.apply(method, this, args);
          return name === 'get' ? patchConfigValue(args[0], result) : result;
        };
      };
      makeWrapper();
      try {
        Object.defineProperty(config, name, {
          configurable: true, enumerable: descriptor?.enumerable ?? true,
          get: () => wrapped,
          set(value) { original = value; makeWrapper(); }
        });
      } catch (_) { }
    }
  }

  const descriptor = Object.getOwnPropertyDescriptor(window, 'ytcfg');
  if (!descriptor || (descriptor.configurable && !descriptor.get && !descriptor.set)) {
    let config = window.ytcfg;
    patchConfig(config);
    try {
      Object.defineProperty(window, 'ytcfg', {
        configurable: true, enumerable: descriptor?.enumerable ?? true,
        get: () => config,
        set(value) { config = value; patchConfig(value); }
      });
    } catch (_) { }
  } else patchConfig(window.ytcfg);

  function applyPreference() {
    const player = document.getElementById('movie_player');
    if (typeof player?.setUserAudio51Preference === 'function') {
      try { player.setUserAudio51Preference(1, true); } catch (_) { }
    }
    document.documentElement?.setAttribute('data-sistema51-youtube', 'experimental-ativo');
  }
  document.addEventListener('DOMContentLoaded', applyPreference, {once: true});
  document.addEventListener('yt-navigate-finish', applyPreference);
  setTimeout(applyPreference, 2500);
})();
