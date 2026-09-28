# Transmissão local da Google TV

APK transmite tela e áudio reproduzido por apps que **permitem captura** para servidor MediaMTX na LAN. Player WebRTC local e player HLS remoto já vêm no MediaMTX; sem navegador/controle remoto na TV. Conteúdo protegido pode ficar preto ou sem áudio. Não suporta HDMI, antena ou gravação. Acesso remoto exige Cloudflare Access e túnel HLS configurados conforme abaixo.

## Servidor

Após publicar este código no seu Git, instalar no **servidor Linux** em uma linha (substituir URL e IP pelo repositório público e IPv4 LAN do servidor):

```sh
git clone URL_DO_SEU_REPOSITORIO tv-play && cd tv-play && sh install.sh IP_LAN_DO_SERVIDOR
```

Requer `git` e Docker Compose v2. Nova instalação usa senha padrão `123` para `tvpublisher` (APK) e `tvviewer` (web); guarda `.env` com permissões restritas. Ao repetir `sh install.sh` dentro da pasta, preserva `.env` existente, inclusive senhas anteriores. Para trocar senhas, edite `.env` e execute `docker compose up -d --force-recreate`. Não compartilhe `.env`. Senha curta e igual nas duas contas facilita teste, **não protege contra outros dispositivos na LAN**. Portas 8554/TCP, 8889/TCP, 8888/TCP e 8189/UDP+TCP devem ficar livres no IP LAN. Sem TLS: nunca exponha portas diretamente na Internet.

Requer NAS Linux com Docker Compose v2, IP IPv4 LAN reservado no DHCP e portas TCP 8554/8889/8888/8189 e UDP 8189 liberadas **somente na LAN confiável**. WebRTC prioriza UDP 8189; TCP 8189 é fallback ICE para redes que bloqueiam UDP, não substitui banda suficiente. Na raiz do projeto:

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

Na TV, informar `IP do NAS` (IPv4 privado) e **senha de publicação**, selecionar **Iniciar transmissão** e autorizar gravação de áudio e captura de tela. Cada sessão exige novo consentimento. Estado **Transmitindo** só após conexão RTSP; pressionar Home ou mudar de tela não encerra transmissão: **Parar transmissão** na Activity ou na notificação. Senha nunca é salva no aparelho; informe novamente após recriação/processo. Cancelamento de consentimento não inicia serviço. Se TV não fornecer MediaProjection, H.264 hardware ou Opus/captura de áudio, app mostra erro e não substitui por microfone.

Perfil preferencial: 1080p30 com alvo de 4 Mbps; fallback 720p30/2 Mbps apenas quando preparo do encoder 1080p falhar. Durante transmissão, congestão na fila de envio reduz bitrate até 2 Mbps; após três amostras sem congestão (aproximadamente 6 s), aumenta 500 kbps por passo até limite do perfil. De 2 a 4 Mbps, recuperação nominal leva aproximadamente 24 s sem nova congestão; codificador pode não obedecer alvo. App mede bytes RTSP enviados e reduz FPS até 5 quando taxa excede alvo do perfil (+10% para áudio/overhead) ou fila congestiona; recupera FPS gradualmente quando banda permite. Resolução não muda durante sessão. Socket RTSP usa Ktor; timeout de 10 s não limita escrita bloqueada. Se fila cresce sem bytes enviados por aproximadamente 10 s, app tenta reconectar somente o transporte, preservando captura e consentimento. Conexão inicial ou reconexão sem sucesso por 20 s encerra sessão; falhas RTSP permitem até sete tentativas com intervalo de 2 s. Após esgotar tentativas ou revogar projeção, novo consentimento é obrigatório. Reduzir FPS troca fluidez por banda; não garante 30 fps, qualidade fixa ou ausência de pausas em rede lenta.

No navegador **na LAN**, abrir `http://IP_DO_NAS:8889/tv/`, autenticar como `tvviewer` usando **senha de visualização** e ativar som no controle nativo; autoplay inicia mudo. Nunca fornecer senha de publicação ao navegador. Se não houver fonte, player informa ausência e reconecta quando publicação voltar. A meta de atraso ≤ 2 s exige medida com TV e browser físicos na mesma LAN; smoke sintético não comprova latência, suporte da Philips, áudio audível nem conteúdo liberado por outros apps. Compatibilidade de browser/dispositivo permanece não comprovada sem ensaio físico.

## Distribuição remota via cloudflared

