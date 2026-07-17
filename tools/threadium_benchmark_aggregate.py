#!/usr/bin/env python3
"""Aggregate valid Threadium benchmark summary JSON files using only stdlib."""
import argparse
import json
import statistics
from pathlib import Path

METRICS = [
    "averageFps", "onePercentLowFps", "averageFrameNanos", "p95FrameNanos", "p99FrameNanos",
    "worldRenderAverageNanos", "featurePreparationAverageNanos", "groupPreparationAverageNanos",
    "drawCalls", "instancesPerDraw", "drawReductionRatio", "boneBytesUploaded", "instanceBytesUploaded",
]

def summarize(values):
    return {"mean": statistics.fmean(values), "median": statistics.median(values), "standardDeviation": statistics.pstdev(values)}

def improvement(metric, baseline, new):
    if baseline == 0:
        return None
    if metric in {"averageFps", "onePercentLowFps", "instancesPerDraw", "drawReductionRatio"}:
        return (new / baseline - 1.0) * 100.0
    return (1.0 - new / baseline) * 100.0

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", nargs="?", default="run/benchmarks/threadium")
    parser.add_argument("--output", default="aggregate.json")
    args = parser.parse_args()
    trials = [json.loads(path.read_text()) for path in sorted(Path(args.directory).glob("*.json"))]
    result = {"schemaVersion": 1, "modes": {}, "comparisons": {}}
    for mode in ("VANILLA", "SINGLETON", "BATCHING"):
        selected = [trial for trial in trials if trial.get("mode") == mode]
        valid = [trial for trial in selected if trial.get("status") == "VALID"]
        result["modes"][mode] = {"validTrials": len(valid), "invalidTrials": len(selected) - len(valid), "metrics": {}}
        for metric in METRICS:
            values = [float(trial[metric]) for trial in valid if trial.get(metric) is not None]
            if values:
                result["modes"][mode]["metrics"][metric] = summarize(values)
    for baseline, new in (("VANILLA", "SINGLETON"), ("VANILLA", "BATCHING"), ("SINGLETON", "BATCHING")):
        key = f"{new}_vs_{baseline}"
        result["comparisons"][key] = {}
        for metric in METRICS:
            left = result["modes"][baseline]["metrics"].get(metric)
            right = result["modes"][new]["metrics"].get(metric)
            if left and right:
                delta = improvement(metric, left["mean"], right["mean"])
                result["comparisons"][key][metric] = {"improvementPercent": delta, "varianceWarning": abs(delta or 0) <= max(left["standardDeviation"], right["standardDeviation"]) / max(abs(left["mean"]), 1e-12) * 100}
    output = Path(args.output)
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(output)

if __name__ == "__main__":
    main()
