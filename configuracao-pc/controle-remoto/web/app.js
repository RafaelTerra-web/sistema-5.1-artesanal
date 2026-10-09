'use strict';
const $ = id => document.getElementById(id);
const prefs = {
  get(key) { try { return localStorage.getItem('remote51.' + key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem('remote51.' + key, value); } catch {} }
};
let state = null, muted = false, paired = false;
let pollBusy = false, pollTimer, toastTimer, trackKey = '', trackSequence = 0, trackView = null;
let volumeTimer, volumeBusy = false, volumeDirty = false, localVolumeUntil = 0, savedVolumeToastAt = 0;
let volumeRevision = 0, systemRevision = 0, pairingRevision = 0;
let systemBusy = false, mediaBusy = false;
let preferredTarget = prefs.get('target') || '', preferredNav = prefs.get('nav') || '';

function toast(message, error = false) {
  $('toast').textContent = message;
  $('toast').classList.toggle('error', error);
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('toast').hidden = true, 5000);
}

async function api(route, data, timeout = 12000) {
  try {
    const response = await fetch(route, {
      method: data === undefined ? 'GET' : 'POST',
      headers: data === undefined ? {} : {'Content-Type': 'application/json'},
      body: data === undefined ? undefined : JSON.stringify(data),
      signal: AbortSignal.timeout(timeout), cache: 'no-store'
    });
    const result = await response.json();
    if (!response.ok) {
      if (response.status === 401 && route === '/api/status') showPair();
      throw Error(result.error || 'O PC não conseguiu executar o comando.');
    }
    return result;
  } catch (error) {
    if (error.name === 'TimeoutError' || error.name === 'AbortError') throw Error('O PC demorou a responder. Confira a conexão e tente novamente.');
    if (error instanceof TypeError) throw Error('Sem conexão com o PC. Confira o Wi-Fi e o atalho do controle.');
    throw error;
  }
}

function showPair() {
  pairingRevision++;
  paired = false; state = null; trackKey = ''; trackView = null; trackSequence++;
  clearTimeout(volumeTimer); volumeDirty = false; stopRepeat(); navigationQueue.length = 0;
  $('pair').hidden = false; $('remote').hidden = true;
  connection('PIN necessário', false);
}

function connection(text, online) {
  $('connection').textContent = text;
  $('reconnect').classList.toggle('offline', !online);
}

function clock(seconds) {
  const total = Math.max(0, Math.floor(seconds || 0));
  const h = Math.floor(total / 3600), m = Math.floor(total % 3600 / 60), s = total % 60;
  return (h ? `${h}:${String(m).padStart(2, '0')}` : String(m)) + ':' + String(s).padStart(2, '0');
}

function appName(app = '') {
  return /netflix/i.test(app) ? 'Netflix' : /spotify/i.test(app) ? 'Spotify' : /opera/i.test(app) ? 'Opera' : /edge/i.test(app) ? 'Edge' : app.split('!').pop().slice(0, 45);
}

// Polling must not rebuild an open select or erase the user's selection.
function fillSelect(id, entries, preferred, missingLabel) {
  const select = $(id), list = [...entries];
  if (preferred && !list.some(x => x.value === preferred)) list.push({value: preferred, label: missingLabel, disabled: true});
  const signature = JSON.stringify(list);
  if (signature !== select.dataset.signature && document.activeElement !== select) {
    select.replaceChildren(...list.map(x => {
      const option = document.createElement('option');
      option.value = x.value; option.textContent = x.label; option.disabled = !!x.disabled;
      return option;
    }));
    select.dataset.signature = signature;
  }
  if (document.activeElement !== select && Array.from(select.options).some(x => x.value === preferred)) select.value = preferred;
}

