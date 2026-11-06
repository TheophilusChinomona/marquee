#!/usr/bin/env python3
"""Build the embedded catalogs for Fire IPTV from the provider + scan results.

Outputs (app/src/main/assets):
  live_catalog.json   verified live channels grouped by category
  vod_catalog.json    all movies grouped by category
  series_catalog.json all series grouped by category

Compact array format keeps the assets small:
  {"categories":[[id,name],...],
   "items":[[id,name,category_id,icon,ext],...]}

Usage:
  python3 firetv-player/tools/build_catalog.py
"""

import json
import os
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
RESULTS = os.path.join(ROOT, "..", "channel-results")
ENV_FILE = os.path.join(ROOT, "..", ".env")
UA = "IPTVSmartersPlayer"


def load_env(path):
    values = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key] = value.strip().strip('"').strip("'")
    return values


def fetch(base, user, password, action):
    query = urllib.parse.urlencode({"username": user, "password": password, "action": action})
    request = urllib.request.Request(f"{base}/player_api.php?{query}", headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read().decode("utf-8"))


def write_asset(name, categories, items):
    path = os.path.join(ASSETS, name)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({"categories": categories, "items": items}, handle, ensure_ascii=False, separators=(",", ":"))
    print(f"{name}: {len(categories)} categories, {len(items)} items, {os.path.getsize(path)} bytes")


def write_home_subset(name, categories, items, per_category=20):
    """Small per-category slice used by the home rows; full lists stay for search."""
    grouped = {}
    for item in items:
        grouped.setdefault(item[2], []).append(item)
    subset = []
    for category in categories:
        subset.extend(grouped.get(category[0], [])[:per_category])
    write_asset(name, categories, subset)


def dedupe(items):
    """Drop repeated names inside a category; the scan lists many mirrors."""
    seen = set()
    unique = []
    for item in items:
        key = (item[2], item[1].strip().lower())
        if key in seen:
            continue
        seen.add(key)
        unique.append(item)
    return unique


def build_live():
    playable = set(json.load(open(os.path.join(ASSETS, "playable_live_ids.json"), encoding="utf-8")))
    channels = json.load(open(os.path.join(RESULTS, "channels.json"), encoding="utf-8"))
    catalog_categories = json.load(open(os.path.join(RESULTS, "categories.json"), encoding="utf-8"))
    names = {str(c["category_id"]): c["category_name"] for c in catalog_categories}

    items = []
    used = set()
    for channel in channels:
        stream_id = str(channel.get("stream_id"))
        if stream_id not in playable:
            continue
        category_id = str(channel.get("category_id"))
        used.add(category_id)
        items.append([stream_id, channel.get("name", ""), category_id, channel.get("stream_icon") or ""])

    categories = [[str(c["category_id"]), names.get(str(c["category_id"]), str(c["category_id"]))]
                  for c in catalog_categories if str(c["category_id"]) in used]
    write_asset("live_catalog.json", categories, dedupe(items))


def build_vod(base, user, password):
    categories = [[str(c["category_id"]), c.get("category_name", "")]
                  for c in fetch(base, user, password, "get_vod_categories")]
    streams = fetch(base, user, password, "get_vod_streams")
    items = [[str(s["stream_id"]), s.get("name", ""), str(s.get("category_id")),
              s.get("stream_icon") or "", s.get("container_extension") or "mp4"] for s in streams]
    items = dedupe(items)
    write_asset("vod_catalog.json", categories, items)
    write_home_subset("vod_home.json", categories, items)


def build_series(base, user, password):
    categories = [[str(c["category_id"]), c.get("category_name", "")]
                  for c in fetch(base, user, password, "get_series_categories")]
    shows = fetch(base, user, password, "get_series")
    items = [[str(s["series_id"]), s.get("name", ""), str(s.get("category_id")),
              s.get("cover") or ""] for s in shows]
    items = dedupe(items)
    write_asset("series_catalog.json", categories, items)
    write_home_subset("series_home.json", categories, items)


def main():
    env = load_env(ENV_FILE)
    base = env["IPTV_URL"].rstrip("/")
    user = env["IPTV_USERNAME"]
    password = env["IPTV_PASSWORD"]
    os.makedirs(ASSETS, exist_ok=True)
    build_live()
    build_vod(base, user, password)
    build_series(base, user, password)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
