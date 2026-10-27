#!/usr/bin/env python3
"""Past flights for the app, from adsb.lol's openly licensed daily history (ODbL, github.com/adsblol).

Run daily by .github/workflows/history.yml. The day's dump (about 4 GB) is streamed straight from its release into
`legs`, never saved, and every aircraft's trace is split into flights. `shards` then publishes the last DAYS days as
small files on GitHub Pages, one per first four hex digits (site/h/7c4e.txt, a few KB), one flight per line:

    hex  callsign  from  departed  to  arrived

Airports are ICAO idents from the app's airports.tsv, blank when the plane was first or last seen away from one
(coverage gaps); times are unix minutes. Day files (site/d/YYYY-MM-DD.tsv.gz) carry history from run to run, so no
data is ever committed to git.

    curl … | python3 tools/history.py legs 1792972800 out.tsv.gz      # day start (unix s), from the tar on stdin
    python3 tools/history.py shards days/ site/
"""
import gzip
import json
import math
import os
import sys
import tarfile
from collections import defaultdict

DAYS = 14
AIRPORTS = os.path.join(os.path.dirname(__file__), "../app/src/main/assets/airports.tsv")


def load_airports(path=AIRPORTS):
    """Airports in 1° cells: (ident, lat, lon, elevation ft, size)."""
    grid = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        for line in f:
            c = line.rstrip("\n").split("\t")
            lat, lon = float(c[5]), float(c[6])
            grid[(math.floor(lat), math.floor(lon))].append((c[0], lat, lon, int(c[7] or 0), c[8]))
    return grid


def airport_at(grid, lat, lon, alt):
    """The airport a plane is at or just above (on the ground, or under 3000 ft above the field), else ''.
    Altitude can be missing (None); then distance alone decides."""
    best, best_d = "", 1e9
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for ident, alat, alon, elev, size in grid.get((math.floor(lat) + dy, math.floor(lon) + dx), ()):
                agl = alt - elev if isinstance(alt, int) else 0
                if agl > 3000:
                    continue
                d = math.hypot(lat - alat, (lon - alon) * math.cos(math.radians(lat))) * 60  # nm
                # Big airports win, out to the distance of a 3° approach at that height (3000 ft: 11 nm), so an
                # airliner isn't put at a strip next to the runway.
                limit, score = (5 + max(agl, 0) / 500, d) if size in ("L", "M") else (3, d + 3)
                if d <= limit and score < best_d:
                    best, best_d = ident, score
    return best


def unseen_stop(a, b):
    """Over 30 min unseen between a and b, and a was last seen descending low or b reappears climbing low: it landed
    and took off again outside coverage. Parked on the ground, or high on both sides (oceans), it's the same flight."""
    low = lambda p: isinstance(p[3], int) and p[3] < 10000
    return b[0] - a[0] > 1800 and (low(a) or (low(b) and (b[7] or 0) > 0))


def callsign(p):
    return p[8].get("flight", "").strip() if isinstance(p[8], dict) else ""


def splits(trace):
    """Indices where a new flight starts: readsb's new-leg flag (flags & 2), a stop outside coverage, or a new callsign
    (split at the longest gap since the old one was last seen, if that gap was long enough to land and take off, and
    nothing split there already)."""
    out = set()
    last_cs, last_i = (callsign(trace[0]), 0) if trace else ("", 0)
    for i in range(1, len(trace)):
        if trace[i][6] & 2 or unseen_stop(trace[i - 1], trace[i]):
            out.add(i)
        cs = callsign(trace[i])
        if cs:
            if last_cs and cs != last_cs and not any(last_i < k <= i for k in out):
                gap, j = max((trace[k][0] - trace[k - 1][0], k) for k in range(last_i + 1, i + 1))
                if gap > 1200:
                    out.add(j)
            last_cs, last_i = cs, i
    return sorted(out)


