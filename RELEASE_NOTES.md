## DashLive 1.10.0

Estrada mais limpa e diagnóstico para gravações.

- **Diagnóstico de gravação.** Em Ajustes → Diagnóstico, liga o registro de dados (CSV, uma linha por objeto detectado) e uma foto a cada 3 s, para análise depois.
- **Estrada.** O seu carro não aparece mais na Estrada. As faixas da 3D ficaram mais largas, e veículos muito perto são desenhados com distância mínima.
- **Caminhões próximos.** Um caminhão a menos de 15 m é desenhado como carro, para evitar um bloco colado na câmera.
- **Filtro de falsos positivos.** Caixas muito pequenas (postes, reflexos) são descartadas.
- **Modo de teste com a câmera do celular** (só no build de debug). Abre em 2D, com o vídeo preenchendo a tela.

Limitações conhecidas: a cena 3D com a câmera do celular ainda não reflete a posição real dos veículos; a validação com a A200 no carro ainda precisa ser feita.

## DashLive 1.9.1

Correções na Estrada com trânsito real.

- **Carros estacionados.** Veículos fora da pista ficam no acostamento, em fileiras com posições diferentes, e não mais na faixa da direita.
- **Curvas mais suaves.** O traçado das ruas é suavizado, mantendo o mesmo número de pontos.
- **Alerta de colisão.** Carros vindo de frente (contramão) não disparam mais o alerta.

Limitações conhecidas: pistas separadas por canteiro ainda são desenhadas como uma pista só, e a regra de carros de frente funciona à noite, não de dia.

## DashLive 1.9.0

Estrada com mais trânsito, carros vindo de frente e correções visuais.

- **Carros na faixa.** Veículos ficam sempre no centro da faixa, dentro do asfalto.
- **Visual mais limpo.** Carros em tons de cinza, cartão da Estrada só com o nome da rua. Os números de detecção ficam só no build de teste.
- **Novos veículos.** Caminhão longo e ônibus usam um modelo de caminhão mais comprido.
- **Ultrapassagem.** A Estrada mostra carros passando pela faixa da esquerda.
- **Carros de frente (experimental).** Carro que vem de frente, com faróis brancos, aparece virado para você na faixa da esquerda. A detecção ainda precisa de calibração com vídeo real.
- **Câmera de ré.** Em Ajustes → Câmera de ré, você pode mostrar a traseira ao ligar a seta. O padrão é desligado, e o tempo depois da seta é configurável. A Estrada também funciona com a lente de ré.
- **Ajustes → Sobre.** Endereço, motor de vídeo e versão saíram da tela principal.
- **Rota de teste (build de teste).** Anda por um arquivo GeoJSON a 60 km/h.
- **Etiquetas de distância.** Não se sobrepõem mais, e um objeto que sumiu não mantém etiqueta.

Limitações conhecidas:
- O alerta de colisão (FCW) ainda trata carro de frente como qualquer objeto. Pode disparar com um carro vindo de frente na sua faixa.
- Os números dos carros de frente e das lanternas precisam de calibração com vídeo real.
- A câmera de ré e a troca pela seta ainda não foram testadas no carro.

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
