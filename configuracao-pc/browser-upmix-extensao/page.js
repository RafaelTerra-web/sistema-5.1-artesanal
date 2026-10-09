(() => {
  'use strict';
  if (location.origin !== 'https://www.youtube.com' || window.top !== window || window.__sistema51SourceInstalled) return;
  window.__sistema51SourceInstalled = true;
  const sources = new WeakMap();
  let workletUrl = null, context = null, active = null, loading = false;
  const sessionId = crypto.randomUUID();
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = 'Ativar upmix automático 5.1';
  button.title = 'Aplica upmix somente quando o áudio decodificado possui um ou dois canais.';
  Object.assign(button.style, {position: 'fixed', right: '16px', bottom: '20px', zIndex: '2147483647',
    padding: '10px 14px', background: '#182b3d', color: '#fff', border: '1px solid #96dccc',
    borderRadius: '6px', maxWidth: '320px', cursor: 'pointer'});
  document.documentElement.append(button);

  function status(text, fields = {}) {
    button.textContent = text;
    // Same-origin, allowlisted, ephemeral status. No media URL, cookies, account
    // information, PCM samples or signed streaming URL leave the page.
    const message = {type: 'sistema51-source-status', version: 1, sessionId,
      active: !!active, ...fields};
    window.postMessage(message, location.origin);
    document.documentElement.setAttribute('data-sistema51-source', JSON.stringify(message));
  }

  function mainVideo() {
    return document.getElementById('movie_player')?.querySelector('video') || document.querySelector('video.html5-main-video');
  }

  function safeMedia(video) {
    if (!video || video.mediaKeys || video.srcObject) throw Error('Esta fonte não permite o processamento local.');
    const current = video.currentSrc || video.src;
    if (!current || video.readyState < 1) throw Error('Inicie um vídeo antes de ativar.');
    const source = new URL(current, location.href);
    // Do not set crossOrigin or change the video's src. Only an already
    // same-origin resource is eligible; CORS-tainted audio may become silent.
    if (source.origin !== location.origin) throw Error('Fonte de outra origem: áudio direto preservado.');
  }

  function direct(record, reason) {
    try { record.source.disconnect(); } catch (_) {}
    try { record.node.disconnect(); } catch (_) {}
    try {
      record.source.connect(context.destination);
      record.direct = true;
      record.connected = true;
      status(reason, {channels: null, mode: 'Unknown', upmixed: false, method: 'direct-fallback'});
    } catch (_) {
      record.failed = true;
      status('A rota mudou — atualize o vídeo com F5',
        {channels: null, mode: 'Unknown', upmixed: false, preserved: false, method: 'route-failed'});
    }
  }

  async function activate() {
    if (loading) return;
    loading = true;
    button.disabled = true;
    try {
      const video = mainVideo();
      safeMedia(video);
      if (!workletUrl) throw Error('Extensão ainda inicializando. Tente novamente.');
      const existing = sources.get(video);
      if (existing) {
        active = existing;
        if (existing.failed) throw Error('O processador falhou — atualize o vídeo com F5.');
        await context.resume();
        safeMedia(video);
        if (context.state !== 'running' || context.destination.maxChannelCount < 6)
          throw Error('Selecione a saída multicanal do sistema antes de ativar.');
        if (existing.direct) {
          existing.connected = false;
          existing.source.disconnect();
          existing.source.connect(existing.node);
          existing.node.connect(context.destination);
          existing.direct = false;
          existing.connected = true;
          existing.enabled = true;
        } else existing.enabled = !existing.enabled;
        existing.node.port.postMessage({type: 'set-upmix', enabled: existing.enabled});
        status(existing.enabled ? 'Upmix automático: aguardando fonte' : 'Upmix desligado — fonte preservada');
        return;
      }
      if (!context) {
        const Candidate = window.AudioContext || window.webkitAudioContext;
        if (!Candidate || !window.AudioWorkletNode) throw Error('AudioWorklet indisponível neste navegador.');
        context = new Candidate({sampleRate: 48000, latencyHint: 'interactive'});
        if (context.destination.maxChannelCount < 6 || context.sampleRate !== 48000)
          throw Error('Selecione a saída 5.1 do sistema e atualize a página.');
        context.destination.channelCount = 6;
        context.destination.channelCountMode = 'explicit';
        context.destination.channelInterpretation = 'speakers';
        await context.audioWorklet.addModule(workletUrl);
      }
      await context.resume();
      // Playback/navigation can change while addModule/resume awaits. Recheck
      // the actual resource immediately before binding any media element.
      safeMedia(video);
      if (mainVideo() !== video) throw Error('O player mudou. Clique novamente para ativar.');
      if (context.state !== 'running' || context.destination.maxChannelCount < 6)
        throw Error('A saída 5.1 ainda não está pronta.');
      const node = new AudioWorkletNode(context, 'sistema51-source-upmix', {
        numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [6],
        channelCountMode: 'max', channelInterpretation: 'discrete'});
      // All fallible preflight/module/context steps precede this irreversible
      // binding. A bound MediaElement can return to its original route with F5.
      const source = context.createMediaElementSource(video);
      const record = {source, node, video, enabled: true, direct: false, failed: false, connected: false, generation: 0};
      sources.set(video, record);
      active = record;
      node.onprocessorerror = () => {
        record.failed = true;
        direct(record, 'Processador indisponível — fonte direta; F5 para reativar');
      };
      node.port.onmessage = event => {
        const value = event.data;
        if (record !== active || record.direct || record.failed || value?.type !== 'source-channels' || value.version !== 1 ||
            !Number.isInteger(value.channels) || value.channels < 0 || value.channels > 32) return;
        if (![0, 1, 2, 6].includes(value.channels)) {
          direct(record, 'Formato não suportado — fonte direta');
          return;
        }
        status(value.upmixed ? `Upmix ${value.channels} → 5.1` : value.channels === 6 ? '5.1 nativo preservado' :
          value.channels === 2 ? 'Estéreo preservado — upmix desligado' : value.channels === 1 ? 'Mono preservado — upmix desligado' : 'Aguardando áudio',
          {channels: value.channels, mode: value.mode, upmixed: value.upmixed,
            method: 'decoded-worklet-input', sampleRate: value.sampleRate, mediaGeneration: record.generation});
      };
      video.addEventListener('loadedmetadata', () => {
        if (record !== active) return;
        record.generation++;
        try {
          safeMedia(video);
          record.node.port.postMessage({type: 'source-reset'});
        } catch (_) {
          // Once bound, CORS/EME can silence MediaElementAudioSourceNode even
          // on a direct graph. F5 is required to restore the browser's route.
          record.failed = true;
          direct(record, 'A fonte mudou de proteção/origem — use F5 para áudio direto');
        }
      });
      try {
        source.connect(node);
        node.connect(context.destination);
        record.connected = true;
      } catch (error) {
        direct(record, 'Processador indisponível — fonte direta');
        throw error;
      }
      status('Upmix automático: aguardando fonte');
    } catch (error) {
      if (active && !active.connected && !active.direct)
        direct(active, 'Inicialização incompleta — fonte direta; F5 para reativar');
      if (!active && context) {
        try { await context.close(); } catch (_) {}
        context = null;
      }
      status(error.message || 'Não foi possível ativar o upmix.');
    } finally {
      loading = false;
      button.disabled = false;
    }
  }
  button.addEventListener('click', activate);
  window.addEventListener('message', event => {
    if (event.source !== window || event.origin !== location.origin ||
        event.data?.type !== 'sistema51-source-bootstrap' || event.data.version !== 1 || workletUrl) return;
    const value = event.data.workletUrl;
    if (typeof value === 'string' && /^chrome-extension:\/\/[a-p]{32}\/source-worklet\.js$/.test(value)) workletUrl = value;
  });
})();
