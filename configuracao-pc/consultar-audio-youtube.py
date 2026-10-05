"""Consulta somente metadados públicos; não baixa áudio nem lê cookies."""
import json
import re
import urllib.request
from pathlib import Path

video_id = 'nLT8nu-BY6s'
request = urllib.request.Request(
    f'https://www.youtube.com/watch?v={video_id}',
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36',
             'Accept-Language': 'pt-BR,pt;q=0.9'})
with urllib.request.urlopen(request, timeout=15) as response:
    html = response.read().decode('utf-8', errors='replace')
match = re.search(r'(?:var\s+)?ytInitialPlayerResponse\s*=\s*', html)
if not match:
    raise RuntimeError('A página não expôs ytInitialPlayerResponse.')
player, _ = json.JSONDecoder().raw_decode(html[match.end():])
streams = player.get('streamingData', {})
audio = []
for fmt in streams.get('formats', []) + streams.get('adaptiveFormats', []):
    if fmt.get('audioChannels') or fmt.get('mimeType', '').startswith('audio/'):
        audio.append({key: fmt.get(key) for key in
                      ('itag', 'mimeType', 'audioChannels', 'audioSampleRate', 'audioQuality')})
        audio[-1]['hasDirectUrl'] = bool(fmt.get('url'))
        audio[-1]['hasSignatureCipher'] = bool(fmt.get('signatureCipher'))
result = {
    'videoId': video_id,
    'title': player.get('videoDetails', {}).get('title'),
    'status': player.get('playabilityStatus', {}).get('status'),
    'reason': player.get('playabilityStatus', {}).get('reason'),
    'audioFormats': audio,
    'note': 'Resposta pública do cliente web, não medição da faixa reproduzida na sessão do usuário.'
}
output = Path(__file__).with_name('youtube-metadados-web.json')
output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))

js_match = re.search(r'"jsUrl"\s*:\s*"([^"]+)"', html)
if js_match:
    js_url = json.loads('"' + js_match.group(1) + '"')
    if js_url.startswith('/s/player/'):
        with urllib.request.urlopen('https://www.youtube.com' + js_url, timeout=15) as response:
            source = response.read().decode('utf-8', errors='replace')
        contexts = []
        for pattern in ('p4x=function', 'pt=function', 'CHANNELS:{', 'html5_enable_ac3'):
            for occurrence in list(re.finditer(re.escape(pattern), source))[:3]:
                contexts.append(source[max(0, occurrence.start()-80):occurrence.end()+900])
        flags = sorted(set(re.findall(r'html5_\w*(?:audio|ac3|eac3|surround)\w*', source)))
        contexts.append('Flags de áudio encontradas: ' + ', '.join(flags))
        Path(__file__).with_name('youtube-player-audio-contextos.txt').write_text(
            '\n\n'.join(contexts), encoding='utf-8')
        print('\nControles de áudio no player público:\n' + '\n\n'.join(contexts))
