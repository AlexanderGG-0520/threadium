# Threadium Phase 3 — Conservative batching threshold

Phase 2 median calibration at 256 static cows:

- VANILLA average frame: 1,426,118 ns
- BATCHING average frame: 872,070 ns
- BATCHING intercept: 185,445 ns/frame
- BATCHING flush: 36,365 ns/frame
- Bone/instance packing: 12,660 ns/frame
- Instance upload: 2,053 ns/frame

Derived model:

- Estimated common frame cost: 650,259 ns
- Estimated vanilla entity-path cost: 3,031 ns per instance
- Estimated Threadium variable cost: 782 ns per instance
- Estimated Threadium fixed batch cost: 21,652 ns
- Raw break-even: 10 instances
- Break-even with 15% safety margin: 13 instances
- Recommended production threshold: 16 instances

Decision:

Keep `entity.gpu.minimumGroupSubmits=16`. The existing frame-delayed exact-identity gate already rejects groups below the configured threshold before topology lookup, pose extraction, packing, and upload. Do not replace it with a more complex runtime cost estimator based on a single calibration scene.

Required targeted validation:

- Static group 15: should remain vanilla
- Static group 16: should become eligible on the next stable frame
- Static group 32: should show clear positive margin
- Animated validation can be limited to 16 and 32 later

The model is a conservative admission-policy calibration, not a general GPU simulator.
