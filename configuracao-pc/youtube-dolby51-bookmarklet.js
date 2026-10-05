(function () {
  'use strict';
  if (location.protocol !== 'https:' || location.hostname !== 'www.youtube.com' || location.pathname !== '/watch') {
    alert('Abra um vídeo em https://www.youtube.com/watch antes de usar este favorito.');
    return;
  }
  const player = document.getElementById('movie_player');
  const settings = window.ytcfg;
  const required = ['setUserAudio51Preference', 'getUserAudio51Preference', 'loadVideoByPlayerVars'];
  if (!player || required.some(name => typeof player[name] !== 'function') || !settings?.get || !settings?.set) {
    alert('Esta versão do player não expôs os controles necessários. Nenhum ajuste foi aplicado.');
    return;
  }
  const config = typeof player.getPlayerConfig === 'function' ? player.getPlayerConfig() : window.ytplayer?.config;
  if (!config?.args) {
    alert('Não foi possível obter a configuração do player para recarregar o áudio. Nenhum ajuste foi aplicado.');
    return;
  }
  document.getElementById('sistema51-youtube-painel')?.remove();
  const keys = ['html5_enable_ac3', 'html5_enable_eac3', 'html5_enable_new_audio_settings_menu'];
  const originalFlags = {...settings.get('EXPERIMENT_FLAGS')};
  const originalArgs = {...config.args};
  const originalPreference = player.getUserAudio51Preference();
  const videoId = new URL(location.href).searchParams.get('v');
  const start = typeof player.getCurrentTime === 'function' ? player.getCurrentTime() : 0;
  const overrides = {html5_enable_ac3: true, html5_enable_eac3: false, html5_enable_new_audio_settings_menu: true};
  const args = {...originalArgs, video_id: videoId, start: String(start)};
  const flags = new URLSearchParams(originalArgs.fflags || '');
  for (const key of keys) flags.set(key, String(overrides[key]));
  args.fflags = flags.toString();
  const panel = document.createElement('section');
  panel.id = 'sistema51-youtube-painel';
  panel.style.cssText = 'position:fixed;right:20px;top:85px;z-index:2147483647;background:#182332;color:#fff;padding:18px;border:1px solid #94a3b8;border-radius:10px;max-width:380px;font:15px system-ui;box-shadow:0 5px 25px #0008';
  const title = document.createElement('strong');
  title.textContent = 'Teste de Dolby Digital 5.1';
  const status = document.createElement('p');
  status.textContent = 'Tentando selecionar AC-3. Isto ainda não confirma a faixa reproduzida.';
  const help = document.createElement('p');
  help.textContent = 'Confira “Estatísticas para nerds”: áudio ac-3 / formato 380 indica a faixa Dolby Digital. Opus / 251 ou AAC / 140 continuam sendo estéreo neste vídeo.';
  const restore = document.createElement('button');
  restore.textContent = 'Desfazer este teste';
  restore.style.cssText = 'padding:8px 12px;background:#e2e8f0;color:#111;border:0;border-radius:5px;cursor:pointer';
  const close = document.createElement('button');
  close.textContent = 'Fechar painel';
  close.style.cssText = restore.style.cssText + ';margin-left:8px';
  let timer;
  let enabled = true;
  restore.onclick = () => {
    enabled = false;
    clearTimeout(timer);
    settings.set('EXPERIMENT_FLAGS', originalFlags);
    config.args = originalArgs;
    player.setUserAudio51Preference(originalPreference, true);
    player.loadVideoByPlayerVars({...originalArgs, video_id: videoId,
      start: String(player.getCurrentTime?.() || start)});
    status.textContent = 'Configuração anterior restaurada para este vídeo.';
    restore.disabled = true;
  };
  close.onclick = () => panel.remove();
  panel.append(title, status, help, restore, close);
  document.body.append(panel);
  try {
    settings.set('EXPERIMENT_FLAGS', {...originalFlags, ...overrides});
    config.args = args;
    player.loadVideoByPlayerVars(args);
    const applyPreference = (remaining) => {
      if (!enabled) return;
      try {
        if (typeof player.setUserAudio51Preference === 'function') {
          player.setUserAudio51Preference(1, true);
          const available = typeof player.hasSupportedAudio51Tracks === 'function'
            ? player.hasSupportedAudio51Tracks() : null;
          const preference = player.getUserAudio51Preference();
          status.textContent = available === true
            ? `Player reconhece faixa 5.1; preferência: ${preference}. Confirme o codec nas estatísticas.`
            : `Preferência: ${preference}. O player ainda não confirmou uma faixa 5.1 utilizável.`;
        }
      } catch (error) { status.textContent = 'Falha ao consultar o player: ' + error.message; }
      if (remaining > 0) timer = setTimeout(() => applyPreference(remaining - 1), 1500);
    };
    timer = setTimeout(() => applyPreference(3), 1000);
  } catch (error) {
    settings.set('EXPERIMENT_FLAGS', originalFlags);
    config.args = originalArgs;
    status.textContent = 'A tentativa falhou: ' + error.message + '. Use Desfazer para restaurar o player.';
  }
})();
