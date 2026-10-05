from pathlib import Path
from urllib.parse import quote
from html import escape

root = Path(__file__).resolve().parent
source = (root / 'youtube-dolby51-bookmarklet.js').read_text(encoding='utf-8')
bookmark = 'javascript:' + quote(source, safe='')
page = '''<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<title>Teste de Dolby Digital 5.1 no YouTube</title>
<style>body{background:#111827;color:#f1f5f9;font:18px system-ui;max-width:850px;margin:50px auto;padding:24px;line-height:1.6}h1{font-size:30px}a{color:#93c5fd}.bookmark{display:inline-block;background:#2563eb;color:white;padding:12px 22px;border-radius:8px;text-decoration:none;font-weight:bold}li{margin-bottom:15px}details{margin-top:30px}pre{font:13px monospace;white-space:pre-wrap;background:#1e293b;padding:15px}small{color:#cbd5e1}</style>
<h1>Teste de Dolby Digital 5.1 no YouTube</h1>
<p>Seu Opera declarou suporte a seis canais, AC-3 e E-AC-3. O vídeo de teste oferece faixas Dolby de seis canais. Este favorito tenta habilitar AC-3 e a preferência por 5.1 no player.</p>
<p><strong>Experimental: ainda não validado na reprodução. O código usa controles internos do YouTube, que podem mudar.</strong></p>
<ol><li>Arraste o botão abaixo para a barra de favoritos do Opera. Clicar nele nesta página não aplica o ajuste.</li>
<li>Abra <a href="https://www.youtube.com/watch?v=nLT8nu-BY6s">o vídeo de teste</a> e clique no favorito recém-criado.</li>
<li>O painel sobre o vídeo informa o que o player reconhece e permite <strong>Desfazer este teste</strong>.</li>
<li>No vídeo, clique com o botão direito e abra <strong>Estatísticas para nerds</strong>. Confira o codec de áudio: <strong>ac-3 / formato 380</strong> é a faixa Dolby Digital; neste vídeo, <strong>Opus / 251</strong> ou <strong>AAC / 140</strong> são estéreo.</li></ol>
<p><a class="bookmark" href="BOOKMARK">YouTube Dolby 5.1 — testar</a></p>
<p>O sistema global mantém os atrasos de 70 ms. O Opera decodifica o áudio e a rota do PC o codifica novamente em Dolby Digital para o UD851B.</p>
<p><small>O favorito opera somente em páginas HTTPS de vídeos do YouTube. Não lê cookies nem envia dados a outros sites. As flags de áudio valem para o player atual; a preferência 5.1 pode persistir no YouTube. Para restaurar, use Desfazer antes de sair do vídeo.</small></p>
<details><summary>Ver o código</summary><pre>SOURCE</pre></details></html>'''
page = page.replace('BOOKMARK', escape(bookmark, quote=True)).replace('SOURCE', escape(source))
(root / 'YouTube Dolby 5.1 - teste.html').write_text(page, encoding='utf-8')
