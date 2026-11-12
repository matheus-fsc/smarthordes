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
- **Avisos à tarde**: mensagens que dão a pista do que vem ("você sente o cheiro da podridão no horizonte", "você escuta o tinir das picaretas...").
- **Rampa por tempo de jogo**: chance e tamanho crescem com as horas da run, com noites de descanso e um teto.
- **Horda do Nether**: zumbis-porco e piglins seguem quem fica tempo demais no Nether.
- **Gravador de ondas** e **modo arena** de teste para ajustar a IA.

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
| `horde` | IA: sair da terra, cercar, rampas e formatos, cavar escadas, escolta, capacidade de blocos, gravação |
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