function navigationTarget() {
  const id = $('navTarget').value;
  const jelly = state?.jellySessions?.find(x => x.id === id);
  if (jelly) return {kind: 'jelly', session: id, player: jelly};
  const window = id === 'app:netflix' ? findNetflixWindow() : id === 'foreground' ? state?.windows?.find(x => x.current) : state?.windows?.find(x => x.id === id);
  if (id === 'app:netflix') return window ? {kind: 'input', window: window.id, player: window, netflix: true} : null;
  return window ? {kind: 'input', window: id, player: window} : null;
}
function findNetflixWindow() {
  const windows = (state?.windows || []).filter(x => /netflix/i.test(x.title || ''));
  // The installed Netflix app has its own window, even without a playing video.
  return windows.find(x => /^netflix$/i.test(x.title) && x.current) || windows.find(x => /^netflix$/i.test(x.title)) || windows.find(x => x.current) || windows[0];
}
function chosen() {
  const id = $('target').value;
  const nav = navigationTarget();
  if (!id && nav?.kind === 'input') return nav;
  if (id.startsWith('jelly:')) return {kind: 'jelly', session: id.slice(6), player: state?.jellySessions?.find(x => x.id === id.slice(6))};
  if (id === 'netflix:bridge') return {kind: 'netflix', player: state?.netflix?.player};
  const sessions = state?.sessions || [];
  const player = id ? sessions.find(x => x.id === id.slice(4)) : sessions.find(x => x.current) || sessions.find(x => x.state === 'Playing') || sessions[0];
  return {kind: 'win', session: player?.id || '', player};
}

