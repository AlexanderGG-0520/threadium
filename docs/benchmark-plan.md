# Threadium benchmark plan

Threadium targets CPU-bound render preparation. It may offer little or no FPS
gain when GPU use is already near 100%.

For each baseline and change: warm up 3 minutes, use a fixed seed/test world,
camera path or fixed position, fixed settings (view/simulation distance,
resolution, shader state, VSync and FPS cap), fixed JVM arguments, and the same
complete mod list. Run at least five 120-second captures.

Record Minecraft, Fabric Loader/API, Sodium, Java and Threadium versions; average
FPS; median, 1% and 0.1% frame times; maximum frame time; frame-time distribution
and stutter count; CPU/per-core and GPU utilization; VRAM; allocation rate; and
GC pauses.

Planned scenes: normal vanilla world; many stationary and moving entities; armor
stands; item entities; block entities; high render distance; Sodium without
shaders; Iris with shaders disabled/enabled; and a later Create-heavy scene.
