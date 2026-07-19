#!/usr/bin/env python3
"""Derive a conservative ModelPart batching threshold from Phase 2 benchmark medians."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


def require_number(obj: dict[str, Any], key: str) -> float:
    value = obj.get(key)
    if not isinstance(value, (int, float)):
        raise ValueError(f"Missing numeric field: {key}")
    return float(value)


def next_power_of_two(value: int) -> int:
    if value <= 1:
        return 1
    return 1 << (value - 1).bit_length()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Estimate the conservative batching threshold from Phase 2 summary JSON."
    )
    parser.add_argument(
        "summary",
        nargs="?",
        default="/tmp/threadium-phase2-summary.json",
        help="Phase 2 summary JSON path",
    )
    parser.add_argument(
        "--entities",
        type=int,
        default=256,
        help="Entity count used by the Phase 2 benchmark",
    )
    parser.add_argument(
        "--safety-ratio",
        type=float,
        default=0.85,
        help="Require Threadium cost to be no more than this fraction of vanilla cost",
    )
    parser.add_argument(
        "--max-group",
        type=int,
        default=32,
        help="Largest group size shown in the simulation table",
    )
    args = parser.parse_args()

    if args.entities <= 0:
        raise ValueError("--entities must be positive")
    if not 0.0 < args.safety_ratio <= 1.0:
        raise ValueError("--safety-ratio must be in (0, 1]")
    if args.max_group <= 0:
        raise ValueError("--max-group must be positive")

    data = json.loads(Path(args.summary).read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("Summary root must be a JSON array")

    modes = {str(entry.get("mode")): entry for entry in data if isinstance(entry, dict)}
    vanilla = modes.get("VANILLA")
    batching = modes.get("BATCHING")
    if vanilla is None or batching is None:
        raise ValueError("Summary must contain VANILLA and BATCHING entries")

    vanilla_frame = require_number(vanilla, "averageFrameNanosMedian")
    batching_frame = require_number(batching, "averageFrameNanosMedian")
    intercept = require_number(batching, "interceptNanosPerFrameMedian")
    flush = require_number(batching, "flushNanosPerFrameMedian")
    packing = require_number(batching, "boneAndInstancePackingNanosPerFrameMedian")
    instance_upload = require_number(batching, "instanceUploadNanosPerFrameMedian")

    # Conservative decomposition:
    # - Common frame cost is estimated by removing measured Threadium intercept/flush work.
    # - Vanilla incremental cost is spread across all benchmark entities.
    # - Threadium per-instance cost includes intercept, packing, and instance upload.
    # - Remaining flush work is treated as fixed per accepted batch.
    common_frame = batching_frame - intercept - flush
    vanilla_incremental = vanilla_frame - common_frame
    vanilla_per_instance = vanilla_incremental / args.entities
    threadium_per_instance = (intercept + packing + instance_upload) / args.entities
    threadium_fixed_batch = flush - packing - instance_upload

    raw_denominator = vanilla_per_instance - threadium_per_instance
    safe_denominator = args.safety_ratio * vanilla_per_instance - threadium_per_instance
    if raw_denominator <= 0:
        raise ValueError("Model predicts no raw break-even")
    if safe_denominator <= 0:
        raise ValueError("Model predicts no break-even at the requested safety ratio")

    raw_threshold = math.ceil(threadium_fixed_batch / raw_denominator)
    safe_threshold = math.ceil(threadium_fixed_batch / safe_denominator)
    recommended_threshold = next_power_of_two(safe_threshold)

    print("# Threadium Phase 3 threshold model")
    print()
    print(f"- Entities in calibration run: {args.entities}")
    print(f"- Estimated common frame cost: {common_frame:.1f} ns")
    print(f"- Estimated vanilla cost per instance: {vanilla_per_instance:.1f} ns")
    print(f"- Estimated Threadium cost per instance: {threadium_per_instance:.1f} ns")
    print(f"- Estimated Threadium fixed batch cost: {threadium_fixed_batch:.1f} ns")
    print(f"- Raw break-even threshold: {raw_threshold}")
    print(
        f"- Safety-adjusted threshold ({(1.0 - args.safety_ratio) * 100:.0f}% margin): "
        f"{safe_threshold}"
    )
    print(f"- Recommended production threshold: {recommended_threshold}")
    print()
    print("| Group | Vanilla path ns | Threadium path ns | Savings ns | Safety pass |")
    print("| ---: | ---: | ---: | ---: | :---: |")

    for group in range(1, args.max_group + 1):
        vanilla_cost = vanilla_per_instance * group
        threadium_cost = threadium_fixed_batch + threadium_per_instance * group
        savings = vanilla_cost - threadium_cost
        safety_pass = threadium_cost <= args.safety_ratio * vanilla_cost
        print(
            f"| {group} | {vanilla_cost:.1f} | {threadium_cost:.1f} | "
            f"{savings:+.1f} | {'yes' if safety_pass else 'no'} |"
        )


if __name__ == "__main__":
    main()
