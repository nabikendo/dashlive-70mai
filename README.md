# DashLive 70mai

App Android para ver ao vivo, no tablet, a câmera 70mai A200 (DR2210).

## O que foi descoberto no app oficial (v4.5.0)
- O A200 aparece como "70mai Dash Cam A200", tipo 70, sem Bluetooth, e usa chip **HiSilicon**.
- IP da câmera na rede Wi-Fi dela: `192.168.0.1`
- Vídeo ao vivo: `rtsp://192.168.0.1:554/livestream/12` (o `13` é um segundo canal)
- Comandos HTTP no estilo `http://192.168.0.1/.../getdeviceattr.cgi` (não usados ainda)

## Como usar
1. Ligue a câmera e conecte o tablet ao Wi-Fi `70mai_A200_…`.
2. Feche o app oficial da 70mai (a câmera pode aceitar só um cliente).
3. Abra o DashLive. Toque no vídeo para mostrar ou esconder os botões.
4. Se não abrir: **Ajustes → Forçar RTSP por TCP**, ou teste o canal 13.

## Compilar
Envie esta pasta para um repositório no GitHub. A Action `Build APK` gera o APK
em *Actions → última execução → Artifacts*.
