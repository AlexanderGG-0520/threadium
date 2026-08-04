# Beta.7 render hot-path implementation

The remaining production work required before beta.7 real-machine validation is implemented on this branch.

## Implemented

- compatible non-sorted ModelPart batches now share one Blaze3D render pass per prepared pipeline group;
- the per-instance `Queued` record and copied `Matrix4f` allocation were replaced by a bounded frame-local structure-of-arrays arena;
- sorted ModelPart distance-key preparation reads the arena's flat root-matrix storage directly;
- ImmediatelyFast 1.16.2+26.2 compatibility is explicitly version-bounded and retains the vanilla `BufferBuilder` boundary;
- unknown or future wrapped vertex consumers are never reflectively unwrapped and continue through vanilla fallback;
- focused arena and compatibility regression tests were added.

The release version remains unchanged until Java 25 CI and real-machine mixed-scene benchmarks pass.
