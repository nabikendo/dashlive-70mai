# DashLive 70mai

O DashLive exibe o vídeo ao vivo da câmera **70mai A200 (DR2210)** em celulares e tablets Android. No modo **Estrada**, você acompanha a posição dos veículos em uma cena 3D ou sobre a imagem da câmera. Um adaptador OBD2 Bluetooth é opcional para consultar dados do carro.

**[Baixar o APK estável](https://github.com/nabikendo/dashlive-70mai/releases/latest)** · [Novidades da v1.8.0](RELEASE_NOTES.md)

## Instalação e primeira conexão

1. Em um aparelho ARM com Android 8.0 ou superior, abra a release estável e baixe o arquivo `DashLive-v1.8.0.apk` em **Assets**. Abra o APK e confirme a instalação no Android.
2. Ligue a A200 e conecte o aparelho à rede Wi-Fi da câmera. Feche o aplicativo da 70mai caso ele esteja usando a transmissão.
3. Abra o DashLive. Use **Câmeras** para alternar entre a frontal e a traseira, se instalada. A A200 transmite uma lente por vez.
4. Para abrir a visualização **Estrada**, selecione a lente frontal. Se o vídeo não carregar, toque em **Reconectar**. A opção **Ajustes → Forçar RTSP por TCP** também está disponível.

Para atualizar uma instalação da versão estável ou da prévia `1.8.0-rc.1`, conecte o aparelho à internet e use **Ajustes → Outros → Verificar atualização**. O Android instala a atualização sobre o DashLive e preserva os ajustes. O aplicativo **DashLive teste** usa um pacote separado.

## Recursos

| Recurso | Como funciona |
| --- | --- |
| Vídeo ao vivo | A A200 envia a imagem por RTSP pela rede Wi-Fi local. Há controles de reconexão, troca de lente e tela cheia. |
| Modo Estrada | O detector identifica veículos e pessoas no próprio aparelho. A cena 3D acompanha as ruas do OpenStreetMap quando há dados disponíveis. Você também pode exibir as detecções sobre o vídeo. |
| Mapas offline | Em **Ajustes → Mapas offline**, escolha a região pela localização ou por um link do OpenStreetMap. O download pode ser retomado, e a Estrada consulta os dados salvos antes de buscar ruas na internet. |
| Painel OBD2 | Com um adaptador ELM327 Bluetooth pareado, o painel mostra velocidade, rotação e outros dados fornecidos pelo veículo. A leitura de setas e faróis é específica do Ford Ka testado. |
| Controles no celular | A barra compacta reúne **Câmeras**, **Reconectar**, **Tela cheia**, **Estrada** e **Ajustes**. OBD2, diagnóstico, atualização e Android Auto ficam em **Ajustes → Outros**. |

## Alertas experimentais

Em **Ajustes → Assistência ao motorista**, você pode ativar o aviso de colisão frontal (FCW) e o aviso de saída de faixa (LDW). A assistência começa desligada. O FCW estima distância e tempo de aproximação pela imagem da câmera frontal. O LDW usa as marcações da pista e, quando o OBD fornece a seta, suprime o aviso para o lado sinalizado.

O teste no Galaxy Tab S9 FE cobre a demonstração de saída de faixa e a leitura de mapas offline. A precisão dos alertas e a audibilidade dos bipes em trânsito com a A200 ainda aguardam validação. O teste físico no Galaxy S24 FE também está pendente.

## Compilar e testar

Use JDK 17, Gradle 8.7 e Android SDK 34. Antes de compilar, baixe o modelo EfficientDet-Lite0 indicado no passo **Baixar modelo de detecção** do [fluxo de build](.github/workflows/build.yml) e salve-o em `app/src/main/assets/efficientdet_lite0.tflite`.

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

O APK de desenvolvimento fica em `app/build/outputs/apk/debug/app-debug.apk`. O GitHub Actions executa os testes unitários, compila o APK de distribuição e usa a chave de release para assiná-lo.

[Licença MIT](LICENSE)
