# SmartHordes

[English](README.md) | **Português (pt-BR)**

> **Beta.** O SmartHordes está em desenvolvimento ativo: pode mudar e ter arestas. É open source (MIT) e ideias da comunidade são bem-vindas. Sugira recursos ou reporte problemas nos issues.

Hordas de zumbis que **cavam, constroem e cercam** os jogadores. Cada zumbi tem um papel, junta os blocos de que precisa e sobe para te alcançar. A horda é avisada à tarde, para dar tempo de se preparar.

> Feito para **Paper 26.3** (Java 25). Mensagens em português (pt-BR).

Funciona sozinho em qualquer servidor Paper. Com o [HardcoreRoulette](https://github.com/matheus-fsc/hardcore-roulette), segue o mundo da run atual, escala com o tempo da run e se limpa quando uma run nova começa.

## Recursos

- **Papéis com a regra de ferramenta do vanilla**: minerador (picareta de madeira) quebra e coloca pedra, cavador (pá de madeira) cuida de terra, areia e cascalho, lenhador (machado de madeira) cuida de madeira; brutamontes e tanque só perseguem e lutam.
- **Recursos de verdade**: começam sem blocos e com inventário limitado. Pilares, pontes e rampas usam só o que mineraram. Trabalham em duas fases: **coleta** (calcula o material que a subida precisa e busca recursos expostos por perto) e **ataque**.
- **Escadas como um jogador**: o lenhador crafta uma bancada, crafta as escadas que a subida precisa e prega na parede; qualquer zumbi da horda sobe por elas.
- **Cerco e escolta**: a horda se divide entre os jogadores próximos e os guerreiros escoltam os construtores.
- **Saem da terra**: os zumbis da horda emergem do chão com animação.
- **Água e cavernas**: nadam em vez de afundar, alguns viram afogados da horda (rápidos na água), e quem está no subsolo enfrenta uma horda só de mineradores que cava os blocos naturais da caverna.
- **Avisos à tarde**: mensagens que dão a pista do que vem ("você sente o cheiro da podridão no horizonte", "você escuta o tinir das picaretas...").
- **Rampa por tempo de jogo**: chance e tamanho crescem com as horas da run, com noites de descanso e um teto.
- **Horda do Nether**: zumbis-porco e piglins seguem quem fica tempo demais no Nether.
- **Gravador de ondas** e **modo arena** de teste para ajustar a IA.

## Como funciona

A cada meio segundo, cada zumbi da horda passa por um pequeno ciclo de decisao:

1. **Escolhe um alvo.** A horda se divide entre os jogadores proximos, pesando a distancia e quantos zumbis ja estao em cada um.
2. **Da para ir andando?** Se sim, persegue e ataca.
3. **Alvo fora de alcance (acima, atras de parede, do outro lado de um vao)?** Os construtores entram em duas fases:
   - **Coleta**: calculam quantos blocos a subida precisa, vao ate recursos expostos por perto e quebram (o tempo de quebra segue a dureza do bloco, como um jogador). Os blocos vao para um inventario limitado.
   - **Construcao**: pilares, pontes e uma rampa compartilhada (reta, zigue-zague ou espiral). O lenhador crafta bancada e escadas e prega na parede.
4. **Guerreiros** (brutamontes e tanques) escoltam os construtores e esperam em formacao ate a rampa ficar pronta; ai avancam.

Situacoes especiais:

| Situacao | O que a horda faz |
|----------|-------------------|
| Ao nascer | Os zumbis saem de dentro da terra com animacao, em tempos diferentes |
| Agua | Nadam ate a margem mais util em vez de afundar. Alguns viram **afogados da horda** (mesmo papel, ferramenta, blocos e alvo), que nadam rapido |
| Jogador se defende com agua (nadando, barco, ilha, fosso) | Parte da horda mergulha de proposito para virar afogado; os construtores seguem tentando pontes |
| Jogador no subsolo | A horda nasce **so de mineradores**, que cavam blocos naturais da caverna (pedra, deepslate, tuff, granito, diorito, andesito, terra, cascalho, argila...). Base feita de blocos de construcao (pedregulho, tijolo, madeira, vidro, pedra lapidada) fica segura, e minerios nunca sao quebrados |
| Jogador muito tempo no Nether | Uma horda do Nether (zumbis-porco e piglins) vai atras dele |

Tudo isso liga e desliga em `horde` no config.yml.

## Como e treinado

Os numeros por tras da IA (tempos de coleta, raios de busca, limites da rampa, distancias de cerco e outros, cerca de 20) ficam em `horde.p` no config.yml. Eles nao foram escolhidos a mao: saem de um treino feito em cima de **hordas reais gravadas**.

1. **Gravacao.** Com `horde.record: true`, cada horda real fica salva em `plugins/SmartHordes/waves/<data>/`: o terreno em volta da luta, posicao, vida e acao de cada zumbi e jogador a cada meio segundo, blocos mudados e danos.
2. **Analise.** Um script le todas as gravacoes e mede, por jogador, dano tomado, tempo ate o primeiro golpe, o quanto a horda chegou perto e que fatia da horda foi em cada um. Foi assim que vimos, por exemplo, que a horda se concentrava demais num jogador em partidas com varios e que quase nao chegava em quem estava no subsolo.
3. **Simulador.** Uma copia em Python do cerebro da horda roda numa copia voxel de cada terreno gravado, contra estrategias de jogador (parado, fugitivo, pilar, bunker, lutador, esperto). E rapido o bastante para milhares de partidas e e calibrado com as gravacoes reais.
4. **Otimizador.** O CMA-ES (um otimizador evolutivo) propoe conjuntos de parametros. Cada candidato e avaliado em **servidores Paper de verdade, sem tela**, rodando o SmartHordes em `arena-mode`: o terreno gravado e colado numa arena e um boneco repete o caminho real do jogador, enquanto a horda candidata joga de verdade. Varias arenas rodam em paralelo, em mais de uma maquina.
5. **Nota.** Menor e melhor para a horda: matar cedo vale mais; sem morte, contam dano, golpes, tempo ate o primeiro golpe e a menor distancia. A melhor media vira o novo padrao.

As ferramentas de treino (simulador, analise e controle das arenas) ainda nao fazem parte deste repositorio. A dificuldade nao e o que o otimizador ajusta: ele deixa a horda *competente*, e o quanto ela pesa fica nos numeros de `invasion` (chance, tamanho, rampa por tempo de jogo, noites de descanso).

## Instalação

1. Servidor **Paper 26.3** com **Java 25**.
2. Coloque `SmartHordes.jar` em `plugins/` e reinicie.
3. Opcional: [HardcoreRoulette](https://github.com/matheus-fsc/hardcore-roulette) 1.4.0 ou mais novo.

Vindo do HardcoreRoulette 1.3 ou anterior (quando a horda morava nele): instale o SmartHordes junto do HardcoreRoulette 1.4. Na primeira vez, ele copia as seções `invasion`, `nether-horde` e `horde` da config do HardcoreRoulette.

## Comandos

| Comando | Descrição |
|---------|-----------|
| `/horda` (ou `/invasao`) | Estado da horda |
| `/horda zumbis [qtd] [alvo] [construtores]` | Começa uma horda agora (`construtores` = só construtores) |
| `/horda nether [alvo]` | Horda do Nether num jogador que está no Nether |
| `/horda parar` | Encerra a horda atual |
| `/horda report` | Gera um relatório para anexar num issue |
| `/arena load\|run\|stop` | Arena de teste (só com `arena-mode: true`) |

Permissão: `smarthordes.admin` (padrão op).

## Configuração (`plugins/SmartHordes/config.yml`)

| Seção | O que controla |
|-------|----------------|
| `world` | Mundo da horda sem o HardcoreRoulette (vazio = mundo principal) |
| `invasion` | Horda noturna: chance, tamanho, rampa por tempo de jogo, noites de descanso, primeiro dia |
| `nether-horde` | Liga a horda do Nether e quanto tempo dá para ficar lá antes dela vir |
| `horde` | IA: sair da terra, nadar, afogados, mineradores de caverna, cercar, rampas e formatos, cavar escadas, escolta, capacidade de blocos, gravação |
| `horde.p` | Números da IA ajustados pelo treino (veja Como é treinado) |
| `arena-mode` | Servidor de teste: só a arena roda |
| `debug` | Log mais detalhado |

## Reportar um problema

Rode `/horda report`, abra um issue e anexe o arquivo de `plugins/SmartHordes/reports/`. Ele traz versões, plugins instalados, a config sem segredos e as últimas linhas do log. Os logs diários ficam em `plugins/SmartHordes/logs/`.

## Compilar

```
mvn package
```

O jar sai em `target/SmartHordes.jar`. A integração com o HardcoreRoulette usa a API dele como dependência opcional: rode `mvn install` no repositório do HardcoreRoulette antes.

## Licença

MIT