def legs(trace, day_start, grid):
    """Flights in one aircraft's day: (callsign, from, departed min, to, arrived min)."""
    out = []
    bounds = [0] + splits(trace) + [len(trace)]
    for start, end in zip(bounds, bounds[1:]):
        pts = trace[start:end]
        air = [p for p in pts if p[3] != "ground"]
        if not air:
            continue  # taxiing only
        first, last = pts[0], pts[-1]
        cs = next((c for c in map(callsign, pts) if c), "")
        dep, arr = airport_at(grid, first[1], first[2], first[3]), airport_at(grid, last[1], last[2], last[3])
        t0, t1 = first[0], last[0]
        # Unknown ends are kept only at the day's edges, where they may join the neighbouring day's half (see merge).
        if not dep and not arr and t0 > 300 and t1 < 86100:
            continue
        if not (dep or arr) and air[-1][0] - air[0][0] < 300:
            continue
        out.append((cs, dep, int((day_start + t0) // 60), arr, int((day_start + t1) // 60)))
    return out


def cmd_legs(day_start, out_path):
    grid = load_airports()
    n = 0
    with tarfile.open(fileobj=sys.stdin.buffer, mode="r|") as tar, gzip.open(out_path, "wt", encoding="utf-8") as out:
        for m in tar:
            # "~" hexes are TIS-B/other non-ICAO addresses, not airframes.
            if not (m.isfile() and "/traces/" in m.name) or "_~" in m.name:
                continue
            j = json.loads(gzip.decompress(tar.extractfile(m).read()))
            for cs, dep, t0, arr, t1 in legs(j["trace"], day_start, grid):
                out.write(f"{j['icao']}\t{cs}\t{dep}\t{t0}\t{arr}\t{t1}\n")
                n += 1
    print(f"{n} flights", file=sys.stderr)


def merge(rows):
    """One aircraft's rows sorted by departure. Joins a flight split at midnight UTC, then drops rows with no airport."""
    out = []
    for r in rows:
        p = out[-1] if out else None
        if p and not p[3] and not r[1] and 0 <= r[2] - p[4] <= 30 and (not p[0] or not r[0] or p[0] == r[0]):
            out[-1] = (p[0] or r[0], p[1], p[2], r[3], r[4])
        else:
            out.append(r)
    return [r for r in out if r[1] or r[3]]


def cmd_shards(days_dir, site_dir):
    files = sorted(f for f in os.listdir(days_dir) if f.endswith(".tsv.gz"))[-DAYS:]
    by_hex = defaultdict(list)
    for f in files:
        with gzip.open(os.path.join(days_dir, f), "rt", encoding="utf-8") as fh:
            for line in fh:
                h, cs, dep, t0, arr, t1 = line.rstrip("\n").split("\t")
                by_hex[h].append((cs, dep, int(t0), arr, int(t1)))
    shards = defaultdict(list)
    for h, rows in by_hex.items():
        for cs, dep, t0, arr, t1 in merge(sorted(rows, key=lambda r: r[2])):
            shards[h[:4]].append(f"{h}\t{cs}\t{dep}\t{t0}\t{arr}\t{t1}\n")
    os.makedirs(os.path.join(site_dir, "h"), exist_ok=True)
    for k, lines in shards.items():
        with open(os.path.join(site_dir, "h", k + ".txt"), "w", encoding="utf-8") as fh:
            fh.writelines(sorted(lines))
    print(f"{len(files)} days, {len(by_hex)} aircraft, {len(shards)} shards", file=sys.stderr)


if __name__ == "__main__":
    if sys.argv[1] == "legs":
        cmd_legs(float(sys.argv[2]), sys.argv[3])
    elif sys.argv[1] == "shards":
        cmd_shards(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "test":
        g = load_airports()
        assert airport_at(g, -34.945, 138.531, "ground") == "YPAD"   # Adelaide
        assert airport_at(g, -34.945, 138.531, 9000) == ""            # overflying it
        assert airport_at(g, -34.945, 138.531, None) == "YPAD"
        # Sydney to Singapore and back, the second leg flagged as a new one; the third piece taxis only.
        t = [[0, -33.95, 151.18, "ground", 0, 0, 0, 0, {"flight": "QFA1    "}], [600, -33.0, 150.0, 20000, 400, 0, 0, 0, None],
             [30000, 1.35, 103.99, "ground", 0, 0, 0, 0, None], [34000, 1.36, 103.99, "ground", 0, 0, 2, 0, None],
             [34600, 2.0, 104.5, 15000, 400, 0, 0, 0, {"flight": "QFA2"}], [60000, -33.95, 151.18, "ground", 0, 0, 0, 0, None],
             [70000, -33.95, 151.18, "ground", 0, 0, 2, 0, None]]
        assert [(cs, a, b) for cs, a, _, b, _ in legs(t, 0, g)] == [("QFA1", "YSSY", "WSSS"), ("QFA2", "WSSS", "YSSY")]
        # Down low at Sydney, unseen for an hour, then climbing out of Sydney again: two flights.
        t = [[0, -33.0, 150.0, 20000, 400, 0, 0, 0, None], [100, -33.95, 151.18, 2000, 150, 0, 0, 0, None],
             [4000, -33.95, 151.18, 1500, 150, 0, 0, 0, None], [4500, -33.0, 150.0, 20000, 400, 0, 0, 0, None]]
        assert [(a, b) for _, a, _, b, _ in legs(t, 0, g)] == [("", "YSSY"), ("YSSY", "")]
        # Out and back with the turnaround unseen at cruise height: the new callsign splits it at the long gap.
        t = [[0, -33.95, 151.18, "ground", 0, 0, 0, 0, {"flight": "QFA1"}], [600, -33.0, 150.0, 30000, 400, 0, 0, 0, None],
             [9000, -20.0, 140.0, 30000, 400, 0, 0, 0, None], [9100, -20.1, 140.1, 30000, 400, 0, 0, 0, {"flight": "QFA2"}],
             [20000, -33.95, 151.18, "ground", 0, 0, 0, 0, None]]
        assert [(cs, a, b) for cs, a, _, b, _ in legs(t, 0, g)] == [("QFA1", "YSSY", ""), ("QFA2", "", "YSSY")]
        # A flight split at midnight UTC joins up.
        assert merge([("QFA1", "YSSY", 100, "", 1439), ("QFA1", "", 1440, "EGLL", 2000)]) == [("QFA1", "YSSY", 100, "EGLL", 2000)]
        print("ok")
