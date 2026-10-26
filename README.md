# Holo Signs

[![CI](https://github.com/Brendan111111111/holo-signs/actions/workflows/build.yml/badge.svg)](https://github.com/Brendan111111111/holo-signs/actions/workflows/build.yml)

Standalone holographic signs and displays for Minecraft 26.1.2 on NeoForge, with optional computer-mod control.

## What it does

- Place a **Hologram Projector** on any surface: floor, ceiling or wall.
- It projects away from the surface it was placed on. Sneak-use cycles through all six directions
  (up, north, east, south, west, down).
- Right-click opens an editor with up to **8 lines** of text, a **Facing** button, a **Distance**
  control (1–16 blocks) and a **Size** control (25–300%).
- Size scales the glyphs and the display panel's width and height together, so the backing grows
  with the text instead of only stretching the font.
- Holograms are billboarded to the viewer, so text stays upright and readable even when the
  projector points down from a ceiling or sideways from a wall.
- The text sits on an emissive **teal hologram panel** with a darker fill, brighter edge and a
  soft glow. The background colour is configurable as a six-digit RGB value (`#RRGGBB`), and the
  brighter frame and glow are tinted from the same colour. The panel and text are depth-tested, so
  solid blocks in front of the hologram hide it instead of letting the display shine through walls
  or floors.
- The projection itself is occluded too: a closed door or any solid geometry whose collision shape
  crosses the beam stops the hologram from appearing on the far side. Closed doors are treated as
  full blockers, not just the thin door slab, so a hologram can never be read through a closed
  door. An open door is a real opening and lets the projection continue through it.
- **Camouflage** the projector from its editor: hold a regular block and click the Camo slot to
  disguise the projector as that block; right-click the slot to clear it. The disguise is visual
  only, so collision, hologram behaviour and computer peripherals stay unchanged. Blocks with
  block entities (chests, signs, banners, and similar) are not available as camouflage.
- Works standalone with no computer mods installed.

## Compatibility target

- Minecraft Java Edition **26.1.2**
- NeoForge **26.1.2.122**
- Java **25** (toolchain required to build)

## Optional integrations

Both integrations share the same character grid as the hologram, so a computer can use the
projector as a screen instead of as a sign.

- **CC:Tweaked / ComputerCraft** — the peripheral reports itself as a **`monitor`** (with
  `holosigns:projector` as an additional type). `peripheral.find("monitor")` finds it, and the
  standard monitor calls work: `write`, `clear`, `clearLine`, `getCursorPos`, `setCursorPos`,
  `getSize`, `scroll`, `setTextColour`, `getTextColour`, `setBackgroundColour`,
  `getBackgroundColour`, `isColour`, `blit`. Monitor text is 24×10 cells by default (32×12 max) and
  keeps per-cell foreground colours. The original line calls (`getLines`, `setLine`, `setLines`,
  `clearLines`) and `getDirection`, `setDirection`, `getDistance`, `setDistance`, `getMode`,
  `setMode` are available on the same peripheral.
- **OpenComputers: Modernized** — the projector block entity implements OpenComputers' `TextBuffer`
  screen, so a graphics card can bind it and `term` draws on the hologram like on a monitor. The
  terminal grid follows the hologram's **Size** setting: 100% gives 24×10 cells, smaller
  projections shrink toward 8×3 and larger ones grow toward 32×12, so a bigger display has more
  room for text instead of only magnifying the same grid. Changing the size while a computer is
  bound resizes the buffer and sends the usual `screen_resized` signal. The `hologram_projector`
  component keeps the line callbacks plus the direction, distance and mode callbacks.
  - The **Screen Linker** (OpenComputers' wireless monitor tool) works with the projector:
    right-click the computer case with the linker to select it, then right-click the projector to
    bind the hologram to that computer over the same 64-block range as a normal screen.
    Sneak-right-click the projector with the linker to unbind it. The projector keeps the link
    across world reloads.
  - A linker click never opens the projector editor while the integration is available. The click
    is intercepted in the main jar before the block interaction runs, so it works even while
    sneaking (needed for unbinding). If this build has no OpenComputers integration, or the
    installed OpenComputers version cannot be bound, the click falls back to the normal block
    interaction so the editor still opens instead of leaving a dead right-click.
  - The OC-capable block entity lives in `src/optional/opencomputers/java`; the core mod builds and
    runs without it. Target: `https://github.com/Mantoku1/OpenComputers-NeoForge-26.1`.

Linking a computer or a computer write switches the hologram to monitor mode automatically; the
editor or `setMode("lines")` switches it back to the authored lines. A blank terminal stays a blank
monitor instead of falling back to the saved lines. Background colours are stored and reported
through the APIs but are not drawn; the hologram uses the teal display panel as its backing instead
of per-cell terminal backgrounds.

### Saved links re-bind after a restart

A projector keeps its linked computer across a world reload. On load it tries to rejoin that
computer's OpenComputers network right away; if the case's block entity or network node is not up
yet, it retries from the server tick every **10 ticks (half a second)** until the link is back. No
second Screen Linker click is needed, and the retry stops as soon as the projector and computer are
on the same network.

## Performance and safety

- Computer updates are batched. A graphics-card row write notifies the block entity once, not once
  per character, and all pending display changes are coalesced into at most one client update per
  projector per server tick.
- Monitor rows and colour arrays are cached by a terminal version, so a visible projector does not
  allocate or copy its whole grid every frame. The hologram panel is submitted as one custom-geometry
  batch per projector, and the render bounding box follows the configured projection distance and
  hologram size instead of always using the maximum range.
- Camouflage resolves the chosen block through the vanilla block model resolver only for visible
  projectors. The disguise keeps rendering to the chunk view distance, while hologram text stops
  at 64 blocks so a distant camouflaged projector does not pay for glyph rendering.
- Computer-facing input is bounded: write length, grid width/height, viewport, line count/length,
  palette indexes and raw graphics arrays are clamped; the Screen Linker range and target type are
  validated on the server.
- The editor payload decodes strings with a maximum length before allocating them, and the server
  revalidates reach, block entity type, facing, distance and every line before applying a save.

## Build

```bash
gradle build          # core mod: standalone + CC:Tweaked (+ OpenComputers when its source is available)
gradle test           # unit tests for terminal grid scaling and the pending-update queue
gradle stageOpenComputersTestMod  # install the test-only OpenComputers jar into run/mods
gradle runGameTestServer  # tick flush, projection occlusion, and the OpenComputers link test
gradle runServer      # dedicated-server smoke test (run/eula.txt must exist)
```

CI runs `gradle build` (which includes the unit tests) and `gradle runGameTestServer` on every
push and pull request. The game-test job stages the OpenComputers test jar from `libs/` into
`run/mods` first, so the Screen Linker test runs there too. Every run attaches the built mod jar,
and failed runs upload the test report, server log and crash reports. Pushing a `v<version>` tag
(matching `mod_version`) runs the same checks and publishes the jar in a GitHub Release.

The OpenComputers integration is compiled automatically when the OC 26.1 source archive is present
at `.opencomputers-api/Mantoku1-OpenComputers-NeoForge-26.1-*/`, or against the test jar at
`libs/opencomputers-neoforge-1.11.0.jar` when the archive is absent. To require it (and fail the
build when neither source is available) use:

```bash
gradle -Poc_compile_integrations=true build
```

The resulting jar is `build/libs/holosigns-<version>.jar`. The OpenComputers API is compile-only
and is not bundled into the jar, so the jar still loads without OpenComputers installed. When the
integration is enabled, its classes are compiled into the normal mod output, so Gradle's
`runClient`/`runServer` configurations load the same `TextBuffer` block entity as the packaged jar
instead of falling back to the plain projector.

## Status

- Core standalone workflow, CC:Tweaked integration, and the OpenComputers integration compile and
  jar against Minecraft 26.1.2 / NeoForge 26.1.2.122.
- The dedicated server loads the mod and registers the projector block, item, block entity, and
  creative tab without errors.
- Earlier client verification covered projector placement, direction cycling, the editor's facing
  and distance controls, and upright billboarded text on floors, ceilings and walls. The teal
  display panel, depth-tested occlusion, projection-path occlusion (including closed doors), size,
  background colour and camouflage controls were added afterwards; both builds succeed, but that
  visual change still needs a client screenshot pass.
- Verified with a real CraftOS computer: `peripheral.find("monitor")` returns the projector, and
  `clear`, `setCursorPos`, `write`, `getSize`, `getCursorPos`, `setTextColour` and `isColour` all
  drive the hologram, including red/green text.
- The Screen Linker path is verified on the server: the projector's block entity is created through
  the shared factory, so the `TextBuffer`-capable subclass is used for both placement and chunk
  loading; before that fix the block constructed the plain block entity and computers had nothing
  to bind to. A live client screenshot pass is still pending: an earlier test showed the authored
  lines after linking because the renderer fell back to them while the terminal was blank. The
  renderer now treats monitor mode as authoritative, and `projector_oc_bind` checks that the block
  update tag carries that mode to the client.
- The OpenComputers grid now follows the hologram Size setting: 25% gives 8×3, 100% gives 24×10
  and 150% or more gives 32×12, clamped by the terminal's own limits. The mapping and the
  resize/signal path build cleanly and the dedicated-server smoke test passes; a live
  graphics-card rebind after changing the size still needs an in-game check.
- The `holosigns:projector_tick_flush` game test runs in a live `GameTestServer` level: it places a
  projector, writes to its terminal during a tick, and checks the end-of-tick flush drains the
  queued update by the next tick. It passes alongside vanilla's `always_pass`.
- The `holosigns:projector_occlusion` game test drives the same obstruction flag the renderer uses
  to hide a hologram: an empty path reads clear, a solid block blocks and clears when removed, a
  closed oak door blocks despite its thin panel, and an open door clears. The check is repeated for
  all six facings (floor, ceiling and the four walls). The last block within the projection
  distance still blocks, while a block flush with the display plane and one beyond the projection
  distance do not.
- The `holosigns:projector_oc_bind` game test places a real OpenComputers case and projector,
  installs a graphics card in the case, selects the case with the Screen Linker and right-clicks
  the projector. It checks the projector switches to monitor mode and that the graphics card's
  bound screen is the projector's `TextBuffer`. CI stages the test jar from `libs/` into `run/mods`
  so this test runs on every push; a headless run without that mod still executes the remaining
  tests.
- The `holosigns:projector_oc_relink` game test covers a world reload: it links a projector to a
  real OpenComputers case, replaces the case, and checks the projector rejoins the computer's
  network without another Screen Linker click. It then installs a graphics card and checks the
  card binds the projector's `TextBuffer`. The projector retries its saved link from the server
  tick every 10 ticks, so a case that loads after the projector still reconnects.
- The optional OpenComputers classes are compiled into the main mod output, so `gradle runClient`
  and `gradle runServer` get the same projector block entity as the jar. Previously they loaded
  only `build/classes/java/main`, fell back to the plain block entity, and a Screen Linker click
  had no integration to handle it. A click that still cannot be handled now opens the editor
  rather than being swallowed.
- CI is green on `main`: the latest Build runs, including the one for the reconnect fix, passed
  `gradle build`, the staged OpenComputers test jar, and `gradle runGameTestServer` with the link
  and relink tests. The reconnect fix and its README note are pushed as `4d1e99c` and `455acd0`.
