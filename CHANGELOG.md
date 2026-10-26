# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) with a 0.x version line.

## [0.2.0] - 2026-10-26

The interactive release: a linked hologram is now a terminal you can type into, and the projected
screen renders as a real character grid.

### Added

- **Type into the computer from the hologram.** Right-clicking a linked projector's floating panel
  opens a terminal screen for that terminal. Keys, mouse presses and drags, the scroll wheel and
  clipboard pastes are forwarded to the bound computer, so shells and programs that read the
  keyboard or touch the screen work on the hologram as they do on a monitor.
- The panel reports the computer's own keyboard address, which is what OpenComputers' terminal
  checks before accepting a key, so typing works even when the hologram is far from the keyboard.
- Opening the terminal copies the screen the graphics card is currently drawing, so a shell that
  started before the projector was linked fills the panel instead of staying blank.
- The terminal grid follows the projector's Size setting: 8x3 cells at 25%, 24x10 at 100%, up to
  32x12 at 150% and above.
- Unicode text is repaired for the projector and terminal: text that OpenComputers handed over one
  cp1252 byte per character is decoded as UTF-8, so box-drawing frames and other non-ASCII
  characters arrive intact instead of as three garbled cells.
- Box-drawing characters are drawn as exact lines in both the in-world panel and the terminal
  screen, with single, heavy, double, mixed-frame and rounded styles.

### Fixed

- Saving the projector editor no longer ends a computer's view. Changing the size, colour, facing
  or distance sends the authored lines back with the rest of the settings, and that save used to
  switch the hologram out of monitor mode, so the link looked dead until the computer wrote again
  or was rebooted. Only an actual text edit takes the hologram back now.
- Terminal text is drawn one glyph per grid cell. Words used to be stretched to fill their cells,
  which smeared letters into each other, pushed text past the panel and left several cells of gap
  between words.
- Box-drawing and other wide fallback glyphs no longer overlap neighbouring cells, so frame lines
  stay inside the panel and stop running through words.

### Changed

- The terminal screen uses the same bezel, panel colours and cell layout as the in-world hologram,
  scaled up to the window size, so it reads as the projected panel pulled closer.

## [0.1.0]

Initial projector mod: placeable holographic projectors, up to eight lines of authored text, the
in-world editor, CC:Tweaked and OpenComputers monitor support, and saved links that re-bind after a
world reload.
