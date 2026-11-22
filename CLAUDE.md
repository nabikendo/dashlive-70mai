# DashLive — contexto para o Claude Code

App Android (Kotlin) que mostra ao vivo, no tablet (Galaxy Tab S9 FE), o vídeo da dashcam 70mai A200 (DR2210, A200-A1).
Repositório: github.com/nabikendo/dashlive-70mai (público, licença MIT). Falar com o usuário em português.

## A câmera (já descoberto)
- Wi-Fi `70mai_A200_4025`, câmera em `192.168.0.1` (sem internet). RTSP na porta 554, HTTP na 80 (`/cgi-bin/getdeviceattr.cgi` funciona sem login; o resto exige pareamento com o app oficial).
- Vídeo: `rtsp://192.168.0.1:554/livestream/12` (H.264 High 5.1, 640x360, sem áudio). `/livestream/13` e `/liveRTSP/av1,av2,av4,av5,v1` também respondem 200.
- A câmera entrega **um vídeo por vez**: com duas sessões, só uma recebe dados (a outra leva `454 Session Not Found` ou fica sem dados). Os canais 12 e 13 têm SDP idêntico.
- SPS da câmera: High 5.1, 640x368 com corte para 360, 30 fps, `num_ref_frames=1`, **com** VUI (cor + timing) mas **sem** `bitstream_restriction`.
- Testado em 22/11/2026, uma fonte por vez: as 7 fontes (12, 13, av1, av2, av4, av5, v1) mostram a câmera **FRONTAL**. Nenhuma mostrou traseira. Falta confirmar se há câmera traseira ligada na A200.

## Código (app/src/main/java/com/dashlive/app)
- `MainActivity.kt`: interface (estilo Android Auto), liga ao Wi-Fi da câmera (`bindProcessToNetwork`), escolhe motor de vídeo, atualização, diagnóstico, botões Câmeras / Estrada / Android Auto.
- `NativePlayer.kt`: RTSP/TCP → RTP → H.264 → MediaCodec na Surface, com thread própria de saída. ExoPlayer é só reserva.
- `H264Sps.kt` (Kotlin puro, testado em `app/src/test`): lê o SPS e grava `bitstream_restriction` (`max_num_reorder_frames=0`) no VUI, exista VUI ou não. Sem isso o decodificador Exynos segura 23 quadros (`output.delay.value = 23` no logcat).
- `RoadDetector.kt` + `RoadView.kt`: simulação da estrada estilo Tesla (EfficientDet-Lite0 via TensorFlow Lite Task). Posições/distâncias são estimativas; faixas são fixas. O modelo tem que ser a versão da Task Library (4 saídas, `TFLite_Detection_PostProcess`); a do MediaPipe (2 saídas) não abre.
- `MainActivity`: com a segunda câmera ligada, duas quedas da principal em 1 min (depois de algum vídeo chegar) desligam a segunda sozinho.
- Armadilha: `TextureView.getBitmap(w, h)` IGNORA a matriz de `setTransform` e devolve o quadro inteiro esticado em w×h. Peça na proporção do vídeo, sem recortar. No build debug, ligar a Estrada salva o 1º quadro do detector em `/sdcard/Android/data/com.dashlive.app.debug/files/road_frame.png`.
- Medido em 22/11/2026 (Tab S9 FE, canal 12): atraso de 1,7–2,0 s na v1.4.0 → 0,8–1,2 s com o SPS corrigido; Estrada a ~12 det/s.
- `libtask_vision_jni.so` (tensorflow-lite-task-vision 0.4.4) não é alinhada a 16 KB: o Android avisa no build debug. Funciona no tablet atual; vira problema em aparelhos com páginas de 16 KB.
- `Diagnostics.kt`: testa portas, RTSP (todos os caminhos), TCP/UDP, duas câmeras juntas, HTTP.
- `Updater.kt`: atualização dentro do app pelo GitHub Releases (usa rede com internet, ignora o Wi-Fi da câmera).
- Armadilha: nunca ler `prefs`/contexto em inicializador de campo da Activity (derrubou a v1.1.4).

## Build e publicação
- CI: `.github/workflows/build.yml` (Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24). Baixa o modelo `efficientdet_lite0.tflite` para `app/src/main/assets/` (fora do git).
- A assinatura vem dos segredos `ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD`. A chave NÃO está no repositório.
- Release: criar a tag `vX.Y.Z` no GitHub (Releases → Draft a new release); a Action anexa `DashLive-vX.Y.Z.apk`. Atualizar `appVersionName`/`appVersionCode` padrão em `app/build.gradle.kts`.
- Build local (no PC): precisa de JDK 17 e Android SDK 34; `gradle :app:testDebugUnitTest :app:assembleDebug`. Neste PC as ferramentas estão em `C:\Users\Ariane\dev-tools` (jdk17, gradle-8.7, android-sdk, mingit): defina `JAVA_HOME` e `ANDROID_HOME` para elas.
- O build debug é `com.dashlive.app.debug` ("DashLive teste") e instala ao lado do app oficial (a chave de release é outra; `adb install -r` por cima do oficial falharia).

## Testar no tablet por USB
1. Tablet: Configurações → Sobre o tablet → Informações do software → toque 7x em "Número da versão"; depois Opções do desenvolvedor → Depuração USB.
2. PC: `adb devices` (aceitar a chave no tablet). Instalar: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
3. Conectar o tablet ao Wi-Fi da câmera (a depuração por USB continua funcionando).
4. Logs: `adb logcat -s AndroidRuntime DashLive TaskJniUtils` (o app registra com a tag `DashLive`). Captura de tela no PowerShell 5.1: `adb shell screencap -p /sdcard/tela.png` e `adb pull /sdcard/tela.png` (o `>` do PowerShell corrompe o PNG).
5. Atraso: compare o relógio gravado no vídeo com o relógio da câmera (`devts` de `/cgi-bin/getdeviceattr.cgi`, lido pelo `nc` do tablet) no instante da captura.

## Lista de testes
- Abre sem fechar; mostra "Ao vivo" e imagem da frontal; medir o atraso (conferir a hora na tela).
- Botão Câmeras: testar as 7 fontes e anotar qual mostra a traseira.
- Botão Estrada: caixas/ícones aparecem? FPS de detecção? `getBitmap` do TextureView com a matriz de encaixe retorna o quadro certo?
- Segunda câmera ao lado (Ajustes) — esperado falhar (uma sessão por vez).
- Tela cheia, reconectar, perda e volta do Wi-Fi da câmera.
- Atualizar (precisa de internet fora do Wi-Fi da câmera); Android Auto abre o Open Headunit (`com.andrerinas.headunitrevived`) se instalado.
