# Backend local de configuração do player Netflix

Este componente salvou as seis opções do ajuste no perfil Default real em
03/10/2026. A aplicação inseriu uma única preferência `cadmiumconfig`, e o app foi
reaberto. Porém, o usuário informou que a Netflix continuava sem oferecer 5.1.
A preferência gravada foi conferida novamente e não foi suficiente na sessão
real. Este backend permanece como registro e para reverter essa preferência.
A solução em uso passou a enviar os parâmetros de URL ao app instalado, pelo
atalho Netflix - Dolby 5.1. O usuário confirmou o 5.1 sem favorito tanto na
abertura direta do episódio quanto na abertura pela página inicial.

## Escopo

- Lê e modifica somente `cadmiumconfig`, nos hosts `www.netflix.com`,
  `.netflix.com` e `netflix.com`, com caminho `/`.
- Mescla as seis opções em CSV ASCII sem codificação de URL, preservando os
  parâmetros extras de cada linha existente.
- Quando não existe nenhuma linha alvo, cria uma única linha para o host
  `www.netflix.com`, HTTPS, Secure, SameSite=Lax, com validade de 365 dias.
- O backup JSON contém exclusivamente as linhas desse cookie de configuração.
  Outros cookies não são selecionados, exportados ou restaurados.
- Não descriptografa nada. Um `cadmiumconfig` criptografado, particionado ou um
  schema desconhecido cancela a operação antes de gerar o backup.
- Aceita somente a versão 24 do schema Chromium, identificada em `meta`, com as
  vinte colunas do Chromium ou a variante de vinte e duas colunas do Edge.
  As extras `is_edgelegacycookie` e `browser_provenance` exigem INTEGER, permitem
  NULL e têm DEFAULT 0; uma linha nova utiliza 0 em ambas.
  Metadados ausentes, versões
  diferentes, colunas extras ou faltantes cancelam a operação.

## Uso

O script exige que o Edge e o app Netflix estejam completamente fechados. O modo
padrão é diagnóstico, sem alterações:

```powershell
C:\Python314\python.exe .\cadmiumconfig.py
C:\Python314\python.exe .\cadmiumconfig.py --apply --backup .\meu-backup.json
C:\Python314\python.exe .\cadmiumconfig.py --restore .\meu-backup.json
```

`--cookie-db` permite indicar explicitamente o arquivo Cookies de outro perfil.
`--apply` e `--restore` são mutuamente exclusivos. A integração deverá guardar o
caminho do backup retornado pelo comando de aplicação para oferecer reversão.

O backup é salvo antes da primeira escrita. As alterações ocorrem em uma única
transação SQLite. A restauração exige que o cookie ainda corresponda ao ajuste
salvo; uma alteração posterior de configuração cancela a restauração inteira.
Mudanças normais nos timestamps de acesso/atualização são toleradas.

## Testes e limites

```powershell
C:\Python314\python.exe -m unittest -v test_cadmiumconfig.py
```

Os testes usam apenas bancos SQLite temporários sem autenticação ou dados de
conta. Eles verificam mesclagem, criação/restauração, variantes de domínio,
criptografia rejeitada, schema desconhecido, versão e metadados obrigatórios,
particionamento, conflitos de
restauração, backup protegido, rollback, bloqueio com Edge aberto e escopo das
consultas.

A possibilidade de gravar texto simples em `value` vem do schema Chromium e do
teste `OverridePlaintextValue`. Foi validada no Edge 154 instalado, em um perfil
isolado, com o CSV exato das seis opções e os dois campos extras em zero. Os
vinte testes do backend passaram. Se o navegador regravar esse cookie criptografado,
a restauração detectará a mudança e cancelará, sem tentar decifrá-lo. Limpar os
cookies da Netflix remove esta preferência. As opções dependem do player atual;
títulos e idiomas sem faixa 5.1 continuam com sua faixa disponível.
