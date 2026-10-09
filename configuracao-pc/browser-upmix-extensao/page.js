(() => {
  'use strict';
  if (location.origin !== 'https://www.youtube.com' || window.top !== window || window.__sistema51SourceInstalled) return;
  window.__sistema51SourceInstalled = true;
  const sources = new WeakMap();
  let workletUrl = null, context = null, active = null, loading = false, navigating = false;
  const sessionId = crypto.randomUUID();
  const button = document.createElement('button');
  const panel = document.createElement('div');
  const diagnostics = document.createElement('div');
  const copyButton = document.createElement('button');
  let diagnosticText = 'Sistema 5.1: aguardando ativação.';
  button.type = 'button';
  button.textContent = 'Ativar upmix automático 5.1';
  button.title = 'Aplica upmix somente a um/dois canais com codec da faixa selecionada confirmado e diferente de AC-3/E-AC-3.';
  Object.assign(panel.style, {position: 'fixed', right: '16px', bottom: '20px', zIndex: '2147483647',
    padding: '10px', background: '#102030', color: '#fff', borderRadius: '6px', maxWidth: '360px', font: '12px/1.4 sans-serif'});
  Object.assign(button.style, {
    padding: '10px 14px', background: '#182b3d', color: '#fff', border: '1px solid #96dccc',
    borderRadius: '6px', maxWidth: '320px', cursor: 'pointer'});
  diagnostics.setAttribute('role', 'status');
  diagnostics.setAttribute('aria-live', 'polite');
  diagnostics.textContent = diagnosticText;
  Object.assign(diagnostics.style, {marginTop: '8px', whiteSpace: 'pre-wrap', userSelect: 'text'});
  copyButton.type = 'button'; copyButton.textContent = 'Copiar diagnóstico';
  Object.assign(copyButton.style, {marginTop: '6px', cursor: 'pointer'});
  copyButton.addEventListener('click', async () => {
    try {await navigator.clipboard.writeText(diagnosticText); copyButton.textContent = 'Diagnóstico copiado';}
    catch (_) {copyButton.textContent = 'Selecione e copie o texto acima';}
  });
  panel.append(button, diagnostics, copyButton);
  document.documentElement.append(panel);

  function status(text, fields = {}) {
    button.textContent = text;
    // Same-origin, allowlisted, ephemeral status. No media URL, cookies, account
    // information, PCM samples or signed streaming URL leave the page.
    const message = {type: 'sistema51-source-status', version: 1, sessionId,
      active: !!active, ...fields};
    const codec = message.originalAudioCodec || 'Unknown';
    diagnosticText = `Sistema 5.1 v0.2.1\n${text}\nCodec: ${codec} (${message.codecConfirmed ? 'confirmado' : 'não confirmado'})` +
      `\nOrigem: ${message.originalAudioChannels ?? '?'} canais | PCM do navegador: ${message.channels ?? '?'} canais` +
      `\nEvidência codec: ${message.codecEvidence || 'ausente'} | canais: ${message.originalChannelEvidence || 'ausente'}` +
      `\nUpmix: ${message.upmixed ? 'sim' : 'não'} | motivo: ${message.blockedReason || (message.upmixed ? 'autorizado' : 'aguardando')}` +
      `\nGeração: ${message.mediaGeneration ?? '?'} | ${message.sampleRate || '?'} Hz`;
    diagnostics.textContent = diagnosticText;
    button.title = diagnosticText;
    copyButton.textContent = 'Copiar diagnóstico';
    window.postMessage(message, location.origin);
    document.documentElement.setAttribute('data-sistema51-source', JSON.stringify(message));
  }

  function mainVideo() {
    return document.getElementById('movie_player')?.querySelector('video') || document.querySelector('video.html5-main-video');
  }

  /** @returns {'Unknown'|'AC3'|'EAC3'|'Opus'|'AAC'|'Vorbis'|'MP3'|'FLAC'|'PCM'} */
  function classifyCodec(value) {
    if (typeof value !== 'string') return 'Unknown';
    const codec = value.trim().toLowerCase();
    if (['ec-3', 'eac3', 'e-ac-3', 'dd+', 'dolby digital plus', 'mp4a.a6'].includes(codec)) return 'EAC3';
    if (['ac-3', 'ac3', 'a52', 'dolby digital', 'mp4a.a5'].includes(codec)) return 'AC3';
    if (codec === 'opus') return 'Opus';
    // mp4a alone is a sample-entry family, not evidence of AAC.
    if (/^mp4a\.40\.(?:2|02|5|05|29|42)$/.test(codec) || codec === 'aac') return 'AAC';
    if (codec === 'vorbis') return 'Vorbis';
    if (codec === 'mp3') return 'MP3';
    if (codec === 'flac') return 'FLAC';
    if (/^(?:pcm|lpcm|sowt|twos|f32le|f64le)$/.test(codec)) return 'PCM';
    return 'Unknown';
  }

  function formatId(value) {
    return (typeof value === 'number' || typeof value === 'string') && /^\d+$/.test(String(value)) ? String(value) : null;
  }

  function audioTrackId(track) {
    if (!track || typeof track !== 'object') return null;
    const id = track.id || track.audioTrackId || (typeof track.getId === 'function' ? track.getId() : null);
    return typeof id === 'string' && id ? id : null;
  }

  function parseMime(mime) {
    if (typeof mime !== 'string' || !/^(audio|video)\//i.test(mime)) return null;
    const parameter = /\bcodecs\s*=\s*(?:"([^"]+)"|([^;\s]+))/i.exec(mime);
    const tokens = (parameter?.[1] || parameter?.[2])?.split(',').map(token => token.trim());
    if (!tokens || !tokens.length) return null;
    const audio = tokens.map(classifyCodec).filter(codec => codec !== 'Unknown');
    if (audio.length > 1 || (/^audio\//i.test(mime) && (audio.length !== 1 || tokens.length !== 1)) ||
        (/^video\//i.test(mime) && tokens.some(token => classifyCodec(token) === 'Unknown' &&
          !/^(?:avc[13]\.|av01\.|vp09\.|vp8$|vp9$|hev1\.|hvc1\.)/i.test(token)))) return null;
    const channels = Number(/(?:^|;)\s*channels\s*=\s*(\d+)\s*(?:;|$)/i.exec(mime)?.[1]);
    return {codec: audio[0] || null, channels: Number.isInteger(channels) && channels >= 1 && channels <= 32 ? channels : null};
  }

  function mseCodec(record, captured) {
    if (captured.unknown || !Array.isArray(captured.buffers)) return null;
    const candidates = [];
    for (const buffer of captured.buffers) {
      if (!Array.isArray(buffer.mimes) || !buffer.mimes.length) return null;
      const audio = new Set(), channels = new Set();
      let unknownChannels = false;
      for (const mime of buffer.mimes) {
        const parsed = parseMime(mime);
        if (!parsed) return null;
        if (parsed.codec) {
          audio.add(parsed.codec);
          if (parsed.channels !== null) channels.add(parsed.channels);
          else unknownChannels = true;
        }
      }
      // Mixed codec histories are ambiguous at the playhead after changeType.
      if (audio.size > 1) return null;
      if (audio.size === 1) candidates.push({codec: [...audio][0],
        channels: [...channels].some(value => value > 2) ? Math.max(...channels) :
          !unknownChannels && channels.size === 1 ? [...channels][0] : null,
        allowSelectedChannels: buffer.typeChanges === 0});
    }
    // Only one active audio buffer; an offered/inactive buffer is not selected.
    if (candidates.length !== 1) return null;
    const {codec, channels, allowSelectedChannels} = candidates[0];
    return {originalAudioCodec: codec, originalAudioChannels: channels, codecEvidence: 'mse-active-source-buffer',
      originalChannelEvidence: channels === null ? null : 'mse-mime-channels', allowSelectedChannels,
      key: ['mse', captured.identity, captured.revision, record.video.currentSrc || record.video.src, codec, channels].join('\n')};
  }

  function selectedPlayerCodec(record) {
    // These are read-only, private YouTube APIs. Unsupported/stale/ambiguous
    // shapes fail closed; a list of offered formats never grants permission.
    if (navigating || record.video.readyState < 1 || mainVideo() !== record.video) return null;
    try {
      safeMedia(record.video);
      const page = new URL(location.href);
      const wanted = page.searchParams.get('v') || /^\/shorts\/([^/]+)/.exec(page.pathname)?.[1];
      const player = document.getElementById('movie_player');
      const stats = player?.getStatsForNerds?.(), response = player?.getPlayerResponse?.();
      const videoId = response?.videoDetails?.videoId;
      const statsId = stats?.video_id || stats?.videoId || stats?.debug_videoId || stats?.docid;
      if (!wanted || typeof videoId !== 'string' || wanted !== videoId || typeof statsId !== 'string' ||
          statsId.split(' / ')[0] !== videoId) return null;
      const currentData = player.getVideoData?.();
      if (currentData && (currentData.video_id || currentData.videoId) !== videoId) return null;
      const displayed = typeof stats.codecs === 'string' ?
        /^[^/]+\/\s*([a-z0-9.+_-]+)\s*\((\d+)\)\s*$/i.exec(stats.codecs) : null;
      if (typeof stats.codecs === 'string' && !displayed) return null;
      const selected = formatId(stats.afmt ?? stats.audio_format ?? stats.debug_afmt) || displayed?.[2];
      if (!selected || (displayed && selected !== displayed[2])) return null;
      const currentTrackId = audioTrackId(player.getAudioTrack?.());
      const statsTrackId = typeof stats.audio_track_id === 'string' ? stats.audio_track_id : null;
      if (currentTrackId && statsTrackId && currentTrackId !== statsTrackId) return null;
      const trackId = currentTrackId || statsTrackId;
      const offered = [...(response.streamingData?.adaptiveFormats || []), ...(response.streamingData?.formats || [])];
      let matching = offered.filter(format => formatId(format.itag) === selected);
      if (matching.some(format => format.audioTrack?.id)) {
        if (!trackId) return null;
        matching = matching.filter(format => format.audioTrack?.id === trackId);
      }
      if (matching.length !== 1 || typeof matching[0].mimeType !== 'string') return null;
      const mime = matching[0].mimeType;
      const parsed = parseMime(mime), codec = parsed?.codec;
      if (!codec) return null;
      if (displayed && classifyCodec(displayed[1]) !== codec) return null;
      const declaredChannels = Number.isInteger(matching[0].audioChannels) && matching[0].audioChannels >= 1 &&
        matching[0].audioChannels <= 32 ? matching[0].audioChannels : null;
      const availableChannels = [declaredChannels, parsed.channels].filter(value => value !== null);
      const originalAudioChannels = availableChannels.some(value => value > 2) ? Math.max(...availableChannels) :
        new Set(availableChannels).size === 1 ? availableChannels[0] : null;
      return {originalAudioCodec: codec, originalAudioChannels, codecEvidence: 'youtube-selected-format',
        originalChannelEvidence: originalAudioChannels === null ? null : 'youtube-selected-format',
        key: [videoId, record.video.currentSrc || record.video.src, selected, trackId || '', codec, originalAudioChannels].join('\n')};
    } catch (_) {return null;}
  }

  function selectedCodec(record) {
    if (navigating || record.video.readyState < 1 || mainVideo() !== record.video) return null;
    try {
      safeMedia(record.video);
      const capture = window.__sistema51SourceCodecCapture;
      const captured = capture?.version === 1 ? capture.read(record.video) : null;
      if (captured === null) return selectedPlayerCodec(record);
      // Ambiguous MSE histories cannot be overridden by private player data.
      const evidence = mseCodec(record, captured);
      if (!evidence) return null;
      const selected = selectedPlayerCodec(record);
      if (!selected || selected.originalAudioCodec !== evidence.originalAudioCodec || selected.originalAudioChannels === null)
        return evidence;
      const currentChannels = evidence.originalAudioChannels, selectedChannels = selected.originalAudioChannels;
      // A selected format is corroborated by this exact current MediaSource,
      // video/track identity and codec. Only this bridge may supply missing
      // original channels; an offered list or a past changeType never does.
      if (selectedChannels > 2 || currentChannels > 2) evidence.originalAudioChannels = Math.max(currentChannels || 0, selectedChannels);
      else if (currentChannels !== null && currentChannels !== selectedChannels) evidence.originalAudioChannels = null;
      else if (currentChannels === null && evidence.allowSelectedChannels) evidence.originalAudioChannels = selectedChannels;
      else return evidence;
      evidence.originalChannelEvidence = evidence.originalAudioChannels === null ? null : 'youtube-selected-format+mse';
      evidence.key += '\n' + selected.key + '\n' + evidence.originalAudioChannels;
      return evidence;
    } catch (_) {return null;}
  }

  function invalidateSource(record) {
    record.generation++;
    record.candidate = null;
    record.proof = null;
    record.originalAudioCodec = 'Unknown';
    record.originalAudioChannels = null;
    record.codecEvidence = null;
    record.originalChannelEvidence = null;
    record.node.port.postMessage({type: 'set-source', generation: record.generation,
      originalAudioCodec: 'Unknown', originalAudioChannels: null, confirmed: false});
  }

  function refreshSource(record) {
    if (record !== active || record.failed || record.direct) return;
    const evidence = selectedCodec(record);
    if (!evidence) {
      if (record.proof || record.candidate) invalidateSource(record);
      return;
    }
    if (record.proof !== evidence.key) {
      if (record.candidate !== evidence.key) {
        invalidateSource(record);
        record.candidate = evidence.key;
        return;
      }
      // Confirm on a second observation after the native first-block gate.
      record.proof = evidence.key;
      record.originalAudioCodec = evidence.originalAudioCodec;
      record.originalAudioChannels = evidence.originalAudioChannels;
      record.codecEvidence = evidence.codecEvidence;
      record.originalChannelEvidence = evidence.originalChannelEvidence;
    }
    record.node.port.postMessage({type: 'set-source', generation: record.generation,
      originalAudioCodec: record.originalAudioCodec, originalAudioChannels: record.originalAudioChannels,
      codecEvidence: record.codecEvidence, confirmed: true});
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
      if (active && active.video !== video) invalidateSource(active);
      if (!workletUrl) throw Error('Extensão ainda inicializando. Tente novamente.');
      const existing = sources.get(video);
      if (existing) {
        active = existing;
        if (existing.failed) throw Error('O processador falhou — atualize o vídeo com F5.');
        // Revoke before resume/reconnect: a suspended graph must not render its
        // first new block with a codec permission from the previous source.
        invalidateSource(existing);
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
        status(existing.enabled ? 'Fonte preservada — aguardando codec selecionado' : 'Upmix desligado — fonte preservada');
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
      const record = {source, node, video, enabled: true, direct: false, failed: false, connected: false,
        generation: 0, candidate: null, proof: null, originalAudioCodec: 'Unknown', originalAudioChannels: null};
      sources.set(video, record);
      active = record;
      invalidateSource(record);
      node.onprocessorerror = () => {
        record.failed = true;
        direct(record, 'Processador indisponível — fonte direta; F5 para reativar');
      };
      node.port.onmessage = event => {
        const value = event.data;
        if (record !== active || record.direct || record.failed || value?.type !== 'source-channels' || value.version !== 1 ||
            value.mediaGeneration !== record.generation || !Number.isInteger(value.channels) || value.channels < 0 || value.channels > 32) return;
        if (![0, 1, 2, 6].includes(value.channels)) {
          direct(record, 'Formato não suportado — fonte direta');
          return;
        }
        status(value.upmixed ? `Upmix ${value.upmixSourceChannels || value.originalAudioChannels || value.channels} → 5.1 (${value.originalAudioCodec})` :
          value.blockedReason === 'dolby-protected' ? `${value.originalAudioCodec} preservado — sem upmix` :
          value.blockedReason === 'upmix-disabled' ? `Upmix desligado — PCM ${value.channels} preservado` :
          value.blockedReason === 'codec-unconfirmed' ? 'Fonte preservada — falta confirmar codec selecionado' :
          value.channels === 6 && value.originalAudioChannels === 6 ? `5.1 da origem preservado (${value.originalAudioCodec})` :
          value.blockedReason === 'original-multichannel' ? 'Fonte multicanal preservada — sem upmix' :
          value.channels === 6 ? `PCM 6 preservado — origem não confirmada (${value.originalAudioCodec})` :
          value.channels === 2 ? 'Estéreo preservado — upmix desligado' : value.channels === 1 ? 'Mono preservado — upmix desligado' : 'Aguardando áudio',
          {channels: value.channels, mode: value.mode, upmixed: value.upmixed,
            originalAudioCodec: value.originalAudioCodec, codecConfirmed: value.codecConfirmed, blockedReason: value.blockedReason,
            originalAudioChannels: value.originalAudioChannels,
            codecEvidence: value.codecConfirmed ? record.codecEvidence : null,
            originalChannelEvidence: value.originalAudioChannels === null ? null : record.originalChannelEvidence,
            upmixSourceChannels: value.upmixSourceChannels,
            method: 'decoded-worklet-input', sampleRate: value.sampleRate, mediaGeneration: record.generation});
      };
      for (const event of ['loadstart', 'emptied']) video.addEventListener(event, () => {
        if (record === active) invalidateSource(record);
      });
      video.addEventListener('loadedmetadata', () => {
        if (record !== active) return;
        invalidateSource(record);
        try {
          safeMedia(video);
        } catch (_) {
          // Once bound, CORS/EME can silence MediaElementAudioSourceNode even
          // on a direct graph. F5 is required to restore the browser's route.
          record.failed = true;
          direct(record, 'A fonte mudou de proteção/origem — use F5 para áudio direto');
        }
      });
      const player = document.getElementById('movie_player');
      for (const event of ['onAudioTrackChanged', 'videodatachange', 'onAdStart', 'onAdEnd', 'onPlaybackQualityChange']) {
        try {player?.addEventListener?.(event, () => {if (record === active) invalidateSource(record);});} catch (_) {}
      }
      try {
        source.connect(node);
        node.connect(context.destination);
        record.connected = true;
      } catch (error) {
        direct(record, 'Processador indisponível — fonte direta');
        throw error;
      }
      refreshSource(record);
      status('Fonte preservada — aguardando codec selecionado');
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
  document.addEventListener('yt-navigate-start', () => {
    navigating = true;
    if (active) invalidateSource(active);
  });
  document.addEventListener('yt-navigate-finish', () => {navigating = false;});
  document.addEventListener('sistema51-codec-changed', () => {if (active) invalidateSource(active);});
  setInterval(() => {if (active) refreshSource(active);}, 200);
  window.addEventListener('message', event => {
    if (event.source !== window || event.origin !== location.origin ||
        event.data?.type !== 'sistema51-source-bootstrap' || event.data.version !== 1 || workletUrl) return;
    const value = event.data.workletUrl;
    if (typeof value === 'string' && /^chrome-extension:\/\/[a-p]{32}\/source-worklet\.js$/.test(value)) workletUrl = value;
  });
})();