function render() {
  paired = true; connection('Conectado', true);
  $('pair').hidden = true; $('remote').hidden = false;
  if (!volumeBusy && !volumeDirty && Date.now() > localVolumeUntil) {
    $('volume').value = state.volume; $('volumeValue').textContent = state.volume; muted = state.muted;
  }
  renderMute();
  const targets = [{value: '', label: 'Aplicativo escolhido abaixo / mídia do Windows'}];
  if (state.netflix?.player) targets.push({value: 'netflix:bridge', label: 'Netflix · extensão'});
  for (const session of state.jellySessions || []) targets.push({value: 'jelly:' + session.id, label: `Jellyfin · ${session.device} · ${session.title}`});
  for (const session of state.sessions || []) targets.push({value: 'win:' + session.id, label: `${appName(session.app)} · ${session.title || session.state}`});
  const navSessions = state.jellySessions || [];
  const netflixWindow = findNetflixWindow();
  const savedWindow = state.windows?.find(x => x.id === preferredNav);
  if (savedWindow && /netflix/i.test(savedWindow.title)) preferredNav = 'app:netflix';
  else if (preferredNav.startsWith('window:') && !savedWindow) preferredNav = '';
  if (!preferredNav) preferredNav = netflixWindow ? 'app:netflix' : navSessions[0]?.id || 'foreground';
  prefs.set('nav', preferredNav);
  if ((preferredTarget && !targets.some(x => x.value === preferredTarget)) || (preferredNav === 'app:netflix' && preferredTarget !== 'netflix:bridge')) {
    preferredTarget = ''; prefs.set('target', ''); trackKey = '';
  }
  fillSelect('target', targets, preferredTarget, 'Aplicativo indisponível · escolha outro');
  const navEntries = [{value:'app:netflix',label:netflixWindow ? 'Netflix · conectar automaticamente' : 'Netflix · abra o app no PC',disabled:!netflixWindow}, {value: 'foreground', label: 'Aplicativo em primeiro plano'}, ...(state.windows || []).filter(x => !/^netflix$/i.test(x.title)).map(x => ({value:x.id,label:`${x.title} · ${x.app}`})), ...navSessions.map(x => ({value:x.id,label:`Jellyfin · ${x.device} · ${x.app}`}))];
  fillSelect('navTarget', navEntries, preferredNav, 'Janela indisponível · escolha outra');
  const nav = navigationTarget(), navAvailable = !!nav;
  $('controlNetflix').disabled = !netflixWindow;
  if (nav?.netflix && !prefs.get('netflixMode')) $('navMode').value = 'mouse';
  const jellyActions = ['up','down','left','right','select','back','home','search','fullscreen'];
  document.querySelectorAll('[data-nav]').forEach(button => button.disabled = !navAvailable || (nav.kind === 'jelly' && !jellyActions.includes(button.dataset.nav)) || (button.dataset.nav === 'skipintro' && !/netflix/i.test(nav.player.title || '')));
  $('navMode').disabled = !navAvailable || nav.kind === 'jelly';
  $('touchpad').hidden = !navAvailable || nav.kind !== 'input' || $('navMode').value !== 'mouse';
  $('remoteText').disabled = !navAvailable;
  if (nav?.kind === 'input') $('textForm').hidden = false;
  $('textForm').querySelector('button').disabled = !navAvailable;
  $('navHint').textContent = !navAvailable ? 'Abra o aplicativo no PC e escolha sua janela.' : nav.kind === 'jelly' ? 'Setas e OK controlam a interface Jellyfin.' : $('navMode').value === 'mouse' ? 'Setas movem o mouse. OK e toque clicam no título ou botão.' : 'Setas e OK enviam teclas. Use Próximo foco para alcançar os títulos.';

  const c = chosen(), player = c.player, hasMedia = !!player && (c.kind !== 'jelly' || !!player.itemId);
  const inputMode = c.kind === 'input';
  const paused = c.kind === 'win' ? player?.state !== 'Playing' : player?.paused;
  $('title').textContent = player?.title || (preferredTarget ? 'Aplicativo indisponível' : 'Abra um vídeo no PC');
  $('title').title = $('title').textContent;
  $('playingState').textContent = inputMode ? 'Controle universal' : hasMedia ? (paused ? 'Pausado' : 'Tocando') : 'Sem vídeo';
  $('playPause').textContent = inputMode ? '⏯' : paused ? '▶' : 'Ⅱ';
  $('playPause').setAttribute('aria-label', inputMode ? 'Pausar ou continuar reprodução' : paused ? 'Continuar reprodução' : 'Pausar reprodução');
  $('timeline').textContent = inputMode ? 'Reprodução controlada na janela selecionada abaixo.' : hasMedia ? `${clock(player.position)} / ${clock(player.duration)}` : 'Escolha o aplicativo acima';
  const canSeek = inputMode || hasMedia && (c.kind === 'win' ? player.canSeek : player.duration > 0);
  for (const id of ['back', 'forward']) $(id).disabled = mediaBusy || !canSeek;
  $('playPause').disabled = mediaBusy || !hasMedia || (c.kind === 'win' && !(player.canPlay || player.canPause));
  $('pause').disabled = inputMode || mediaBusy || !hasMedia || (c.kind === 'win' && !player.canPause);
  $('resume').disabled = inputMode || mediaBusy || !hasMedia || (c.kind === 'win' && !player.canPlay);
  $('jellyStatus').textContent = state.jellyError || (state.jellyConnected ? 'Conectado. A navegação está na aba Controle.' : 'A senha não é salva pelo controle.');
  $('bridgeStatus').textContent = state.netflix ? 'Extensão conectada' + (state.netflix.message ? ': ' + state.netflix.message : '') : 'Extensão não conectada';
  renderSystem();
  const trackRevision = c.kind === 'netflix' ? JSON.stringify([state.netflix?.tracks, state.netflix?.subtitles]) : c.kind === 'jelly' ? JSON.stringify([player?.audioIndex, player?.subtitleIndex, player?.streams]) : '';
  const key = $('target').value + '|' + (player?.itemId || player?.id || player?.title || '') + '|' + trackRevision;
  if (trackView?.target === $('target').value) { fillTracks('audioTrack', trackView.tracks.audio); fillTracks('subtitleTrack', trackView.tracks.subtitles); }
  if (trackKey !== key) { trackKey = key; refreshTracks().catch(error => toast(error.message, true)); }
  if (mediaBusy) { $('audioTrack').disabled = true; $('subtitleTrack').disabled = true; }
}

function renderMute() {
  $('mute').setAttribute('aria-pressed', String(muted));
  $('mute').textContent = muted ? 'Ativar som' : 'Mudo';
}

