# SmartHordes

**English** | [Português (pt-BR)](README.pt-BR.md)

![Paper](https://img.shields.io/badge/Paper-26.3-blue) ![Java](https://img.shields.io/badge/Java-25-orange) ![Version](https://img.shields.io/badge/version-1.2.0-green) ![License](https://img.shields.io/badge/license-MIT-lightgrey) ![Status](https://img.shields.io/badge/status-beta-orange)

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
- **Water and caves**: zombies swim instead of sinking, some turn into fast drowned horde members, and underground players face a miners-only horde that digs through natural cave blocks.
- **Afternoon warnings**: flavor messages hint at what is coming ("you smell rot on the horizon", "you hear pickaxes clinking...").
- **Ramp by play time**: chance and size grow with the run's hours, with rest nights after a horde and a hard cap.
- **Nether horde**: zombified piglins and piglins follow players who stay too long in the Nether.
- **Wave recorder** and a test **arena mode** to tune the AI.

## How it works

Every half second each horde zombie runs a small decision loop:

1. **Pick a target.** The horde spreads across nearby players, weighing distance against how many zombies are already on each player.
2. **Can it just walk there?** If yes, it chases and attacks.
3. **Target out of reach (above, behind a wall, across a gap)?** Builders switch to two phases:
   - **Gather**: compute how many blocks the climb needs, walk to exposed resources nearby and break them (break time follows block hardness, like a player). Blocks go into a limited inventory.
   - **Build**: pillars, bridges and a shared ramp (straight, zigzag or spiral). The lumberjack crafts a crafting table and stairs and nails them to the wall.
4. **Warriors** (brutes and tanks) escort the builders and wait in formation until the ramp is ready, then rush in.

Special situations:

| Situation | What the horde does |
|-----------|---------------------|
| Spawning | Zombies rise out of the ground with a dirt animation, staggered |
| Water | Zombies swim to the most useful shore instead of sinking. Some become **drowned horde members** (same role, tools, blocks and target) that swim fast |
| Player defends with water (swimming, boat, island, moat) | Part of the horde dives in on purpose to turn into drowned; builders keep trying bridges |
| Player underground | The horde spawns as **miners only**, digging through natural cave blocks (stone, deepslate, tuff, granite, diorite, andesite, dirt, gravel, clay...). A base built from building blocks (cobblestone, bricks, planks, glass, cut stone) stays safe, and ores are never broken |
| Player in the Nether too long | A Nether horde (zombified piglins and piglins) comes after them |

All of it can be toggled under `horde` in config.yml.

## How it is trained

The numbers behind the AI (gather timeouts, scan radii, ramp thresholds, surround distances and more, about 20 in total) live under `horde.p` in config.yml. They are not hand-picked: they come from an offline training loop built around **real recorded hordes**.

1. **Recording.** With `horde.record: true`, every real horde is saved to `plugins/SmartHordes/waves/<date>/`: the terrain around the fight, the position, health and current action of every zombie and player every half second, block changes and damage events.
2. **Analysis.** A script reads all recordings and measures, per player, damage taken, time to first hit, how close the horde got and what share of the horde went after each player. This is how we found, for example, that the horde focused one player too much in multiplayer fights and that it rarely reached players underground.
3. **Simulator.** A Python port of the horde brain runs on a voxel copy of each recorded terrain, against scripted player strategies (idle, runner, pillar, bunker, fighter, smart). It is fast enough for thousands of episodes and is calibrated against the real recordings.
4. **Optimizer.** CMA-ES (an evolutionary optimizer) proposes parameter sets. Each candidate is evaluated in **real headless Paper servers** running SmartHordes in `arena-mode`: the recorded terrain is pasted into an arena and a dummy player replays the real player's path, while the candidate horde plays for real. Several arenas run in parallel, on more than one machine.
5. **Score.** Lower is better for the horde: dying early counts most; without a death, damage, hits, time to first hit and closest distance count. The best average becomes the new defaults.

The training tools (simulator, analysis and arena driver) are not part of this repository yet. Difficulty is not what the optimizer tunes: it makes the horde *competent*, while how hard it feels is set by the numbers under `invasion` (chance, size, ramp by play time, rest nights).

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
| `horde` | AI toggles: emerge, swim, drowned, cave miners, surround, ramps and their shapes, digging stairs, escort, block capacity, recording |
| `horde.p` | Tuned AI numbers (see How it is trained) |
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
