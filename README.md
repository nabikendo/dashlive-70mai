# DashLive 70mai

App Android para ver ao vivo, no tablet, a câmera 70mai A200 (DR2210).

## O que foi descoberto no app oficial (v4.5.0)
- O A200 aparece como "70mai Dash Cam A200", tipo 70, sem Bluetooth, e usa chip **HiSilicon**.
- IP da câmera na rede Wi-Fi dela: `192.168.0.1`
- Vídeo ao vivo: `rtsp://192.168.0.1:554/livestream/12` (frontal). O app oficial também conhece `13` e
  `/liveRTSP/av1, av2, av4, av5, v1`, mas na A200 testada todos mostram a frontal
- A câmera envia **um vídeo por vez**: deixe a "segunda câmera ao lado" desligada
- Comandos HTTP no estilo `http://192.168.0.1/.../getdeviceattr.cgi` (não usados ainda)

## Como usar
1. Ligue a câmera e conecte o tablet ao Wi-Fi `70mai Dash Cam A200-XXXX`.
2. Feche o app oficial da 70mai (a câmera pode aceitar só um cliente).
3. Abra o DashLive. Toque no vídeo para mostrar ou esconder os botões.
4. Se não abrir: **Ajustes → Forçar RTSP por TCP**, ou teste o canal 13.

## Compilar
Envie esta pasta para um repositório no GitHub. A Action `Build APK` gera o APK
em *Actions → última execução → Artifacts*.

No PC (JDK 17, Android SDK 34, Gradle 8.7): baixe o modelo para `app/src/main/assets/efficientdet_lite0.tflite`
(primeira URL em `.github/workflows/build.yml`) e rode `gradle :app:testDebugUnitTest :app:assembleDebug`.
O APK de teste se chama "DashLive teste" (`com.dashlive.app.debug`) e instala ao lado do app oficial.