TV e MediaMTX continuam na **mesma LAN**: APK publica somente em `rtsp://IP_LAN:8554/tv`; navegador local continua em `http://IP_LAN:8889/tv/` via WebRTC. Um túnel HTTP para `:8889` mostra a página, mas **não transporta mídia WebRTC**: ICE usa UDP 8189 fora do túnel. Para espectadores externos, MediaMTX também entrega LL-HLS na porta 8888. Essa saída é HTTP e funciona no túnel; atraso e compatibilidade de áudio variam, não prometer 1–2 s remotos. Safari com Opus ainda exige teste real.

**Antes de publicar qualquer rota**, no painel Cloudflare Zero Trust crie aplicação **Access → Self-hosted** para hostname dedicado (ex.: `tv.seudominio.com`) com política **Allow só para seus usuários**; habilite **Protect with Access** na configuração da origem do túnel para validar token. Senha de teste `123` sozinha **não protege** transmissão remota. Nunca publique 8554, 8889 ou 8189 no túnel/roteador. No túnel `cloudflared` que roda no servidor, crie **um único hostname público** encaminhando `https://tv.seudominio.com` para serviço HTTP `http://IP_LAN_DO_SERVIDOR:8888` (não `localhost`: Compose vincula porta ao IP LAN). Use IP LAN acessível ao processo cloudflared; se cloudflared roda em contêiner, confira rota entre contêiner e host. Sem serviço cloudflared neste repositório: reaproveite túnel que já administra.

Depois da autenticação Access, abra `https://tv.seudominio.com/tv/`, usuário Basic `tvviewer`, senha de visualização em `.env` (instalação nova: `123`). HLS usa player embutido MediaMTX, sem página extra. Falta de fonte mostra ausência, não vídeo fictício. Se Cloudflare Access não estiver protegendo **todos** os caminhos deste hostname, desative a rota até corrigir política. HTTP entre cloudflared e MediaMTX permanece sem TLS: mantenha ambos na mesma LAN confiável. Não encaminhe caminho `/tv/` sob outro prefixo: playlists e segmentos dependem de caminhos relativos.

