# Holo Signs

Standalone holographic signs and displays for Minecraft 26.1.2 on NeoForge, with optional computer-mod control.

## What it does

- Place a **Hologram Projector** block.
- Right-click to open an in-game editor with up to **8 lines** of floating text.
- Lines persist on the block entity and render floating above the projector.
- Works standalone with no computer mods installed.

## Compatibility target

- Minecraft Java Edition **26.1.2**
- NeoForge **26.1.2.122**
- Java **25** (toolchain required to build)

## Optional integrations

- **CC:Tweaked / ComputerCraft** — generic peripheral (`holosigns:projector`) exposing `getLines`, `setLine`, `setLines`, and `clearLines` from Lua. Registered only when `computercraft` is present; never required.
- **OpenComputers: Modernized** — `hologram_projector` component with `getLines`, `setLine`, `setLines`, and `clearLines` callbacks. The OC-capable block entity lives in `src/optional/opencomputers/java` and is compiled only with `-Poc_compile_integrations=true`; the core mod builds and runs without it. Target: `https://github.com/Mantoku1/OpenComputers-NeoForge-26.1`.

## Build

```bash
./gradlew build          # core mod: standalone + CC:Tweaked
./gradlew runServer      # dedicated-server smoke test (run/eula.txt must exist)
```

OpenComputers integration (extract the OC 26.1 source archive so that `.opencomputers-api/Mantoku1-OpenComputers-NeoForge-26.1-*/` exists first):

```bash
./gradlew -Poc_compile_integrations=true build
```

The resulting jar is `build/libs/holosigns-<version>.jar`. OC API sources are compiled from that archive and are not bundled into the jar; the OpenComputers classes are only present in the jar when the integration is enabled.

## Status

- Core standalone workflow, CC:Tweaked integration, and the OpenComputers integration compile and jar against Minecraft 26.1.2 / NeoForge 26.1.2.122.
- The dedicated server loads the mod and registers the projector block, item, block entity, and creative tab without errors.
- Verified in a real 26.1.2 client: the projector model and texture load, the hologram renders above the block, and the eight-line editor opens and saves changes back to the display.
- Talking to the block from an actual ComputerCraft or OpenComputers computer (Lua/OC component calls) has not been exercised in this workspace.
