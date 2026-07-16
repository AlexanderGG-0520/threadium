# Threadium

Threadium is a Java-only Fabric client mod investigating safe CPU-side render
preparation for Minecraft Java Edition. Phase 0 contains instrumentation and a
bounded, idle worker-lifecycle foundation only: it does not submit render work,
issue GPU calls, or alter rendering.

The initial target is unobfuscated Minecraft 26.2. It uses non-remapping Fabric
Loom and intentionally declares no mappings dependency.
