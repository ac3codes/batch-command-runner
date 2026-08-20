### Author's Message
Hello everyone! I'm Ac3. I'm writing this because I have to confess that I vibe-coded this project. I didn't know much about modding, but needed a mod that could run a large series of commands, so I vibe-coded it. I personally didn't like that I was dependent on AI for this but I don't have enough time to pick up java, so I apologize for that. All the designs and ui decisions are made by me. Anyway, thanks for using my mod.

# Batch Command Runner

## Basic Information
Minecraft ver: 26.2
API: Fabric

Description:
This mod is a utility mod that makes running commands much easier. Instead of needing to manually copy-paste every single command one-by-one, you can paste them all into the mod's text window and run them all.

**Note:** This mod requires you to have the ability to run commands, so in servers or worlds where you can't, no commands will be run.

## Install
1. Install Fabric Loader and Fabric API for Minecraft 26.2.
2. Put this mod's JAR file into your `.minecraft/mods/` folder.
3. Launch Minecraft using the Fabric profile.

## Keybind
Press **/** (forward slash) to open the mod's UI. This is rebindable in Controls if it conflicts with anything. You can also type `/batch` in chat to open it.

## Process
1. Type/copy-paste the commands you want to run.
2. Click the run button.
3. Mod runs all the commands sequentially with delays to preserve game performance.
It's as simple as that!

**Note:** A "session" (referenced below) means one full run of your pasted commands, from pressing Run Commands until it finishes, is stopped, or errors out.

## Features
- Minecraft-based UI: This mod uses a Minecraft-like UI to ensure that same feel. We intentionally styled it a bit like Minecraft to ensure that users of the mod would find it comfortable compared to the original game.
- Command Suggestions: The mod provides suggestions on what to write next to ensure a complete, executable command. For example, it displays "ghost text" (as I like to call it) that provides a preview of what to type next. Furthermore, when typing, suggestions may pop up, similar to the original vanilla command system.
- Heavy-Protection: This is a feature that is exclusive to this mod. This is the recommended delays that we have set up for certain commands since these ones can cause HUGE lag spikes if not turned on. While this is customizable and turnoff-able, I recommend having this on since it's for large/expensive commands (like fill, clone, place, and summon).
- Pause/Resume: The mod lets you pause and resume the running session. The pause button turns into the resume button. Editing the commands is only allowed while paused; doing so immediately stops the entire sequence, so you will need to run it again.
- Run Commands/Stop: This is a single button. It reads "Run Commands" while idle; once a batch starts, it becomes "Stop" for as long as the session is running or paused. Pressing it while running or paused completely stops the session and resets progress back to zero (unlike Pause, this can't be resumed). The commands are uneditable while the session is actually running.
- Clear: Clears all the commands. Only available while idle (not during a running or paused session).

## License
MIT License. Copyright (c) 2026 Ac3. See LICENSE for full details.