function renderSystem() {
  const audio = state.audioStatus || {};
  const running = state.audioRunning;
  $('audioBadge').textContent = systemBusy ? 'Alterando' : state.audioError ? 'Verificar' : running ? '5.1 ativo' : 'Direto';
  $('audioBadge').classList.toggle('subdued', !running);
  $('audioState').textContent = systemBusy ? 'Aguarde a mudança da rota de áudio…' : state.audioError ? 'Não foi possível confirmar a rota de áudio. Atualize o estado.' : audio.Estado || (running ? 'Saída de áudio confirmada pelo gerenciador.' : 'Processamento 5.1 desligado. Volume salvo para a próxima ativação.');
  document.querySelector('.system-card').classList.toggle('busy', systemBusy);
  for (const id of ['audioOn', 'audioOff', 'routePcm', 'routeOptical', 'upmixAuto', 'upmixNative', 'upmixStereo']) $(id).disabled = systemBusy;
  $('audioOn').disabled ||= running; $('audioOff').disabled ||= !running;
  document.querySelectorAll('[data-profile]').forEach(button => {
    const selected = audio.Perfil === button.dataset.profile;
    button.setAttribute('aria-pressed', String(selected)); button.disabled = true;
  });
  $('upmixAuto').setAttribute('aria-pressed', String(audio.InputMode === 'Auto' || (!audio.InputMode && audio.UpmixAutomatico === true)));
  $('upmixNative').setAttribute('aria-pressed', String(audio.InputMode === 'Native'));
  $('upmixStereo').setAttribute('aria-pressed', String(audio.InputMode === 'Stereo'));
  $('routePcm').setAttribute('aria-pressed', String(audio.Modo === 'Pcm'));
  $('routeOptical').setAttribute('aria-pressed', String(audio.Modo === 'Optical'));
  const delays = audio.AtrasosMs || {};
  const hasDelays = ['FL', 'FR', 'CEN', 'LFE', 'SL', 'SR'].every(channel => typeof delays[channel] === 'number' && Number.isFinite(delays[channel]) && delays[channel] >= 0);
  const delayNumber = value => value.toLocaleString('pt-BR', {minimumFractionDigits: 1, maximumFractionDigits: 1});
  const delayPair = (left, right) => delayNumber(left) === delayNumber(right) ? delayNumber(left) + ' ms' : delayNumber(left) + ' / ' + delayNumber(right) + ' ms';
  $('delayState').textContent = hasDelays ? (running ? 'Atrasos aplicados na rota ativa.' : 'Atrasos configurados nesta sessão.') : 'Aguardando valores confirmados nesta rota.';
  $('delayFront').textContent = hasDelays ? delayPair(delays.FL, delays.FR) : '—';
  $('delayCenter').textContent = hasDelays ? delayNumber(delays.CEN) + ' ms' : '—';
  $('delayLfe').textContent = hasDelays ? delayNumber(delays.LFE) + ' ms' : '—';
  $('delaySurround').textContent = hasDelays ? delayPair(delays.SL, delays.SR) : '—';
  const rows = [
    ['Rota 5.1', running ? 'Ativa' : 'Desligada'],
    ['Rota', audio.Modo === 'Optical' ? 'Óptica AC-3 → decodificação → USB' : 'PC → PCM USB'],
    ['Fonte', audio.InputMode === 'Stereo' ? 'Estéreo confirmado · upmix' : audio.InputMode === 'Native' ? '5.1 nativo preservado' : 'Auto por formato informado'],
    ['Reprodução', audio.PlayerId && running ? 'Saída WASAPI confirmada' : audio.Solicitado ? 'Aguardando confirmação de saída' : 'Desligada'],
    ['Jellyfin', state.jellyConnected ? `${(state.jellySessions || []).length} sessão(ões)` : 'Não conectado'],
    ['Última consulta', new Date().toLocaleTimeString('pt-BR')]
  ];
  if (hasDelays) rows.push(['Atrasos', 'FL/FR ' + delayPair(delays.FL, delays.FR) + '; CEN ' + delayNumber(delays.CEN) + ' ms; LFE ' + delayNumber(delays.LFE) + ' ms; SL/SR ' + delayPair(delays.SL, delays.SR)]);
  if (audio.UltimoErro) rows.push(['Último erro de áudio', audio.UltimoErro]);
  if (state.audioError) rows.push(['Consulta de áudio', state.audioError]);
  if (state.backend?.lastWorkerError) rows.push(['Controle', state.backend.lastWorkerError.message || String(state.backend.lastWorkerError)]);
  $('diagnostics').replaceChildren(...rows.flatMap(([label, value]) => { const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = label; dd.textContent = value; return [dt, dd]; }));
}

