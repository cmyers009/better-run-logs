# Better Run Logs

A Slay the Spire mod that records **everything** about a run, well beyond the game's own `.run` files:

- every card played (with its real target, energy spent, and hand at the time)
- every game action executed, every draw, exhaust, end of turn and monster turn
- every monster move roll and intent
- every potion **use** (with its target and what triggered it) kept separate from potion **discards**
- every event option, card reward pick/skip, hand/grid/Discovery selection, room entered
- deck, gold, HP and max-HP changes, relics and potions gained
- the state of **every RNG stream** (the 13 dungeon streams, Neow's, and libgdx `MathUtils`) whenever it changes
- a full game-state snapshot at the start of the run, every act, every room and every player turn

It does not change gameplay and works with any character, including modded ones.

Logs can be replayed with [better-run-logs-replayer](https://github.com/cmyers009/better-run-logs-replayer).

## Install

You need the Steam version of Slay the Spire.

1. On the Steam Workshop, subscribe to **ModTheSpire** and **BaseMod**.
2. Download `better-run-logs.jar` from the releases page.
3. Put it in the `mods` folder inside your game folder (create `mods` if it does not exist). See
   [Finding your game folder](#finding-your-game-folder).
4. Launch Slay the Spire from Steam and pick **Play With Mods**. In the ModTheSpire launcher, tick
   **BaseMod** and **Better Run Logs**, then press **Play**.

To check it's working, look for `[BetterRunLogs] initialized` in the ModTheSpire console window,
or start a run and check that `better-run-logs/inprogress/` appears in the game folder.

To uninstall, delete the jar from `mods`.

## Where the logs are

Everything is saved inside your game folder:

| What | Where |
|---|---|
| The game's normal run history | `runs/<CHARACTER>/<timestamp>.run` |
| **Better Run Logs, finished runs** | `better-run-logs/<CHARACTER>/<timestamp>.jsonl.gz` |
| Run in progress (written live) | `better-run-logs/inprogress/<CHARACTER>_<seed>.jsonl` |
| Runs abandoned without a `.run` file | `better-run-logs/unfinished/` |

A finished log has the same `<timestamp>` as its `.run` file. The `.run` file also records the
log's `better_run_log_id`, so the two can always be matched.

The logs are **not** stored in `runs/`, because the game deletes any file there that it can't
read as a run.

The in-progress log is written to disk every frame, so a crash or Alt-F4 loses nothing. It
continues across **Save & Quit**.

## Finding your game folder

The easiest way on any system: in your Steam Library, right-click **Slay the Spire** → **Manage** →
**Browse local files**.

The usual locations:

| System | Game folder |
|---|---|
| Windows | `C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire\` |
| Windows (other Steam library drive) | `<drive>:\SteamLibrary\steamapps\common\SlayTheSpire\` |
| Linux (native Steam) | `~/.local/share/Steam/steamapps/common/SlayTheSpire/` |
| Linux (Flatpak Steam) | `~/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/common/SlayTheSpire/` |
| Linux (Snap Steam) | `~/snap/steam/common/.local/share/Steam/steamapps/common/SlayTheSpire/` |
| Steam Deck | `/home/deck/.local/share/Steam/steamapps/common/SlayTheSpire/` |
| macOS | `~/Library/Application Support/Steam/steamapps/common/SlayTheSpire/SlayTheSpire.app/Contents/Resources/` |

On macOS, get to `SlayTheSpire.app/Contents/Resources/` by right-clicking the app → **Show
Package Contents**. `~/Library` is hidden in Finder: use **Go → Go to Folder…** and paste the path.

So on Windows, for example, your logs are in
`C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire\better-run-logs\IRONCLAD\`.

## Log format

Each log is gzipped [JSON Lines](https://jsonlines.org/): one JSON object per line, in the order
things happened. Every line has:

- `t`: the event type, e.g. `card_play`, `potion_use`, `action`, `turn_start`
- `seq`: a line counter with no gaps
- `floor`: the current floor
- `rng_d`: the RNG streams that changed since the previous line, as `[counter, state0, state1]`

The first line (`run_start`) has the seed, character, ascension, game version and full starting
state. A `resume` line marks each Save & Quit reload.

Open a log on any system with a tool that reads gzip, or on Linux/macOS with
`zcat <file>.jsonl.gz | less`.

## Building from source

Needs a JDK (11 or newer, to compile for Java 8) and the game, ModTheSpire and BaseMod installed
through Steam:

```sh
STEAM=/path/to/steamapps ./build.sh   # writes better-run-logs.jar
```

`STEAM` defaults to `~/.local/share/Steam/steamapps`.

## Known limits

- Randomness that another mod keeps in its own RNG is not captured.
- Shop purchases, campfire choices and reward pickups show up through their effects (gold, deck,
  HP, relic and potion changes), not yet as their own lines.
