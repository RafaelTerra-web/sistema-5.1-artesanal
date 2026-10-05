# Manifesto da publicação

O repositório recebe a implementação desenvolvida e a documentação para reutilização. A pasta de trabalho também contém material temporário, dados de execução e downloads. A exportação foi feita por uma lista explícita de tipos e diretórios no `.gitignore`.

## Incluído

- Fontes C#, PowerShell, Python, JavaScript, HTML e CSS do projeto.
- Iniciadores `.cmd`, configurações de referência e arquivos APO desenvolvidos.
- Controle remoto completo, seus adaptadores, site e fontes das extensões.
- Testes e geradores de sinais, sem os arquivos de áudio gerados.
- Relatórios escritos durante o desenvolvimento e a análise do UD851B.
- Exemplos do preset LFE e dos dois perfis mpv, com GUID de HDMI substituído por marcador.
- Guia de arquitetura, instalação, reaproveitamento e estado de validação.

Os fontes antigos presentes na raiz de `configuracao-pc/` também foram preservados como referências de evolução. Cópias repetidas em diretórios de backup e validação não foram duplicadas no Git. A documentação original pode apontar para relatórios JSON/logs locais excluídos; use as páginas de `docs/` para o panorama do pacote publicado.

## Mantido somente no PC

| Categoria | Exemplos | Motivo |
| --- | --- | --- |
| Credenciais de controle | `connection-private.json`, `extension/connection.json`, PIN/chave em `endereco.txt` | Cada instalação gera seus próprios valores |
| Navegador e conta | Cookies, Login Data, Local State, perfis de teste/cópias do Edge | Não são código do projeto |
| Estado e recuperações | PIDs, flags, estados JSON, backups locais de preferências | Pertencem à execução/máquina de origem |
| Configuração ativa | Os dois `.conf` mutáveis e `equalizador-lfe.json` | Exemplos versionados substituem o estado vivo |
| Artefatos gerados | Logs, WAV, raw, SPDIF, capturas e saídas de teste | Geradores/fontes permanecem disponíveis |
| Dependências | mpv, SoundVolumeView, instaladores, yt-dlp e bibliotecas de análise | Instalar pela origem apropriada |
| Materiais de terceiros | Player Netflix completo, firmware, rootfs e páginas/cotações baixadas | Relatórios e links de referência são suficientes para explicar o trabalho |

Essa seleção não apaga os arquivos locais. Ela impede que entrem no primeiro commit e nas publicações seguintes.

## Conferir antes de outro push

```powershell
git add .
node .\scripts\verificar-publicacao.mjs
git diff --cached --stat
```

O verificador lê somente os arquivos do índice Git, não despeja conteúdo nem segredos no terminal, rejeita categorias proibidas e busca formatos comuns de tokens. Quando há estado local do controle, também verifica se sua chave atual foi copiada literalmente para algum arquivo versionado. A checagem complementa a seleção de arquivos; não garante identificar qualquer segredo possível.

O repositório foi criado privado na conta autenticada do autor. A licença de redistribuição das dependências é independente dos fontes próprios; nenhum executável de terceiro foi incorporado.


## Documentação do plano A34 — 05/10/2026

Incluídos plano aprovado, diagramas, orçamento em reais, comparação com J1800, pesquisa de interfaces e roteiro de programação/validação. As referências de preços não são cotações novas; as condições de hardware continuam explícitas. Dados de entrega, credenciais, transcrição privada da conversa e arquivos baixados de terceiros não foram acrescentados. Os documentos contêm links e síntese técnica, sem binários ou implementação Android pronta.

A criação privada descrita acima é histórica; o usuário tornou o repositório público antes desta atualização.
