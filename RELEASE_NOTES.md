## DashLive 1.8.1

Correções na Estrada 3D, no aviso de Wi-Fi da câmera e no layout do painel.

- **Estrada 3D.** Se a renderização falhar, a Estrada volta para a cena 2D e registra o erro, em vez de continuar tentando desenhar. No modo demonstração, a velocidade simulada de 62 km/h é sempre aplicada.
- **Wi-Fi da câmera.** Enquanto o tablet procura a câmera, a Estrada mostra "Procurando veículos." Tocar no cartão de status abre o painel de Wi-Fi do Android.
- **Painel.** No tablet, o aviso de estado deixa de cobrir a tela quando a Estrada está aberta.
- **Build de debug.** Não mostra o botão de atualização e não verifica atualizações automaticamente.
- **Teste de Estrada.** Branches `fix/*` geram também um APK separado (`DashLive-estrada.apk`) para testes isolados.

Validação: testes unitários (70) e build de debug passaram. O comportamento no tablet e no carro ainda aguarda teste.

## DashLive 1.8.0

Esta versão estável traz mapas offline, controles compactos para celular e dois alertas experimentais que usam a câmera frontal da 70mai A200.

- **Mapas offline.** Em **Ajustes → Mapas offline**, escolha a região pela localização ou por um link do OpenStreetMap. Se o download for interrompido, o app aproveita os blocos já salvos. A Estrada consulta o pacote local antes da internet.
- **Câmeras e controles.** O botão **Câmeras** alterna entre a frontal e a traseira. No celular, as cinco ações principais ficam na barra compacta. OBD2, diagnóstico, atualização e Android Auto ficam em **Ajustes → Outros**.
- **Colisão frontal (FCW).** A estimativa de distância leva em conta a lente de 130° da A200 e a posição lateral do objeto em relação ao carro.
- **Saída de faixa (LDW).** Acima de 30 km/h, o app usa as duas marcações da pista para avaliar a saída de faixa. Quando a seta está disponível pelo OBD, o app suprime o aviso para o lado sinalizado.
- **Som.** Os bipes usam o volume de mídia e podem tocar no modo vibrar. No modo silencioso, os avisos ficam sem som.

### Instalação e atualização

Baixe **DashLive-v1.8.0.apk** nos arquivos desta release. Quem já usa a v1.7.0 ou a prévia v1.8.0-rc.1 também pode atualizar pelo próprio DashLive, com acesso à internet. A versão de distribuição usa o pacote `com.dashlive.app`.

### Validação

O teste no Galaxy Tab S9 FE cobre a demonstração do LDW e a leitura offline de 25 blocos de Wanderlândia, com 253 vias e aproximadamente 0,3 MB. A assistência começa desligada. A precisão dos alertas e a audibilidade dos bipes em trajeto real com a A200, assim como o teste físico no Galaxy S24 FE, ainda aguardam validação.
