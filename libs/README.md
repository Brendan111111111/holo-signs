# Test-only mods

`opencomputers-neoforge-1.11.0.jar` is OpenComputers: Modernized 1.11.0 for Minecraft 26.1.2 /
NeoForge 26.1.2.102 (MIT). It is kept here so the optional integration can be compiled and the
headless `holosigns:projector_oc_bind` game test can place a real computer and graphics card without
downloading an unpublished 26.1 build.

The jar is not packaged into Holo Signs and is not needed to run the mod. CI verifies its
SHA-256 (`862e762c...eaf1f9`) before building; update that checksum in both workflow files if the
jar is replaced.
