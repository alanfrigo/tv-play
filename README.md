# Transmissão local da Google TV

APK transmite tela e áudio reproduzido por apps que **permitem captura** para servidor MediaMTX na LAN. Player WebRTC já vem no MediaMTX; sem navegador/controle remoto na TV. Conteúdo protegido pode ficar preto ou sem áudio. Não suporta HDMI, antena, gravação ou acesso pela Internet.

## Servidor

Após publicar este código no seu Git, instalar no **servidor Linux** em uma linha (substituir URL e IP pelo repositório público e IPv4 LAN do servidor):

```sh
git clone URL_DO_SEU_REPOSITORIO tv-play && cd tv-play && sh install.sh IP_LAN_DO_SERVIDOR
```

Requer `git` e Docker Compose v2. Nova instalação usa senha padrão `123` para `tvpublisher` (APK) e `tvviewer` (web); guarda `.env` com permissões restritas. Ao repetir `sh install.sh` dentro da pasta, preserva `.env` existente, inclusive senhas anteriores. Para trocar senhas, edite `.env` e execute `docker compose up -d --force-recreate`. Não compartilhe `.env`. Senha curta e igual nas duas contas facilita teste, **não protege contra outros dispositivos na LAN**. Portas 8554/TCP, 8889/TCP e 8189/UDP devem ficar livres no IP LAN. Sem TLS: nunca exponha portas na Internet.

Requer NAS Linux com Docker Compose v2, IP IPv4 LAN reservado no DHCP e portas TCP 8554/8889 e UDP 8189 liberadas **somente na LAN confiável**. Na raiz do projeto:

```sh
cp .env.example .env
# preencher NAS_IP no .env; senhas de teste já vêm como 123
```

Preencher `NAS_IP` com IPv4 real do NAS. `.env` é ignorado pelo Git. Não abrir portas no roteador: HTTP Basic e RTSP aqui **não usam TLS**; tráfego e credenciais podem ser observados na LAN. Rede não confiável exige HTTPS/RTSPS antes de exposição.

```sh
docker compose config --quiet
docker compose run --rm mediamtx --validate-conf=/mediamtx.yml
docker compose up -d
curl -s -o /dev/null -w '%{http_code}\n' "http://IP_DO_NAS:8889/tv/" # 401 sem credenciais
```

Conflito de porta falha explicitamente; não trocar porta sem atualizar contrato da TV. Configuração recusa segundo publisher e não cria vídeo quando TV encerra. Para diagnóstico do servidor, `docker compose logs mediamtx`; não incluir senhas em comandos compartilhados/logs.

## APK

Requer JDK 17, Android SDK platform 37 (`platforms;android-37.0` no SDK atual), build-tools 36.0.0, Android TV API 29+ e consentimento na TV. Build:

```sh
JAVA_HOME=/caminho/para/jdk17 ANDROID_HOME=/caminho/para/sdk ./gradlew :app:assembleDebug
adb devices -l
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -n com.tvplay/.MainActivity
```

Na TV, informar `IP do NAS` (IPv4 privado) e **senha de publicação**, selecionar **Iniciar transmissão** e autorizar gravação de áudio e captura de tela. Cada sessão exige novo consentimento. Estado **Transmitindo** só após conexão RTSP; pressionar Home para exibir conteúdo. **Parar transmissão** na Activity ou na notificação. Senha nunca é salva no aparelho; informe novamente após recriação/processo. Cancelamento de consentimento não inicia serviço. Se TV não fornecer MediaProjection, H.264 hardware ou Opus/captura de áudio, app mostra erro e não substitui por microfone.

No navegador, abrir `http://IP_DO_NAS:8889/tv/`, autenticar como `tvviewer` usando **senha de visualização** e ativar som no controle nativo; autoplay inicia mudo. Nunca fornecer senha de publicação ao navegador. Se não houver fonte, player informa ausência e reconecta quando publicação voltar. A meta de atraso ≤ 2 s exige medida com TV e browser físicos na mesma LAN; smoke sintético não comprova latência, suporte da Philips, áudio audível nem conteúdo liberado por outros apps.

## Check local

Executar **sem transmissão ativa da TV**; check cria e encerra fonte sintética própria, não encerra fonte preexistente. Requer Python 3, FFmpeg/ffprobe com libx264/libopus e Docker já ativo. Exportar variáveis de `.env` sem exibir conteúdo; por exemplo, em shell privado:

```sh
set -a
. ./.env
set +a
python3 checks/smoke.py
```

Check cobre autenticação HTTP e RTSP, H.264/Opus, decodificação de vídeo/áudio e recusa de segunda publicação sem derrubar primeira. Browser WebRTC e TV física exigem verificação separada.

## Aceite na Philips

Com ADB autorizado, registrar `ro.product.model`, `ro.build.version.sdk` e `ro.build.display.id`. Conferir cancelamento/novo prompt, tela e som de app **separado** com `AudioPlaybackCapture` permitido, duas sessões e dois browsers, Home e notificação, perda/restauração de rede, revogação de captura/`RECORD_AUDIO` e ausência de microfone ambiente. Com app de fixture que proíbe captura, vídeo pode seguir e áudio deve ficar silencioso. Medir dez mudanças visuais com gravação externa da TV e browser no mesmo quadro; todos os atrasos até 2 s em LAN saudável. Sem esses dados, compatibilidade e aceite total permanecem pendentes. Emulador testa lifecycle, não desempenho nem firmware Philips.
