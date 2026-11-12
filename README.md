# SmartHordes

**English** | [Português (pt-BR)](README.pt-BR.md)

![Paper](https://img.shields.io/badge/Paper-26.3-blue) ![Java](https://img.shields.io/badge/Java-25-orange) ![Version](https://img.shields.io/badge/version-1.0.0-green) ![License](https://img.shields.io/badge/license-MIT-lightgrey) ![Status](https://img.shields.io/badge/status-beta-orange)

> **Beta.** SmartHordes is in active development: expect changes and rough edges. It is fully open source (MIT) and community ideas are welcome. Suggest features or report bugs in the issues.

Zombie hordes that **dig, build and surround** players. Each zombie has a role, gathers the blocks it needs and climbs to reach you. Hordes are announced in the afternoon so players have time to prepare.

> Built for **Paper 26.3** (Java 25). In-game text is in Brazilian Portuguese (pt-BR).

Works on its own on any Paper server. With [HardcoreRoulette](https://github.com/matheus-fsc/hardcore-roulette) it follows the current run's world, scales with the run's age and clears itself when a new run starts.

## Features

- **Roles that follow vanilla tool rules**: the miner (wooden pickaxe) breaks and places stone, the digger (wooden shovel) handles dirt, sand and gravel, the lumberjack (wooden axe) handles wood; brutes and tanks only chase and fight.
- **Real resources**: zombies start with no blocks and a limited inventory. Pillars, bridges and ramps use only what they mined. They work in two phases: **gather** (compute how much material the climb needs and collect exposed resources nearby) and **attack**.
- **Stairs like a player**: the lumberjack crafts a crafting table, crafts the stairs the climb needs and nails them to the wall; any horde zombie can climb them.
- **Surround and escort**: the horde spreads across nearby players and warriors escort the builders.
- **Rise from the ground**: horde zombies emerge with a dirt animation instead of popping in.
- **Afternoon warnings**: flavor messages hint at what is coming ("you smell rot on the horizon", "you hear pickaxes clinking...").
- **Ramp by play time**: chance and size grow with the run's hours, with rest nights after a horde and a hard cap.
- **Nether horde**: zombified piglins and piglins follow players who stay too long in the Nether.
- **Wave recorder** and a test **arena mode** to tune the AI.

## Installation

1. A **Paper 26.3** server running **Java 25**.
2. Drop `SmartHordes.jar` into `plugins/` and restart.
3. Optional: [HardcoreRoulette](https://github.com/matheus-fsc/hardcore-roulette) 1.4.0 or newer.

Upgrading from HardcoreRoulette 1.3 or older (when the horde lived inside it): install SmartHordes next to HardcoreRoulette 1.4. On first start it copies the `invasion`, `nether-horde` and `horde` sections from HardcoreRoulette's config.

## Commands

| Command | Description |
|---------|-------------|
| `/horda` (or `/invasao`) | Horde status |
| `/horda zumbis [amount] [target] [construtores]` | Start a horde now (`construtores` = builders only) |
| `/horda nether [target]` | Start a Nether horde on a player in the Nether |
| `/horda parar` | Stop the current horde |
| `/horda report` | Create a report to attach to an issue |
| `/arena load\|run\|stop` | Test arena (only with `arena-mode: true`) |

Permission: `smarthordes.admin` (default op).

## Configuration (`plugins/SmartHordes/config.yml`)

| Section | What it controls |
|---------|------------------|
| `world` | Horde world without HardcoreRoulette (empty = main world) |
| `invasion` | Night horde: chance, size, ramp by play time, rest nights, first day |
| `nether-horde` | Nether horde on/off and how long players can stay before it comes |
| `horde` | AI toggles: emerge, surround, ramps and their shapes, digging stairs, escort, block capacity, recording |
| `arena-mode` | Test server: only the arena runs |
| `debug` | More detailed logs |

## Reporting a problem

Run `/horda report`, then open an issue and attach the file from `plugins/SmartHordes/reports/`. It includes versions, installed plugins, the config without secrets and the latest log lines. Daily logs are in `plugins/SmartHordes/logs/`.

## Building

```
mvn package
```

The jar ends up in `target/SmartHordes.jar`. The HardcoreRoulette integration compiles against its API as an optional dependency: run `mvn install` in the HardcoreRoulette repository first.

## License

MIT
