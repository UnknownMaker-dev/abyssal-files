# Abyssal Files — regras do projeto

Repositório: https://github.com/UnknownMaker-dev/abyssal-files (público). O remote `origin` já usa a chave SSH da conta
UnknownMaker-dev, e a identidade dos commits já está configurada neste repo, então não mexa nelas. Para comandos `gh`, use
`GH_TOKEN=$(gh auth token -u UnknownMaker-dev)`.

## Git: sempre, em toda alteração
1. **Antes** de alterar qualquer coisa: `git pull --rebase --autostash`.
2. **Depois** de terminar (build e testes passando): `git add -A && git commit -m "..."` e depois `git push`.
   Não é preciso pedir permissão para isso, porque o usuário pediu que seja sempre assim.
3. Mudanças que não alteram o app (README, docs, CLAUDE.md) também seguem os passos 1 e 2, mas não geram release.

## Releases
Toda mudança no app gera um APK novo e uma release no GitHub com `dist/explorer.apk` anexado.

- `versionCode` sempre sobe 1 a cada release, beta ou estável, para que instale por cima da anterior.
- `versionName` é igual à tag, sem o `v`. Exemplos: `1.8-beta.1` e `1.8`.

Quem decide entre beta e estável é o Claude, seguindo estes critérios:

- **Beta**: tag `vX.Y-beta.N`, publicada com `--prerelease`, sem marcar como latest. Esse é o padrão para:
  - qualquer feature nova ou mudança grande
  - qualquer coisa que só foi testada no emulador e ainda não foi confirmada pelo usuário no celular de verdade
  - correções em cima de uma beta, que viram `beta.N+1`
- **Estável (latest)**: tag `vX.Y`, publicada com `--latest`, quando:
  - o usuário confirmou que a beta funciona no celular, ou
  - a mudança é uma correção pequena e de baixo risco sobre uma versão estável.

  Ao promover uma beta, a estável sai com o mesmo código e um `versionCode` novo.

As notas da release ficam em inglês, como o app: uma lista curta do que mudou e, na beta, o que ainda falta confirmar.

Comando base:
```
GH_TOKEN=$(gh auth token -u UnknownMaker-dev) gh release create vX.Y[-beta.N] dist/explorer.apk \
  --repo UnknownMaker-dev/abyssal-files --title "Abyssal Files X.Y[ beta N]" --notes "..." [--prerelease | --latest]
```

## Build e testes
- `./gradlew assembleRelease testReleaseUnitTest`, depois `cp app/build/outputs/apk/release/app-release.apk dist/explorer.apk`.
- O emulador fica no scratchpad da sessão (AVD `lf`) e roda com `-gpu host`. Depois de instalar, liberar o acesso com
  `adb shell appops set com.abyssal.files MANAGE_EXTERNAL_STORAGE allow`.
- Responder ao usuário em português.