async function poll() {
  if (pollBusy || document.hidden || systemBusy) return;
  pollBusy = true;
  const requestedVolume = volumeRevision, requestedSystem = systemRevision, requestedPairing = pairingRevision;
  let retry = false;
  try {
    const incoming = await api('/api/status');
    if (requestedPairing !== pairingRevision) return;
    if (requestedSystem !== systemRevision) { retry = true; return; }
    // A slow status response may predate a successful volume adjustment.
    if (requestedVolume !== volumeRevision) { incoming.volume = Number($('volume').value); incoming.muted = muted; }
    state = incoming; render();
  }
  catch { if (paired) connection('Sem conexão', false); }
  finally { pollBusy = false; if (retry) setTimeout(poll, 0); }
}
function schedulePoll() {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(async () => { await poll(); schedulePoll(); }, 2500);
}

async function flushVolume() {
  if (!paired || volumeBusy) return;
  clearTimeout(volumeTimer); volumeBusy = true;
  try {
    do {
      volumeDirty = false;
      const result = await api('/api/volume', {percent: Number($('volume').value), muted});
      if (!result.AppliedLive && Date.now() - savedVolumeToastAt > 8000) { savedVolumeToastAt = Date.now(); toast('Volume salvo para quando o sistema 5.1 ligar.'); }
    } while (volumeDirty && paired);
  } catch (error) { volumeDirty = false; toast(error.message, true); }
  finally { volumeBusy = false; localVolumeUntil = Date.now() + 1500; }
}
function scheduleVolume(immediate = false) {
  volumeRevision++;
  volumeDirty = true; localVolumeUntil = Date.now() + 2500;
  $('volumeValue').textContent = $('volume').value;
  clearTimeout(volumeTimer); volumeTimer = setTimeout(flushVolume, immediate ? 0 : 120);
}

async function command(action, extra = {}) {
  if (mediaBusy) return;
  const c = chosen(); if (!c.player) return;
  mediaBusy = true; render();
  try {
    if (c.kind === 'input') {
      const key = action === 'toggle' ? 'space' : action === 'seek' ? (extra.seconds < 0 ? 'rewind' : 'forward') : action;
      await api('/api/input', {window: c.window, action: key, mode: 'keyboard'});
      return;
    }
    const route = c.kind === 'jelly' ? '/api/jellyfin/command' : c.kind === 'netflix' ? '/api/netflix/command' : '/api/media';
    await api(route, {action, session: c.session, ...extra});
  } catch (error) { toast(error.message, true); }
  finally { mediaBusy = false; if (state) render(); setTimeout(poll, 350); }
}

function fillTracks(id, tracks) {
  const list = (tracks || []).map(x => ({value: String(x.index), label: x.label}));
  const selected = (tracks || []).find(x => x.selected);
  fillSelect(id, list.length ? list : [{value: '', label: 'Nenhuma faixa disponível'}], selected ? String(selected.index) : list[0]?.value || '', 'Faixa indisponível');
  $(id).disabled = mediaBusy || !list.length;
}
async function refreshTracks() {
  const c = chosen(), request = ++trackSequence, current = $('target').value;
  let tracks = {audio: [], subtitles: []}, hint;
  $('audioTrack').disabled = true; $('subtitleTrack').disabled = true;
  if (c.kind === 'jelly' && c.player?.itemId) {
    tracks = await api('/api/jellyfin/tracks', {session: c.session}); hint = 'Faixas do vídeo Jellyfin selecionado.';
  } else if (c.kind === 'netflix' && state?.netflix) {
    tracks = {audio: state.netflix.tracks, subtitles: state.netflix.subtitles}; hint = state.netflix.message || 'Faixas da reprodução Netflix.';
  } else hint = 'Para idiomas, escolha um vídeo Jellyfin ou Netflix com extensão.';
  if (request !== trackSequence || current !== $('target').value) return;
  trackView = {target: current, tracks};
  $('tracksHint').textContent = hint; fillTracks('audioTrack', tracks.audio); fillTracks('subtitleTrack', tracks.subtitles);
}