Configure regra Cloudflare **Bypass cache** para hostname inteiro: playlists e fragmentos HLS são conteúdo ao vivo privado, não arquivos públicos reutilizáveis. Confira plano/termos Cloudflare para distribuição de vídeo antes de escalar espectadores: CDN Free/Pro/Business pode limitar vídeo sem produto pago ([termos específicos](https://www.cloudflare.com/service-specific-terms-application-services/)).

Checar origem local antes do túnel: `curl -o /dev/null -w '%{http_code}\n' http://IP_LAN_DO_SERVIDOR:8888/tv/` retorna 401 sem login; com `curl -u tvviewer:123` retorna 200. Conferir no navegador **fora da LAN**, atrás de Access, movimento, áudio e retomada ao reiniciar fonte. Latência remota deve ser medida; LL-HLS/Cloudflare não herdam meta WebRTC local.

Referências: [protocolos de hostname no Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/routing-to-tunnel/protocols/), [proteção Cloudflare Access](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/), [HLS MediaMTX](https://mediamtx.org/docs/read/hls), [WebRTC MediaMTX](https://mediamtx.org/docs/features/webrtc-specific-features).

## Check local

Executar **sem transmissão ativa da TV**; check cria e encerra fonte sintética própria, não encerra fonte preexistente. Requer Python 3, FFmpeg/ffprobe com libx264/libopus e Docker já ativo. Exportar variáveis de `.env` sem exibir conteúdo; por exemplo, em shell privado:

```sh
set -a
. ./.env
set +a
python3 checks/smoke.py
```

Check cobre autenticação HTTP WebRTC/HLS e RTSP, publicação sintética 720p30/2 Mbps e 1080p30/4 Mbps, frames realmente decodificados em RTSP/HLS com timestamps e hashes para detectar perdas/congelamento, bitrate de pacotes recebidos em RTSP, recusa de segundo publisher e retomada após parada/reinício da fonte. Falha se fonte preexistente ocupar `/tv`; não a encerra. Não mede desempenho de encoder da TV, adaptação de bitrate do APK, rede Wi-Fi, latência, browser via túnel nem compatibilidade Safari: testar separadamente em hardware real.

## Aceite na Philips

Ensaio realizado na Philips Google TV TA1 (API 31, `STT2.230831.001`): captura real publicada em 1920×1080 H.264/Opus; leitor RTSP decodificou 1.774 quadros em 60 s e áudio, sem erro nesse intervalo. Com Home e dez comandos de navegação, leitor recebeu 529 quadros em 18 s, 415 imagens distintas. Browser Chromium recebeu 1080p e avançou 120 quadros em 4 s; após reinício do MediaMTX, publicação e player retomaram sem novo consentimento e RTSP decodificou 355 quadros em 12 s, sem descartes relatados pelo FFmpeg. Pausa de 14 s do contêiner e retomada posterior também preservaram sessão da TV, mas não simulam perda real de Wi-Fi; ensaio não mede latência ponta a ponta, estabilidade por horas, áudio audível de outro app nem qualidade visual subjetiva. Taxa medida com tela quase estática: ~1,07 Mbps; 4 Mbps é alvo solicitado ao encoder, não teto garantido.

Ensaio adicional: 120 s de RTSP com 30 comandos de navegação decodificaram 3.570 quadros de vídeo e 6.000 blocos de áudio, sem erro FFmpeg nem lacuna de PTS acima de um quadro. Esse cenário confirma continuidade por dois minutos nessa TV/rede, não desempenho em conteúdo de alto movimento constante, latência ou estabilidade por horas.

Ensaio de movimento extremo: página sintética local de ruído aleatório em navegador Vewd da TV, capturada em 1080p. Codec de hardware `c2.mtk.avc.encoder` não oferece CBR; antes do limite por bytes RTSP publicou ~17,49 Mbps em 75 s apesar do alvo de 4 Mbps. Com ajuste de FPS, quatro janelas consecutivas de 10 s publicaram 3,68, 3,65, 3,62 e 3,61 Mbps, com ~5 quadros/s; imagem permaneceu decodificável, mas movimento ficou lento. Após corrigir recuperação de FPS em banda disponível, cinco janelas de 5 s com ruído ficaram entre 3,49 e 3,72 Mbps; ao voltar à Home, janelas de 10 s avançaram de 98 para 168 quadros (16,8 quadros/s) em 50 s, com até 3,18 Mbps. Tela da Home contém recomendações animadas: recuperação integral a 30 fps não foi comprovada nesse cenário. Ruído aleatório é pior que vídeo comum; não inferir qualidade em filmes ou jogos desse ensaio.

Ensaio posterior com recuperação de FPS proporcional: após ruído sintético, cinco janelas consecutivas de 5 s mantiveram vídeo entre 3,54 e 3,71 Mbps a ~5 fps; ao trocar para página estática, janelas de 5 s chegaram a 148–150 quadros (aproximadamente 30 fps) em até 15 s, com ~0,42 Mbps. Reinício do MediaMTX com captura ativa manteve publicação 1080p H.264/Opus e FFmpeg decodificou vídeo/áudio por 12 s após retomada. Não é teste de perda física de Wi-Fi nem de conteúdo protegido.

Com última versão instalada, Chromium na LAN autenticou como `tvviewer`, exibiu Home da Philips em 1920×1080 e avançou 90 quadros em aproximadamente 5 s durante navegação por ADB, sem aumento de descartes reportados pelo browser nessa amostra. Ensaio comprova reprodução local e movimento; não mede atraso ponta a ponta nem qualidade em conteúdo protegido.

Na revisão de transporte, versão Ktor publicada na Philips decodificou 217 quadros de vídeo e áudio em 12 s no leitor RTSP. Versão anterior encerrava captura ao pausar MediaMTX por 33 s; variante Java testada após pausa de 35 s também terminou sem retomada. Versão atual reconecta RTSP sem liberar MediaProjection: após pausa controlada de 27 s, status voltou a **Transmitindo** sem novo consentimento e leitor RTSP decodificou 213 quadros e áudio em 10 s; FFmpeg emitiu aviso de DTS não monotônico nessa primeira leitura. Pausa repetida por 35 s mostrou **Reconectando ao NAS** aos 20 s e depois **Transmitindo** sem novo consentimento; leitor decodificou H.264 1920×1080/Opus e 300 quadros em 10 s, sem erro FFmpeg. Pausa de contêiner não simula perda física de Wi-Fi, nem comprova estabilidade por horas.

Com ADB autorizado, registrar `ro.product.model`, `ro.build.version.sdk` e `ro.build.display.id`. Conferir cancelamento/novo prompt, tela e som de app **separado** com `AudioPlaybackCapture` permitido, duas sessões e dois browsers, Home/mudança de tela sem encerrar publicação, parada pela notificação, perda/restauração de rede e reconexão bounded, fallback 720p2M e bitrate sob limitação de banda, revogação de captura/`RECORD_AUDIO` e ausência de microfone ambiente. Com app de fixture que proíbe captura, vídeo pode seguir e áudio deve ficar silencioso. Medir dez mudanças visuais com gravação externa da TV e browser no mesmo quadro; todos os atrasos até 2 s em LAN saudável. Medir separadamente espectador remoto HLS sob Access, sem aplicar meta de atraso LAN. Sem esses dados, compatibilidade e aceite total permanecem pendentes. Emulador testa lifecycle, não desempenho nem firmware Philips.
