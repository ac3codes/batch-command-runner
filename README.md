Hello everyone! I'm Ac3. I'm writing this because I have to confess that I vibe-coded this project. I didn't know much about modding, but needed a mod that could run a large series of commands, so I vibe-coded it. I personally didn't like that I was dependent on AI for this but I don't have enough time to pick up java, so I apologize for that. All the designs and ui decisions are made by me. Anyway, thanks for using my mod.

# Batch Command Runner (Fabric 26.2)

Client-side Fabric mod for Minecraft Java 26.2.

## Features

- Press **/** (configurable in Controls) to open the UI.
- Large multiline, scrollable, wrapping command box.
- Paste one command per line.
- Leading `/` is optional.
- Blank lines are ignored.
- Lines beginning with `#` are treated as comments and ignored.
- Configurable delay in client ticks between commands.
- **Heavy Command Protection** (on by default): `/fill`, `/clone`, `/place`, and `/summon`
  each automatically wait at least their own configurable minimum delay - including when
  hidden behind `/execute ... run`, e.g. `/execute positioned ~ ~ ~ run fill ...` - so heavy
  commands don't overwhelm lighting/chunk/heightmap updates, entity load, and mods like
  Distant Horizons. Ordinary commands are unaffected. Commands still execute one at a time,
  strictly in order - protection only changes the wait between them, never the order.
- Live, allocation-light parsed command count.
- Status: Idle / Running / Paused / Completed / Stopped / Error.
- While running: current command number, a truncated preview of the command just sent, its
  detected type and (for fill/clone) estimated block count, the protection delay applied if
  any, and ticks until the next command.
- Pause/Resume, preserving the exact remaining delay of the in-flight wait.
- Stop button.
- Vanilla-style autocomplete: a small popup near the cursor, scoped to just the command line
  you're editing, backed by Minecraft's own Brigadier command tree. Arrow keys to navigate,
  Tab or click to accept, scroll wheel or Up/Down to move through more than fit on screen,
  Escape to dismiss.
- Delay and Heavy Command Protection settings (on/off plus each per-type minimum) are saved to
  a small config file and restored next launch; batch text/delay values are also retained when
  the screen is reopened during the same game session.
- UI does not pause single-player, so the command runner can continue while open.

## Delay semantics

- `0`: send the next command on the next available client tick.
- `1`: leave one full client tick between command sends.
- `20`: leave twenty full client ticks (about one second at 20 TPS) between commands.
- With Heavy Command Protection on, a protected command uses `max(delay, minimum for its
  type)` instead of the plain delay above (see Heavy Command Protection).

## Heavy Command Protection

Large batches often contain many expensive commands - `/fill`/`/clone` touching thousands to
millions of blocks, `/place` generating a structure or feature, or `/summon` spawning entities
by the thousand. Sending those back-to-back at a very short delay can overwhelm lighting,
chunk, and heightmap updates, or entity load - especially alongside mods like Distant Horizons.
This is a rate limiter, not a sandbox: it doesn't make commands themselves smaller or stop you
from intentionally issuing something dangerous, and it never reorders or skips anything - it
only rate-limits known-expensive commands and always preserves command order.

When Heavy Command Protection is on (default), each command is classified once, when you press
Run:

- **`fill`** / **`clone`** - detected case-insensitively, leading slash optional (`fill ...`,
  `/FILL ...`), and only when it's the actual command token - `/function ns:fill_platform` or
  `/say fill` are never misclassified. For `clone`, only the six *source* coordinates affect
  the estimate; the destination doesn't change how much gets read/written.
- **`place`** - any `/place structure|feature|jigsaw|template ...`. Block/chunk cost isn't
  estimated (there's no cheap way to know how big a structure or feature is), so a fixed
  minimum applies.
- **`summon`** - a single summon is cheap, so its default minimum is much smaller than the
  others; the point is just to stop a batch of thousands of summons from firing at the
  fastest possible rate, not to model entity cost.
- **`execute ... run <command>`** - unwrapped (including nested chains like
  `execute as @a run execute at @s run fill ...`) so a heavy command hidden behind `/execute`
  is still detected and protected, while the full `execute ...` command is still what's
  actually sent to the server, unchanged.

Everything else - `/say`, `/time`, `/weather`, `/gamerule`, `/setblock`, `/kill`,
`/teleport`, and so on - stays at the plain configured delay; Heavy Command Protection doesn't
maintain a database of every Minecraft command, only these specific categories. It also never
looks inside a `/function`, since the runner can't safely know what a datapack function will
do without executing it.

Defaults: Fill minimum 10 ticks, Clone minimum 10 ticks, Place minimum 20 ticks, Summon
minimum 2 ticks (each independently configurable, 0-200). For `fill`/`clone` specifically, when
the command uses simple absolute coordinates, the mod also estimates its block count and may
raise the effective delay further for very large regions (over roughly 10k/32k blocks). Fills
or clones using relative (`~`) or local (`^`) coordinates, selectors, or anything else that
doesn't parse as plain numbers simply fall back to the configured minimum for that type - no
Minecraft command parsing is duplicated to make this estimate. Turning Heavy Command Protection
off restores the plain configured delay for every command, with no minimums applied.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3+
- Fabric API 0.156.0+26.2
- Java 25

## Building

This source project follows Fabric's official 26.2 template versions. Open it in IntelliJ IDEA or another Gradle-capable IDE with JDK 25, then run:

```bash
./gradlew build
```

The built mod JAR will be in `build/libs/`.

This archive does not contain the Gradle wrapper JAR because the generation environment could not download binary dependencies. If your IDE does not supply Gradle, generate/add a Gradle wrapper or copy the wrapper files from Fabric's official 26.2 example-mod template.

## Usage

1. Install Fabric Loader and Fabric API for 26.2.
2. Put the built JAR in `.minecraft/mods/`.
3. Launch Minecraft and join/open a world where you have permission to run the commands.
4. Press **/**.
5. Paste commands, for example:

```mcfunction
/fill -500 1700 -10 500 1700 10 snow_block replace air
/fill -499 1700 -20 499 1700 20 snow_block replace air
/fill -498 1700 -30 498 1700 30 snow_block replace air
```

6. Set the delay, and leave Heavy Command Protection on (default) unless you specifically want
   fill/clone/place/summon sent at the plain delay too.
7. Click **Run Commands**. Use **Pause**/**Resume**/**Stop** as needed while it runs.

The mod sends commands through the normal client command path, so server permissions and command restrictions still apply.

## Author

Ac3 ([@ac3codes](https://github.com/ac3codes) on GitHub)
