Hello everyone! I'm Ac3. I'm writing this because I have to confess that I vibe-coded this project. I didn't know much about modding, but needed a mod that could run a large series of commands, so I vibe-coded it. I personaly didn't like that I was dependent on AI for this but I don't have enough time to pick up java, so I apologize for that. All the designs and ui decisions are made by me. Anyway, thanks for using my mod.

# Batch Command Runner (Fabric 26.2)

Client-side Fabric mod for Minecraft Java 26.2.

## Features

- Press **B** (configurable in Controls) to open the UI.
- Large multiline, scrollable, wrapping command box.
- Paste one command per line.
- Leading `/` is optional.
- Blank lines are ignored.
- Lines beginning with `#` are treated as comments and ignored.
- Configurable delay in client ticks between commands.
- Live parsed command count.
- Status: Idle / Running / Completed / Stopped / Error.
- Highlighted **NEXT #n / total** bar showing the exact next command.
- **Next: none** after completion/stopping.
- Progress counter while running.
- Stop button.
- Text and delay value are retained when the screen is reopened during the same game session.
- UI does not pause single-player, so the command runner can continue while open.

## Delay semantics

- `0`: send the next command on the next available client tick.
- `1`: leave one full client tick between command sends.
- `20`: leave twenty full client ticks (about one second at 20 TPS) between commands.

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
4. Press **B**.
5. Paste commands, for example:

```mcfunction
/fill -500 1700 -10 500 1700 10 snow_block replace air
/fill -499 1700 -20 499 1700 20 snow_block replace air
/fill -498 1700 -30 498 1700 30 snow_block replace air
```

6. Set the delay.
7. Click **Run Commands**.

The mod sends commands through the normal client command path, so server permissions and command restrictions still apply.

## Author

Ac3 ([@ac3codes](https://github.com/ac3codes) on GitHub)
