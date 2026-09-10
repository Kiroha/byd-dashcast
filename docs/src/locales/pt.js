export default {
  code: 'pt',
  flag: '🇧🇷',
  name: 'Português (Brasil)',
  title: 'DashCast — Manual do usuário',
  manualName: 'Manual do usuário',
  meta: 'BYD Seal / Dolphin / Atto 3 · DiLink 3 e DiLink 5 · Android 10–13',
  tocTitle: '📋 Sumário',

  intro: {
    title: '0. Introdução',
    lead:
      'O DashCast mostra qualquer aplicativo Android da tela central do seu BYD no painel de instrumentos (a tela digital atrás do volante). Maps, Waze, Spotify ou ABRP bem à sua frente — e, no modo Layouts, vários apps ao mesmo tempo, cada um na sua zona. Instala como um app normal e não altera nada no sistema.',
    bullets: [
      '✅ Funciona no DiLink 3 (Seal EU / 6125F) e no DiLink 5 (centrais BYD mais novas).',
      '✅ Sem alteração de sistema: o DashCast instala como qualquer outro app.',
      '✅ ADB local por TCP — sem computador depois da primeira autorização.',
      '✅ 14 idiomas de interface, escolhidos na primeira abertura, alteráveis a qualquer momento.',
      '✅ Espelho tátil em tempo real: controle o cluster pela tela central.',
      '✅ Modo Layouts: vários apps lado a lado no cluster, zonas desenhadas com o dedo.',
      '✅ Início automático: projeção + app (ou layout favorito) assim que o DashCast abre.',
      '✅ Margens (overscan) lembradas por aplicativo.',
      '✅ Setas de conversão no HUD do para-brisa no DiLink 3 (firmware compatível).',
      '✅ Assistente de hotspot Wi-Fi integrado para DiLink 3 (use o seu próprio chip).',
      '✅ Relato de problemas sem teclado: um toque envia o diagnóstico ao suporte.',
      '✅ Atualizações OTA automáticas que instalam em silêncio e reabrem o app.',
    ],
    note:
      '💡 Único pré-requisito: ative a depuração ADB sem fio em Ajustes BYD → Opções do desenvolvedor. Na primeira abertura aparece “Permitir depuração?” — marque “Sempre permitir deste computador” e confirme. Você não precisa fazer isso de novo.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Tela de boas-vindas — escolha do idioma',
      lead:
        'Na primeira abertura, o DashCast mostra uma grade com os 14 idiomas disponíveis. Toque no seu: a escolha é lembrada e a tela não volta a aparecer. Dá para mudar o idioma a qualquer momento em Configurações.',
      mockupLabel: 'Ver tela 1 (Boas-vindas)',
      featuresTitle: 'Detalhes',
      features: [
        {
          title: '14 idiomas suportados',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Português (Brasil), Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. O idioma escolhido vale na hora, sem reiniciar.",
        },
        {
          title: 'Direção de leitura automática',
          text:
            'O árabe passa automaticamente para o layout da direita para a esquerda (RTL): a barra de navegação vai para a direita e as listas são espelhadas.',
        },
        {
          title: 'Alterável a qualquer momento',
          text: 'Para mudar o idioma depois: Configurações → Idioma. Aplica na hora.',
        },
      ],
      howTo: {
        title: 'Como fazer',
        steps: [
          'Abra o DashCast (ícone azul na gaveta de apps da BYD).',
          'A tela de boas-vindas aparece com a grade de idiomas.',
          'Toque no seu idioma. A interface muda na hora.',
          'A tela principal abre — pronto.',
        ],
      },
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Tela principal — Apps e Cluster',
      lead:
        'A tela inicial do DashCast. À esquerda: todos os seus aplicativos com busca, filtros e favoritos, além da barra lateral. À direita: a prévia ao vivo do cluster, os botões Espelho em tela cheia / Parar projeção e — no modo Layouts — o seletor recolhível “Layout do cluster”.',
      mockupLabel: 'Ver tela 2 (Principal)',
      featuresTitle: 'Tudo o que você pode fazer',
      features: [
        {
          title: '👆 Toque curto — projetar',
          text:
            'Toque em um app para enviá-lo ao cluster. Se a projeção não estiver ativa, ela começa sozinha (~2 s de aquecimento) e o app aparece atrás do volante.',
        },
        {
          title: '👆⏱️ Toque longo — menu de ações',
          text:
            'Segure um app: ⭐ Favorito, Abrir automaticamente (projetar este app a cada abertura do DashCast), Mover para o cluster / tela principal, ✕ Forçar encerramento.',
        },
        {
          title: '🔍 Busca e filtros',
          text:
            'A barra de busca filtra enquanto você digita (nome ou pacote). Os chips de categoria (Todos / Navegação / Mídia…) agrupam os apps; o botão ▦ alterna lista/grade.',
        },
        {
          title: '🚦 Prévia ao vivo do cluster',
          text:
            'O painel da direita espelha o que está no painel de instrumentos, ao vivo. Seus toques na prévia vão para o app projetado — rolar, zoom, teclado, tudo funciona.',
        },
        {
          title: '👁️ Espelho em tela cheia',
          text:
            'Expande a prévia para a tela central inteira: ideal para digitar um endereço no Maps com o teclado completo. Tudo é replicado no cluster em tempo real.',
        },
        {
          title: '⏹ Parar projeção',
          text:
            'Encerra a projeção com limpeza e restaura o painel original da BYD (velocidade, indicadores, ADAS) no tamanho definido em Configurações.',
        },
        {
          title: '🗂️ Seletor de layout do cluster (recolhido por padrão)',
          text:
            'No modo Layouts, um cabeçalho compacto “LAYOUT DO CLUSTER” fica abaixo dos botões. Toque para expandir: “Abrir apps do layout”, mais um cartão por layout salvo (Modo livre / seus presets / ＋ Gerenciar). Fica recolhido por padrão para a prévia ao vivo manter a altura máxima.',
        },
        {
          title: '📺 Botão flutuante',
          text:
            'Um botão 📺 fica por cima dos outros apps: toque = abrir o espelho, toque longo = troca rápida entre os apps projetados recentemente.',
        },
        {
          title: '🧭 Barra de navegação lateral',
          text:
            'Acesso rápido a Apps, Configurações, Sistema, Log, o relato de problemas e — no DiLink 3 com o seu próprio chip — o assistente de Hotspot.',
        },
      ],
      howTo: {
        title: 'Como projetar um app no cluster',
        steps: [
          'Encontre o app desejado (ex.: Maps) — use a busca ou os filtros se precisar.',
          'Toque no ícone → a projeção começa e o cluster troca para o app em ~2 s.',
          'A prévia da direita mostra ao vivo o que está no cluster.',
          'Para digitar: “Espelho em tela cheia” → digite o endereço → tudo é replicado.',
          'Para parar: “Parar projeção” — o cluster volta ao visual nativo da BYD.',
        ],
      },
      tipsTitle: 'Dicas',
      tips: [
        '💡 Abertura automática: escolha um app (toque longo → Abrir automaticamente) para projetá-lo sozinho a cada abertura do DashCast — a projeção ativa por conta própria.',
        '💡 Layout favorito: o cartão selecionado no seletor “Layout do cluster” é o que o início automático vai ativar (veja a seção Layouts).',
        '💡 Margens: se o app estourar o cluster, Configurações → Margens, controles horizontal/vertical. Lembrado por app.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Configurações',
      lead:
        'Opções globais: tamanho do cluster, idioma, margens, comportamento na partida, modo Layouts e atualizações. A barra lateral continua disponível — troque de tela sem perder o lugar.',
      mockupLabel: 'Ver tela 3 (Configurações)',
      featuresTitle: 'Seções principais',
      features: [
        {
          title: '📺 Tipo de cluster',
          text:
            'Tamanho físico do seu painel: 8,8″, 12,3″ (recomendado no Seal EU — corrige o esticamento do ADAS) ou 10,25″. Usado por “Parar projeção” para restaurar o modo certo.',
        },
        {
          title: '↔️↕️ Margens (overscan)',
          text:
            'Controles horizontal/vertical (0–200 px) para compensar bordas cortadas. Lembrado por aplicativo: o Maps pode ter 80 px enquanto o Spotify fica em 0. “Aplicar” ajusta a projeção ao vivo.',
        },
        {
          title: '🚗 Iniciar com o veículo',
          text:
            'Se estiver ativo, o DashCast inicia com o carro e restaura o último app projetado (ou o layout favorito). Caso contrário, abra-o na gaveta da BYD.',
        },
        {
          title: '🗂️ Modo Layouts',
          text:
            'Ativa a projeção de vários apps com zonas personalizadas (requer o daemon proxy ADB, gerenciado automaticamente). Mostra o seletor “Layout do cluster” na tela principal e a aba Layouts.',
        },
        {
          title: '⭐ Layout favorito automático',
          text:
            'Ao abrir o DashCast: ativa a projeção no cluster, o layout favorito e depois abre os apps vinculados a cada zona. Seu setup multi-app completo, sem um toque.',
        },
        {
          title: '⚡ Pré-criar slots na partida',
          text:
            'Prepara as telas virtuais do layout favorito assim que o DashCast abre (sem abrir os apps) — ativar o layout fica quase instantâneo.',
        },
        {
          title: '📶 Usar o meu próprio chip (DiLink 3)',
          text:
            'Controla se o assistente de Hotspot aparece na barra de navegação. Deixe ligado se você compartilha a internet do carro pelo seu celular/chip. Veja a seção Hotspot.',
        },
        {
          title: '📦 Atualizações OTA',
          text:
            'O DashCast consulta o GitHub a cada abertura. As atualizações agora instalam em silêncio e reabrem o app sozinhas (veja a seção Atualizações). Marque “Incluir pré-lançamentos” para o canal beta.',
        },
        {
          title: '🌐 Idioma',
          text: '14 idiomas — a troca é instantânea.',
        },
      ],
      howTo: {
        title: 'Como ajustar as margens de um app',
        steps: [
          'Projete o app a ajustar (ex.: Waze).',
          'Configurações → Margens.',
          'Mova o controle horizontal até as bordas esquerda/direita ficarem certas.',
          'O mesmo no vertical, depois “Aplicar” — ajuste ao vivo, sem reabrir o app.',
          'O ajuste fica salvo só para este app.',
        ],
      },
      note:
        '⚠️ Se você mudar o tipo de cluster, pare e reinicie a projeção para a restauração usar o modo certo.',
    },

    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Layouts — vários apps no cluster',
      lead:
        'O modo Layouts divide o cluster em zonas personalizadas, cada uma com o seu aplicativo: Waze à esquerda, Spotify à direita, por exemplo. Você desenha as zonas com o dedo, vincula um app a cada zona e o layout ativa num toque — ou sozinho na partida.',
      mockupLabel: 'Ver tela 7 (Layouts)',
      featuresTitle: 'Recursos',
      features: [
        {
          title: '✏️ Desenhar uma zona',
          text:
            'No canvas (uma réplica do cluster), arraste o dedo para desenhar um retângulo. Abre um diálogo: nome, posição/dimensões em pixels e o app a vincular.',
        },
        {
          title: '🔗 Vincular um aplicativo',
          text:
            'Cada zona pode ser vinculada a um app: quando o layout ativa, o app abre sozinho na sua zona. Uma zona sem app fica livre — coloque qualquer coisa depois.',
        },
        {
          title: '✋ Mover e redimensionar',
          text:
            'Arraste uma zona para movê-la; as alças brancas nos cantos redimensionam. As bordas encaixam automaticamente nas bordas do cluster e nas zonas vizinhas.',
        },
        {
          title: '✏️ Editar uma zona existente',
          text:
            'Toque numa zona (no canvas ou no chip abaixo): renomeie, ajuste a geometria, troque o app vinculado ou exclua. Toque longo numa zona = exclusão rápida.',
        },
        {
          title: '💾 Layouts salvos',
          text:
            'Salve quantos layouts quiser (“Nav+Mídia”, “Tela tripla”…). O painel lateral lista com Ativar / Desativar / Editar / Excluir.',
        },
        {
          title: '⭐ Favorito e início automático',
          text:
            'O botão “Favorito” (ou um toque no cartão do seletor da tela principal) define o layout que o “Layout favorito automático” vai ativar ao abrir o DashCast — inclusive a projeção.',
        },
      ],
      howTo: {
        title: 'Crie o seu primeiro layout',
        steps: [
          'Ative o “Modo Layouts” em Configurações.',
          'Abra a aba Layouts (barra lateral).',
          'Arraste o dedo no canvas para desenhar a primeira zona (ex.: metade esquerda).',
          'No diálogo: dê um nome, toque em “Vincular um aplicativo” → escolha o Waze → Adicionar.',
          'Desenhe a segunda zona (metade direita), vincule o Spotify.',
          '“Salvar” → nomeie o layout (ex.: Nav+Mídia).',
          '“Favorito” para selecioná-lo e depois ative: os dois apps abrem, cada um na sua zona.',
        ],
      },
      tipsTitle: 'Dicas',
      tips: [
        '💡 Combinado com “Layout favorito automático” (Configurações), o setup multi-app se monta sozinho a cada abertura do DashCast.',
        '💡 A miniprévia de cada cartão do seletor mostra as zonas reais do layout — reconhecível num relance.',
        '💡 Um app recusa aparecer na zona? Alguns apps impõem a proporção; tente uma zona mais próxima de 16:9.',
      ],
      note:
        'ℹ️ O modo Layouts depende do daemon proxy ADB (iniciado automaticamente). Na primeira partida a frio: espere 6–8 s antes dos apps aparecerem — é a sequência de ativação do cluster.',
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: '5. Setas de navegação no HUD (DiLink 3)',
      lead:
        'Nos carros DiLink 3 cujo Head-Up Display do para-brisa aceita setas de conversão, o DashCast pode desenhar a orientação passo a passo no HUD a partir do seu app de navegação — a seta da manobra e a distância até ela, direto no para-brisa.',
      mockupLabel: 'Ver tela 2 (Principal)',
      featuresTitle: 'Como funciona',
      features: [
        {
          title: '🧭 Orientação do Maps / Waze',
          text:
            'O DashCast lê a notificação de conversão que o app de navegação já publica (Google Maps, Waze) e envia a manobra + distância ao HUD pelo barramento CAN do carro. Não precisa de app extra.',
        },
        {
          title: '🚗 Depende do firmware',
          text:
            'Só firmwares mais novos do HUD DiLink 3 conseguem desenhar setas. Se o seu não consegue, as setas simplesmente não aparecem — o DashCast não adiciona uma capacidade que o hardware do HUD não tem.',
        },
        {
          title: '➡️ Glifos na direção certa',
          text:
            'Manobras em frente / esquerda / direita mapeiam para o glifo correspondente do HUD, com a contagem regressiva da distância até a conversão.',
        },
      ],
      howTo: {
        title: 'Como ter setas no HUD',
        steps: [
          'Confirme que o acesso a notificações foi concedido ao DashCast (ele pede no primeiro uso).',
          'Ligue o HUD do para-brisa e coloque-o num modo de navegação no menu HUD da BYD.',
          'Inicie uma rota no Google Maps ou no Waze.',
          'A seta da manobra e a distância aparecem no HUD conforme você se aproxima de cada conversão.',
        ],
      },
      note:
        'ℹ️ As setas do HUD são um recurso do DiLink 3 e dependem do firmware do HUD. Se nada aparecer, o seu HUD pode ser anterior ao suporte a setas — limite de hardware, não um bug do DashCast.',
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: '6. Assistente de hotspot Wi-Fi (DiLink 3)',
      lead:
        'No DiLink 3, se você liga o carro à internet pelo seu próprio chip/celular, o assistente de Hotspot mantém esse compartilhamento ativo para a navegação e o streaming continuarem. Ele aparece na barra de navegação só quando faz sentido para você.',
      mockupLabel: 'Ver tela 3 (Configurações)',
      featuresTitle: 'Recursos',
      features: [
        {
          title: '📶 Manter ligado',
          text:
            'Rearma o tether Wi-Fi quando o carro acorda (ex.: depois de ligar o ACC), para você não ter que reativar manualmente a cada viagem.',
        },
        {
          title: '👁️ Status ao vivo',
          text:
            'Mostra se o hotspot está ligado e quantos clientes estão conectados, para você confirmar que o carro está mesmo online.',
        },
        {
          title: '⚙️ Só quando é útil',
          text:
            'A entrada Hotspot aparece só no DiLink 3 e só enquanto “Usar o meu próprio chip” está ativo em Configurações. Em outros setups ela fica oculta.',
        },
      ],
      howTo: {
        title: 'Como usar',
        steps: [
          'Configurações → confira se “Usar o meu próprio chip” está ativo.',
          'Abra “Hotspot” na barra de navegação.',
          'Inicie / confirme o tether — o status mostra que está ligado.',
          'Ele se rearma sozinho na próxima vez que o carro acordar.',
        ],
      },
      note:
        'ℹ️ Este assistente é para carros DiLink 3 conectados pelos seus próprios dados. Se o carro tem plano de dados próprio, você não precisa dele.',
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Relatar um problema',
      lead:
        'Um relato de problemas no carro, sem teclado. Em três toques você escolhe o que deu errado; o DashCast captura um instantâneo diagnóstico limitado (logs + estado do sistema) e envia direto ao canal de suporte — sem digitar, sem cabo.',
      mockupLabel: 'Ver tela 6 (Relatar)',
      featuresTitle: 'Como funciona',
      features: [
        {
          title: '1️⃣ Categoria',
          text:
            'Escolha a área afetada: espelho, um app, som, conexão, travamento, HUD… Seis blocos grandes, só toque.',
        },
        {
          title: '2️⃣ App',
          text:
            'O DashCast detecta o app que está no cluster e oferece, além de “Nenhum app específico” e “Outro”.',
        },
        {
          title: '3️⃣ Problema',
          text:
            'Escolha o sintoma mais próximo numa lista curta. Uma caixa de texto opcional deixa você acrescentar detalhe se quiser — nunca é obrigatório.',
        },
        {
          title: '📎 Diagnóstico automático',
          text:
            'O relatório junta os logs recentes e o estado do sistema/cluster no momento do problema — exatamente o que o suporte precisa, capturado para você.',
        },
        {
          title: '🚀 Envio num toque',
          text:
            'Se um canal de suporte estiver configurado, o relatório sobe direto; senão o DashCast abre a folha de compartilhamento do Android para você enviar por Telegram, e-mail ou GitHub.',
        },
        {
          title: '📺 De qualquer lugar',
          text:
            'O botão flutuante 📺 e a barra de navegação abrem o relato, então você pode enviar mesmo com outro app projetado.',
        },
      ],
      howTo: {
        title: 'Como enviar um relatório',
        steps: [
          'Abra o relato de problemas (barra de navegação ou o botão flutuante).',
          'Toque na categoria que combina com o problema.',
          'Confirme o app (ou escolha “Nenhum app específico”).',
          'Escolha o problema mais próximo; acrescente uma nota se for útil.',
          'Toque em Enviar — o diagnóstico vai ao suporte automaticamente.',
        ],
      },
      note:
        '🔒 Antes do envio, o DashCast remove o número de série do veículo, nomes de redes Wi-Fi, endereços de hardware e posições. O que permanece é o log do DashCast e o log do sistema Android, copiados como estão — eles guardam o que outros apps escreveram. Você é perguntado uma vez antes de qualquer envio, e nada sai do carro se você recusar.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. Relatório do sistema',
      lead:
        'Painel somente leitura: versões, telas detectadas e estado ao vivo dos serviços do DashCast. A primeira tela a conferir quando algo parece errado.',
      mockupLabel: 'Ver tela 5 (Sistema)',
      featuresTitle: 'Informações exibidas',
      features: [
        {
          title: '🖥️ Telas',
          text:
            'Tela principal (resolução, densidade) e a tela virtual do cluster com o estado em tempo real.',
        },
        {
          title: '⚙️ Serviços',
          text:
            'ClusterService (projeção), MirrorDaemon (espelho), daemon proxy ADB (operações privilegiadas), AdbLocalClient (túnel ADB) — cada um com um ponto verde/vermelho e um botão de reinício quando parado.',
        },
        {
          title: '📱 Versões',
          text:
            'Versão instalada do DashCast, firmware BYD, versão Android/API, identificadores de build do DiLink.',
        },
        {
          title: '🔁 Replay da projeção',
          text:
            'Botão para repetir a sequência completa de ativação do cluster (útil se o painel travou num estado intermediário).',
        },
      ],
      tipsTitle: 'Dicas',
      tips: [
        '💡 “Daemon proxy ADB” precisa estar verde (LIG) para o modo Layouts — senão toque na linha para reiniciá-lo.',
        '💡 Se algo parecer errado, confira esta tela primeiro e depois envie um relatório pelo relato de problemas.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Log',
      lead:
        'O log interno do DashCast: cada ação importante (projeções, restaurações, erros de ADB, atualizações) é registrada o tempo todo. Útil para entender um comportamento inesperado; também é o dado que o relato de problemas anexa para você.',
      mockupLabel: 'Ver tela 6 (Log)',
      featuresTitle: 'Recursos',
      features: [
        {
          title: '🔍 Filtros',
          text:
            'Filtre por nível (DEBUG / INFO / WARN / ERROR) ou por palavra-chave (ex.: “ADB”, “Maps”, “erro”).',
        },
        {
          title: '🎨 Código de cores',
          text:
            '🟢 INFO — operação normal. 🟠 WARN — atenção. 🔴 ERROR — falha. ⚪ DEBUG — detalhe técnico.',
        },
        {
          title: '📤 Compartilhar',
          text:
            'Exporta o log como .txt e abre a folha de compartilhamento do Android. Inclui a versão do DashCast e o modelo BYD.',
        },
        {
          title: '⏰ Horários',
          text:
            'Cada linha começa com a hora local (HH:mm:ss.mmm); operações longas são cronometradas.',
        },
      ],
      howTo: {
        title: 'Prefira o relato de problemas',
        steps: [
          'Na maioria dos casos, use o relato de problemas (seção 7) — ele captura o log e o estado do sistema automaticamente.',
          'A tela de Log serve quando você quer ler o rastro você mesmo ou compartilhar só o log bruto.',
        ],
      },
      note:
        '🔒 O log registra o que o DashCast faz, inclusive nomes de pacote e a saída dos comandos que ele executa. Compartilhar daqui envia como está — o filtro que remove o número de série do veículo, nomes de rede e posições roda nos relatórios de problema, não aqui.',
    },
  ],

  faq: {
    title: '10. FAQ — Perguntas frequentes',
    items: [
      {
        question: '❓ O cluster fica preto quando eu toco num app',
        answer:
          'Três causas possíveis: (1) ADB sem fio desligado — confira Ajustes BYD → Opções do desenvolvedor. (2) Um serviço parado — tela Sistema, reinicie a linha vermelha. (3) O app acabou de fechar — toque no ícone de novo. Ainda travado? Envie um relatório pelo relato de problemas.',
      },
      {
        question: '❓ A imagem estoura / é cortada no cluster',
        answer:
          'Configurações → Margens: ajuste os controles horizontal/vertical até as bordas ficarem certas. Lembrado por app — você só faz uma vez.',
      },
      {
        question: '❓ Como volto ao painel original da BYD?',
        answer:
          'Toque em “Parar projeção” na tela principal: o DashCast restaura o cluster nativo no tamanho definido em Configurações. Se a tela parecer travada, tela Sistema → “Replay da projeção” e depois pare de novo.',
      },
      {
        question: '❓ Meu layout favorito não inicia na abertura',
        answer:
          'Confira as três condições em Configurações: “Modo Layouts” ativo, “Layout favorito automático” ativo e um layout marcado ⭐ favorito (seletor da tela principal ou o botão Favorito na aba Layouts). Na partida a frio, espere 6–8 s.',
      },
      {
        question: '❓ Sem setas de navegação no meu HUD',
        answer:
          'As setas do HUD são um recurso do DiLink 3 e precisam de um firmware de HUD que saiba desenhá-las. Confirme o acesso a notificações, o HUD ligado num modo de navegação e uma rota rodando no Maps/Waze. Se nada aparecer, o firmware do HUD provavelmente é anterior ao suporte a setas — limite de hardware, não um bug.',
      },
      {
        question: '❓ Eu preciso do assistente de Hotspot?',
        answer:
          'Só no DiLink 3 se você coloca o carro online pelo seu próprio chip/celular. Ele mantém esse tether vivo entre acordares. Se o carro tem plano de dados próprio, ignore — fica oculto a menos que “Usar o meu próprio chip” esteja ligado.',
      },
      {
        question: '❓ Como as atualizações instalam agora?',
        answer:
          'O DashCast consulta o GitHub a cada abertura. Quando uma atualização é baixada, ela instala em silêncio e reabre o app sozinha — sem o aviso “Instalar?”. Num carro em que isso não for possível, cai no instalador normal do sistema. A primeira atualização depois que você sobe para uma versão com este recurso ainda pode pedir uma vez.',
      },
      {
        question: '❓ O DashCast drena a bateria de 12 V?',
        answer:
          'Não — o DashCast para com o carro. Nenhum serviço de fundo fica ativo com o motor desligado.',
      },
      {
        question: '❓ Quais apps funcionam no cluster?',
        items: [
          '✅ Navegação: Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
          '✅ Mídia: Spotify, YouTube, YouTube Music (prefira paisagem).',
          '✅ Sistema: câmera, clima, calendário.',
          '⚠️ Apps com DRM (Netflix, Disney+, Prime Video): podem recusar aparecer numa tela virtual — limitação do Android, não do DashCast.',
        ],
      },
      {
        question: '❓ Atualizações: estável ou beta?',
        answer:
          'O canal estável (padrão) é testado num veículo antes do lançamento. O canal beta (Configurações → Atualizações → “Incluir pré-lançamentos”) recebe os recursos assim que são compilados — útil para testar cedo, com risco de regressões temporárias.',
      },
      {
        question: '❓ Quero contribuir ou relatar um problema',
        answer:
          'Use o relato de problemas no app para o caminho mais rápido (ele anexa o diagnóstico para você). Para código e pedidos de recurso: GitHub https://github.com/Kiroha/byd-dashcast — Issues para bugs, Discussions para perguntas.',
      },
    ],
  },

  footer:
    'O DashCast é um projeto de código aberto distribuído sob a licença MIT. Sem afiliação com a BYD Auto Co., Ltd.',
};
