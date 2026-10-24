# Holo Signs

A standalone holographic signs and displays mod for **Minecraft: Java Edition 26.1.2** on **NeoForge**.

## Project goals

- Place configurable hologram projectors that render readable floating text and simple displays in the world.
- Make signs useful out of the box: editing and displaying content must not require another mod, a computer, or a network.
- Offer optional computer integrations so in-game programs can update displays dynamically.
- Keep the core mod independent from optional integrations, so missing computer mods never prevent the base mod from loading.

## Compatibility target

- Minecraft: Java Edition **26.1.2**
- NeoForge: **26.1.2.122** (the newest NeoForge build verified for the 26.1.2 Minecraft line when this project was created; NeoForge's overall latest release may target a different Minecraft version)

## Planned integrations

- **ComputerCraft / CC:Tweaked** — optional peripheral for writing and controlling displays from Lua.
- **OpenComputers** — optional integration for computer-driven displays. The intended target is the OpenComputers mod maintained by [@Mantoku1](https://github.com/Mantoku1); its repository and API still need to be confirmed before implementation.
- **Other mods** — consider integrations where they provide a useful computer, automation, or display API, without adding mandatory dependencies to the core.

The integration API and supported mod versions are not implemented or finalized yet. The core standalone sign workflow remains the baseline.

## Development status

This repository is the project starting point. Features, the build setup, and integration compatibility are still to be implemented and tested.

## License

No license has been selected yet.