// At most one repeating command waits behind the current request. No runaway repeats.
const navigationQueue = [];
let navigationBusy = false, repeatTimer, heldButton = null;
async function drainNavigation() {
  if (navigationBusy) return;
  navigationBusy = true;
  try {
    while (navigationQueue.length && paired) {
      const next = navigationQueue.shift();
      await api(next.kind === 'input' ? '/api/input' : '/api/jellyfin/command', next.kind === 'input' ? {window: next.window, action: next.action, mode: next.mode, ...next.extra} : {session: next.session, action: next.action});
    }
  } catch (error) { navigationQueue.length = 0; stopRepeat(); toast(error.message, true); }
  finally { navigationBusy = false; }
}
function navigate(action, repeat = false, extra = {}) {
  const target = navigationTarget();
  if (!paired || !target) return;
  if (action === 'search' && target.kind === 'input' && /netflix/i.test(target.player.title)) { toast('Selecione a busca da Netflix com Tab ou mouse e envie o texto abaixo.'); return; }
  if (action === 'move') {
    const pending = navigationQueue.find(x => x.action === 'move' && x.window === target.window);
    if (pending) { pending.extra.dx = Math.max(-300, Math.min(300, pending.extra.dx + extra.dx)); pending.extra.dy = Math.max(-300, Math.min(300, pending.extra.dy + extra.dy)); return; }
  }
  if (repeat && navigationQueue.some(x => x.repeat)) return;
  if (navigationQueue.length >= 6) return;
  navigationQueue.push({action, ...target, mode: $('navMode').value || 'keyboard', repeat, extra});
  if (!repeat && navigator.vibrate) navigator.vibrate(10);
  drainNavigation();
}
function stopRepeat() {
  clearTimeout(repeatTimer);
  heldButton?.classList.remove('pressed'); heldButton = null;
  for (let i = navigationQueue.length - 1; i >= 0; i--) if (navigationQueue[i].repeat) navigationQueue.splice(i, 1);
}
for (const button of document.querySelectorAll('[data-nav]')) {
  if (button.classList.contains('direction')) {
    button.addEventListener('pointerdown', event => {
      if (event.button !== 0 || button.disabled) return;
      event.preventDefault(); stopRepeat(); heldButton = button;
      button.setPointerCapture(event.pointerId); button.classList.add('pressed'); navigate(button.dataset.nav);
      const repeat = () => { if (heldButton === button) { navigate(button.dataset.nav, true); repeatTimer = setTimeout(repeat, 180); } };
      repeatTimer = setTimeout(repeat, 450);
    });
    for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) button.addEventListener(event, stopRepeat);
    button.onclick = event => { if (event.detail === 0) navigate(button.dataset.nav); };
  } else button.onclick = () => {
    navigate(button.dataset.nav);
    if (button.dataset.nav === 'search') { $('textForm').hidden = false; $('remoteText').focus(); }
  };
}
$('textForm').onsubmit = async event => {
  event.preventDefault();
  const text = $('remoteText').value, target = navigationTarget();
  if (!text.trim() || !target) return;
  const button = $('textForm').querySelector('button'); button.disabled = true;
  try { await api(target.kind === 'input' ? '/api/input' : '/api/jellyfin/command', target.kind === 'input' ? {action:'text',window:target.window,mode:'keyboard',text} : {action:'text',session:target.session,text}); toast('Texto enviado ao campo selecionado no PC.'); }
  catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
};

async function changeSystem(route, payload, label, timeout) {
  if (systemBusy) return;
  systemRevision++;
  systemBusy = true; $('systemMessage').textContent = label; if (state) renderSystem();
  try { await api(route, payload, timeout); toast('Ajuste aplicado.'); }
  catch (error) { toast(error.message, true); }
  finally { systemBusy = false; $('systemMessage').textContent = 'Áudio direto encerra o processamento e libera o HDMI.'; if (state) renderSystem(); await poll(); }
}

