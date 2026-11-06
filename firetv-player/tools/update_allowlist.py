#!/usr/bin/env python3
"""Regenerate the verified live allowlist assets from the IPTV scan results.

Usage:
    python3 firetv-player/tools/update_allowlist.py [latest-results.jsonl]

The scan output lives in ../channel-results/latest-results.jsonl. Only entries
whose status is "playable" are admitted. Rebuild the APK afterwards so the new
assets are packaged.
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DEFAULT_RESULTS = os.path.join(ROOT, "..", "channel-results", "latest-results.jsonl")


def main() -> int:
    results = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RESULTS
    results = os.path.abspath(results)
    if not os.path.isfile(results):
        print(f"missing scan results: {results}", file=sys.stderr)
        return 1

    stream_ids = set()
    category_ids = set()
    statuses = {}
    with open(results, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            entry = json.loads(line)
            status = entry.get("status")
            statuses[status] = statuses.get(status, 0) + 1
            if status != "playable":
                continue
            if entry.get("stream_id") is not None:
                stream_ids.add(str(entry["stream_id"]))
            if entry.get("category_id") is not None:
                category_ids.add(str(entry["category_id"]))

    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "playable_live_ids.json"), "w", encoding="utf-8") as handle:
        json.dump(sorted(stream_ids, key=lambda value: (len(value), value)), handle)
    with open(
        os.path.join(ASSETS, "playable_live_categories.json"), "w", encoding="utf-8"
    ) as handle:
        json.dump(sorted(category_ids), handle)

    print(f"scan: {results}")
    print(f"statuses: {statuses}")
    print(f"playable stream ids: {len(stream_ids)}")
    print(f"playable categories: {len(category_ids)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
