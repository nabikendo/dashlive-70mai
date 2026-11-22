# DashLive — contexto para o Claude Code

App Android (Kotlin) que mostra ao vivo, no tablet (Galaxy Tab S9 FE), o vídeo da dashcam 70mai A200 (DR2210, A200-A1).
Repositório: github.com/nabikendo/dashlive-70mai (público, licença MIT). Falar com o usuário em português.

## A câmera (já descoberto)
- Wi-Fi `70mai_A200_4025`, câmera em `192.168.0.1` (sem internet). RTSP na porta 554, HTTP na 80 (`/cgi-bin/getdeviceattr.cgi` funciona sem login; o resto exige pareamento com o app oficial).
- Vídeo: `rtsp://192.168.0.1:554/livestream/12` (H.264 High 5.1, 640x360, sem áudio). `/livestream/13` e `/liveRTSP/av1,av2,av4,av5,v1` também respondem 200.
- A câmera entrega **um vídeo por vez**: com duas sessões, só uma recebe dados. Os canais 12 e 13 têm SDP idêntico.
- **Pendente:** descobrir qual endereço é a câmera TRASEIRA (o botão "Câmeras" do app deixa testar cada um).

## Código (app/src/main/java/com/dashlive/app)
- `MainActivity.kt`: interface (estilo Android Auto), liga ao Wi-Fi da câmera (`bindProcessToNetwork`), escolhe motor de vídeo, atualização, diagnóstico, botões Câmeras / Estrada / Android Auto.
- `NativePlayer.kt`: RTSP/TCP → RTP → H.264 → MediaCodec na Surface. Reescreve o SPS com VUI (`max_num_reorder_frames=0`) para tirar atraso e usa thread própria de saída. ExoPlayer é só reserva.
- `RoadDetector.kt` + `RoadView.kt`: simulação da estrada estilo Tesla (EfficientDet-Lite0 via TensorFlow Lite Task). Posições/distâncias são estimativas; faixas são fixas.
- `Diagnostics.kt`: testa portas, RTSP (todos os caminhos), TCP/UDP, duas câmeras juntas, HTTP.
- `Updater.kt`: atualização dentro do app pelo GitHub Releases (usa rede com internet, ignora o Wi-Fi da câmera).
- Armadilha: nunca ler `prefs`/contexto em inicializador de campo da Activity (derrubou a v1.1.4).

## Build e publicação
- CI: `.github/workflows/build.yml` (Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24). Baixa o modelo `efficientdet_lite0.tflite` para `app/src/main/assets/` (fora do git).
- A assinatura vem dos segredos `ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD`. A chave NÃO está no repositório.
- Release: criar a tag `vX.Y.Z` no GitHub (Releases → Draft a new release); a Action anexa `DashLive-vX.Y.Z.apk`. Atualizar `appVersionName`/`appVersionCode` padrão em `app/build.gradle.kts`.
- Build local (no PC): precisa de JDK 17 e Android SDK 34; `gradle :app:assembleDebug`.

## Testar no tablet por USB
1. Tablet: Configurações → Sobre o tablet → Informações do software → toque 7x em "Número da versão"; depois Opções do desenvolvedor → Depuração USB.
2. PC: `adb devices` (aceitar a chave no tablet). Instalar: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
3. Conectar o tablet ao Wi-Fi da câmera (a depuração por USB continua funcionando).
4. Logs: `adb logcat -s AndroidRuntime MediaCodec` e `adb logcat | findstr dashlive`. Captura de tela: `adb exec-out screencap -p > tela.png`.

## Lista de testes
- Abre sem fechar; mostra "Ao vivo" e imagem da frontal; medir o atraso (conferir a hora na tela).
- Botão Câmeras: testar as 7 fontes e anotar qual mostra a traseira.
- Botão Estrada: caixas/ícones aparecem? FPS de detecção? `getBitmap` do TextureView com a matriz de encaixe retorna o quadro certo?
- Segunda câmera ao lado (Ajustes) — esperado falhar (uma sessão por vez).
- Tela cheia, reconectar, perda e volta do Wi-Fi da câmera.
- Atualizar (precisa de internet fora do Wi-Fi da câmera); Android Auto abre o Open Headunit (`com.andrerinas.headunitrevived`) se instalado.