const tabs = {remoteTab: 'controls', audioTab: 'audioPanel', setupTab: 'applications'};
function selectTab(id) {
  if (!tabs[id]) id = 'remoteTab';
  for (const [button, panel] of Object.entries(tabs)) {
    $(panel).hidden = button !== id; $(button).classList.toggle('selected', button === id); $(button).setAttribute('aria-selected', String(button === id));
  }
  if (id !== 'remoteTab') stopRepeat();
  prefs.set('tab', id);
}
for (const id of Object.keys(tabs)) $(id).onclick = () => selectTab(id);
selectTab(prefs.get('tab'));
$('pairForm').onsubmit = async event => {
  event.preventDefault(); const button = $('pairForm').querySelector('button'); button.disabled = true;
  try { await api('/api/pair', {pin: $('pin').value}); pairingRevision++; $('pin').value = ''; await poll(); }
  catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
};
$('target').onchange = () => { preferredTarget = $('target').value; prefs.set('target', preferredTarget); trackKey = ''; if (state) render(); };
$('navTarget').onchange = () => {
  stopRepeat(); navigationQueue.length = 0; preferredNav = $('navTarget').value; prefs.set('nav', preferredNav);
  if (preferredNav === 'app:netflix') $('navMode').value = prefs.get('netflixMode') || 'mouse';
  if (preferredNav === 'foreground' || preferredNav === 'app:netflix' || preferredNav.startsWith('window:')) { preferredTarget = ''; prefs.set('target', ''); trackKey = ''; }
  if (state) render();
};
$('navMode').value = prefs.get('navMode') || 'keyboard';
$('navMode').onchange = () => { stopRepeat(); navigationQueue.length = 0; prefs.set('navMode', $('navMode').value); if (preferredNav === 'app:netflix') prefs.set('netflixMode', $('navMode').value); if (state) render(); };
function selectNetflixControl() {
  stopRepeat(); navigationQueue.length = 0; touch = null;
  preferredNav = 'app:netflix'; prefs.set('nav', preferredNav);
  preferredTarget = ''; prefs.set('target', ''); trackKey = '';
  $('navMode').value = 'mouse'; prefs.set('netflixMode', 'mouse');
  selectTab('remoteTab'); if (state) render();
}
$('controlNetflix').onclick = selectNetflixControl;
let touch = null;
$('touchpad').addEventListener('pointerdown', event => { if(event.button !== 0)return; event.preventDefault(); $('touchpad').setPointerCapture(event.pointerId); touch={x:event.clientX,y:event.clientY,moved:0}; });
$('touchpad').addEventListener('pointermove', event => { if(!touch)return; const dx=Math.round((event.clientX-touch.x)*3),dy=Math.round((event.clientY-touch.y)*3); touch.moved+=Math.abs(dx)+Math.abs(dy);touch.x=event.clientX;touch.y=event.clientY;if(dx||dy)navigate('move',false,{dx:Math.max(-300,Math.min(300,dx)),dy:Math.max(-300,Math.min(300,dy))}); });
$('touchpad').addEventListener('pointerup', () => { if(touch && touch.moved<15)navigate('select'); touch=null; });
$('touchpad').addEventListener('pointercancel', () => { touch=null; });
$('volume').oninput = () => scheduleVolume();
$('volume').onchange = () => scheduleVolume(true);
$('volumeDown').onclick = () => { $('volume').value = Math.max(0, Number($('volume').value) - 5); scheduleVolume(); };
$('volumeUp').onclick = () => { $('volume').value = Math.min(100, Number($('volume').value) + 5); scheduleVolume(); };
$('mute').onclick = () => { muted = !muted; renderMute(); scheduleVolume(true); };
$('playPause').onclick = () => command('toggle'); $('pause').onclick = () => command('pause'); $('resume').onclick = () => command('play');
$('back').onclick = () => command('seek', {seconds: -10}); $('forward').onclick = () => command('seek', {seconds: 10});
$('refreshTracks').onclick = () => refreshTracks().catch(error => toast(error.message, true));
$('audioTrack').onchange = () => command('audio', {index: Number($('audioTrack').value)});
$('subtitleTrack').onchange = () => command('subtitle', {index: Number($('subtitleTrack').value)});
for (const id of ['audioTrack', 'subtitleTrack']) $(id).onblur = () => {
  if (trackView?.target === $('target').value) fillTracks(id, id === 'audioTrack' ? trackView.tracks.audio : trackView.tracks.subtitles);
};
for (const id of ['target', 'navTarget']) $(id).onblur = () => { if (state) render(); };
$('audioOn').onclick = () => changeSystem('/api/audio', {action: 'Ligar'}, 'Ligando a rota 5.1…', 55000);
$('routePcm').onclick = () => changeSystem('/api/audio', {action: 'Pcm'}, 'Aplicando PCM USB…', 55000);
$('routeOptical').onclick = () => changeSystem('/api/audio', {action: 'Optical'}, 'Aplicando entrada óptica AC-3…', 55000);
async function openNetflix(mode) {
  const buttons = [$('openNetflix'), $('openNetflixBrowser')];
  if (buttons.some(button => button.disabled)) return;
  buttons.forEach(button => { button.disabled = true; });
  try {
    await api('/api/netflix/open', {mode}, 90000);
    preferredTarget = ''; prefs.set('target', ''); trackKey = '';
    stopRepeat(); navigationQueue.length = 0; preferredNav = ''; prefs.set('nav', '');
    state = null;
    selectTab('remoteTab'); await poll();
    selectNetflixControl();
    toast('Netflix aberta com ajuste 5.1. Use Tab e OK ou o modo mouse para escolher um título.');
  } catch (error) { toast(error.message, true); }
  finally { buttons.forEach(button => { button.disabled = false; }); }
}
$('openNetflix').onclick = () => openNetflix('app');
$('openNetflixBrowser').onclick = () => openNetflix('browser');
$('audioOff').onclick = () => changeSystem('/api/audio', {action: 'Desligar'}, 'Liberando a saída HDMI…', 45000);
$('upmixAuto').onclick = () => changeSystem('/api/audio', {action: 'UpmixAuto'}, 'Usando o formato informado…', 55000);
$('upmixNative').onclick = () => changeSystem('/api/audio', {action: 'Nativo'}, 'Preservando os canais 5.1 originais…', 55000);
$('upmixStereo').onclick = () => changeSystem('/api/audio', {action: 'Stereo'}, 'Aplicando upmix à fonte estéreo confirmada…', 55000);
document.querySelectorAll('[data-profile]').forEach(button => button.onclick = () => changeSystem('/api/profile', {profile: button.dataset.profile}, 'Trocando perfil; o áudio pode parar por alguns segundos…', 90000));
$('reconnect').onclick = () => { poll(); }; $('refreshStatus').onclick = () => poll();
$('jellyForm').onsubmit = async event => {
  event.preventDefault(); const button = $('jellyForm').querySelector('button'); button.disabled = true;
  try {
    await api('/api/jellyfin/login', {username: $('jellyUser').value, password: $('jellyPassword').value});
    $('jellyPassword').value = ''; preferredNav = ''; prefs.set('nav', '');
    toast('Jellyfin conectado. Abra a aba Controle.'); await poll();
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
};
$('logout').onclick = async () => { try { await api('/api/logout', {}); showPair(); } catch (error) { toast(error.message, true); } };
document.addEventListener('keydown', event => {
  if (!paired || $('controls').hidden || event.target.closest('input,select,textarea,button') || event.ctrlKey || event.altKey || event.metaKey) return;
  const action = {ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'select', Escape: 'back'}[event.key];
  if (action) { event.preventDefault(); navigate(action, event.repeat); }
});
document.addEventListener('visibilitychange', () => { stopRepeat(); if (document.hidden) { if (volumeDirty) flushVolume(); } else poll(); });
window.addEventListener('blur', stopRepeat);
poll(); schedulePoll();
