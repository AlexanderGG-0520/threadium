# Mixed-scene ModelPart profitability

Threadium's production admission path must optimize real mixed entity scenes, not only homogeneous benchmark populations.

The current policy uses three safeguards:

1. A stable feature-renderer identity, model root, and RenderType tuple must meet the configured minimum in the previous frame.
2. Actual flush statistics are fed back into admission. Groups dominated by singleton draws or low multi-instance coverage enter a bounded vanilla cooldown.
3. Animated populations stop exact pose-dedup probing quickly when misses dominate, while frame-local pose arrays are pooled to avoid steady-state allocation pressure.

The policy deliberately keeps unsupported consumers and pipelines on vanilla rendering. It does not unwrap third-party VertexConsumer implementations without a verified compatibility contract.

Validation must compare identical mixed-Mob scenes with Threadium enabled and disabled, recording average FPS, 1% low, accepted/fallback counts, instances per draw, and multi-instance coverage. Homogeneous cow scenes remain useful for scaling tests, but are not sufficient evidence for normal gameplay performance.
