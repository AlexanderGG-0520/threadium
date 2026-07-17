#!/usr/bin/env python3
"""Summarize the latest valid VANILLA/BATCHING break-even sweep results."""

import argparse
import json
from pathlib import Path

COUNTS = (16, 32, 64, 128, 256, 512, 1024)
SCENES = ("static", "animated")
MODES = ("VANILLA", "BATCHING")


def percentage(baseline: float, candidate: float, higher_is_better: bool = True) -> float:
    if baseline == 0:
        return 0.0
    if higher_is_better:
        return (candidate / baseline - 1.0) * 100.0
    return (1.0 - candidate / baseline) * 100.0


def latest_valid(directory: Path):
    selected = {}
    for path in directory.glob("*.json"):
        try:
            trial = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        key = (trial.get("scene"), trial.get("expectedEntityCount"), trial.get("mode"))
        if key[0] not in SCENES or key[1] not in COUNTS or key[2] not in MODES:
            continue
        if trial.get("status") != "VALID":
            continue
        timestamp = str(trial.get("timestamp", ""))
        previous = selected.get(key)
        if previous is None or timestamp > previous[0]:
            selected[key] = (timestamp, trial, path)
    return selected


def value(trial, name):
    raw = trial.get(name)
    return None if raw is None else float(raw)


def format_number(number, digits=2):
    return "—" if number is None else f"{number:.{digits}f}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", nargs="?", default="run/benchmarks/threadium")
    args = parser.parse_args()
    results = latest_valid(Path(args.directory))

    missing = []
    for scene in SCENES:
        print(f"## {scene.upper()}")
        print()
        print("| Entities | Vanilla FPS | Batching FPS | FPS Δ | Vanilla 1% Low | Batching 1% Low | 1% Low Δ | World Render Δ |")
        print("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        break_even = None
        for count in COUNTS:
            vanilla_entry = results.get((scene, count, "VANILLA"))
            batching_entry = results.get((scene, count, "BATCHING"))
            if vanilla_entry is None or batching_entry is None:
                missing.append((scene, count))
                print(f"| {count} | — | — | — | — | — | — | — |")
                continue
            vanilla = vanilla_entry[1]
            batching = batching_entry[1]
            vanilla_fps = value(vanilla, "averageFps")
            batching_fps = value(batching, "averageFps")
            vanilla_low = value(vanilla, "onePercentLowFps")
            batching_low = value(batching, "onePercentLowFps")
            vanilla_world = value(vanilla, "worldRenderAverageNanos")
            batching_world = value(batching, "worldRenderAverageNanos")
            fps_delta = percentage(vanilla_fps, batching_fps)
            low_delta = percentage(vanilla_low, batching_low)
            world_delta = percentage(vanilla_world, batching_world, higher_is_better=False)
            if break_even is None and fps_delta > 0.0:
                break_even = count
            print(
                f"| {count} | {format_number(vanilla_fps)} | {format_number(batching_fps)} "
                f"| {fps_delta:+.2f}% | {format_number(vanilla_low)} | {format_number(batching_low)} "
                f"| {low_delta:+.2f}% | {world_delta:+.2f}% |"
            )
        print()
        print("Average-FPS break-even:", break_even if break_even is not None else "not reached")
        print()

    if missing:
        unique = sorted(set(missing), key=lambda item: (SCENES.index(item[0]), item[1]))
        print("Missing VANILLA/BATCHING pairs:")
        for scene, count in unique:
            print(f"- {scene}: {count}")


if __name__ == "__main__":
    main()
