# Beta.7 render hot-path scope

This branch implements the remaining production work before beta.7 validation:

- consolidate compatible non-sorted draws into one Blaze3D render pass per prepared pipeline group;
- replace per-instance queued records and copied `Matrix4f` objects with a bounded reusable struct-of-arrays arena;
- add an explicit, version-bounded ImmediatelyFast 1.16.2 compatibility classifier which only accepts semantically transparent consumers and otherwise preserves vanilla fallback;
- add focused regression tests and run the complete Java 25 validation suite.

The branch does not change the release version until implementation and validation are complete.
