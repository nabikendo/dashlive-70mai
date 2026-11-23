## DashLive 1.8.0-rc.1 · Prévia para teste

- **Colisão frontal (FCW).** A distância estimada usa a lente de 130° da A200. No vídeo de 640 × 360, o cálculo usa focal aproximada de 282 px e considera objetos até 1,8 m de cada lado do eixo da câmera. Para um carro de 1,5 m de altura, o limite teórico de detecção pelo tamanho da imagem é de cerca de 26 m.
- **Saída de faixa (LDW).** Ative o aviso em Ajustes → Assistência. O app procura as duas marcações pintadas da pista e avalia a saída acima de 30 km/h. Com a seta do veículo disponível pelo OBD, o app suprime o aviso de saída para aquele lado. O recurso vem desligado.
- **Som.** Os bipes usam o volume de mídia e podem tocar no modo vibrar. O modo silencioso mantém os avisos sem som.
- **Mapas offline.** Em Ajustes → Mapas offline, selecione a região pela localização ou por um link do OpenStreetMap. O download guarda os blocos concluídos para permitir a retomada. Em municípios grandes, a cobertura alcança até 12 km do centro em cada direção. A Estrada consulta os dados salvos antes de buscar as ruas na internet.
- **Câmeras e navegação.** O botão Câmeras alterna entre a frontal e a traseira da A200. A opção de exibir as duas ao mesmo tempo sai dos Ajustes. O celular ganha uma barra compacta com cinco ações. OBD2, diagnóstico, atualização e Android Auto ficam em Ajustes → Outros.

### Instalação e testes

Baixe o APK anexado a esta prévia. Ele usa o mesmo pacote e a mesma assinatura da distribuição oficial e atualiza a instalação existente. O aplicativo separado “DashLive teste” usa o pacote de depuração.

O relato de teste no Tab S9 FE registra a demonstração do LDW e a leitura offline de 25 blocos da região de Wanderlândia (253 vias, aproximadamente 0,3 MB). A validação no Galaxy S24 FE e em trajeto real com a A200, incluindo a precisão dos alertas e a audibilidade dos bipes, continua pendente. FCW e LDW são experimentais.
