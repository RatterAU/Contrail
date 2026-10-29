#!/usr/bin/env python3
"""Past flights for the app, from adsb.lol's openly licensed daily history (ODbL, github.com/adsblol).

Run daily by .github/workflows/history.yml. The day's dump (about 4 GB) is streamed straight from its release into
`legs`, never saved, and every aircraft's trace is split into flights. `shards` then publishes the last DAYS days as
small files on GitHub Pages, one per first four hex digits (site/h/7c4e.txt, a few KB), one flight per line:

    hex  callsign  from  departed  to  arrived

Only what was seen is stated. An airport (ICAO ident from the app's airports.tsv) is given only when the plane was
seen on the ground there or below 2000 ft above it nearby; otherwise it's blank. Times are unix minutes of takeoff and
landing, seen happening or within about a minute (below 1500 ft above the field); otherwise the time is prefixed "~"
and is only when the plane was first or last seen, good for the date and nothing more. Day files (site/d/YYYY-MM-DD.tsv.gz) carry history from run to run, so no
data is ever committed to git.

    curl … | python3 tools/history.py legs 1792972800 out.tsv.gz      # day start (unix s), from the tar on stdin
    python3 tools/history.py shards days/ site/
"""
import datetime
import gzip
import json
import math
import os
import sys
import tarfile
from collections import defaultdict

DAYS = 14
AIRPORTS = os.path.join(os.path.dirname(__file__), "../app/src/main/assets/airports.tsv")
# Barometric vertical rate comes in 64 fpm steps; a plane parked or in level flight jitters by a step or two.
LEVEL_FPM = 300


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
    """(ident, height above it in ft) for the airport a plane is on, or below 2000 ft above nearby; else ('', 0).
    A missing altitude (None) can't confirm anything, so it matches nothing."""
    if alt is None:
        return "", 0
    best, best_d, best_agl = "", 1e9, 0
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for ident, alat, alon, elev, size in grid.get((math.floor(lat) + dy, math.floor(lon) + dx), ()):
                agl = 0 if alt == "ground" else alt - elev
                if agl > 2000:
                    continue
                d = math.hypot(lat - alat, (lon - alon) * math.cos(math.radians(lat))) * 60  # nm
                # Big airports win, out to a 3° approach at that height (2000 ft: 8 nm), so an airliner isn't put at
                # a strip next to the runway; small ones only within 2 nm.
                limit, score = (4 + max(agl, 0) / 500, d) if size in ("L", "M") else (2, d + 4)
                if d <= limit and score < best_d:
                    best, best_d, best_agl = ident, score, agl
    return best, best_agl


def unseen_stop(a, b):
    """Over 30 min unseen between a and b, and a was last seen descending low or b reappears climbing low: it landed
    and took off again outside coverage. Parked on the ground, or high on both sides (oceans), it's the same flight."""
    low = lambda p: isinstance(p[3], int) and p[3] < 10000
    return b[0] - a[0] > 1800 and ((low(a) and (a[7] or 0) <= 0) or (low(b) and (b[7] or 0) > 0))


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


def end(grid, pts, i, j, day_start, arriving):
    """One end of a flight: pts[i] is its first (or last) airborne point, pts[j] the ground point before (or after) it,
    if any. Returns (airport, time): the airport it was on, or just above; the takeoff (or landing) time if that was
    seen, within 2 min on the ground or below 1500 ft above the field, else "~" and when it was first (or last) seen.
    Seen low without the ground, it must be heading that way: not climbing into an arrival, not descending out of a
    departure."""
    a, g = pts[i], pts[j] if 0 <= j < len(pts) else None
    if g:
        ap, agl = airport_at(grid, g[1], g[2], "ground")
        seen = abs(a[0] - g[0]) <= 120 or 0 < airport_at(grid, a[1], a[2], a[3])[1] <= 1500
    elif abs(a[7] or 0) >= LEVEL_FPM and (a[7] > 0) == arriving:
        ap, agl = "", 0
    else:
        ap, agl = airport_at(grid, a[1], a[2], a[3])
        seen = bool(ap) and agl <= 1500
    t = int((day_start + a[0]) // 60)
    return ap, f"{t}" if ap and seen else f"~{t}"


def minutes(t):
    return int(t.lstrip("~"))


def nm(a, b):
    """Great-circle distance between two points ([1] lat, [2] lon), right across the dateline too."""
    la, lb = math.radians(a[1]), math.radians(b[1])
    h = math.sin((lb - la) / 2) ** 2 + math.cos(la) * math.cos(lb) * math.sin(math.radians(b[2] - a[2]) / 2) ** 2
    return 2 * 3440.065 * math.asin(min(1, math.sqrt(h)))


def bearing(a, b):
    la, lb, dl = math.radians(a[1]), math.radians(b[1]), math.radians(b[2] - a[2])
    return math.degrees(math.atan2(math.sin(dl) * math.cos(lb), math.cos(la) * math.sin(lb) - math.sin(la) * math.cos(lb) * math.cos(dl))) % 360


def load_routes(root):
    """Published routes by callsign, {callsign: [ICAO idents in flight order]}, from VRS standing-data's routes (CC0,
    github.com/vradarserver/standing-data, routes/schema-01/X/XXX-all.csv: Callsign,Code,Number,AirlineCode,AirportCodes)."""
    routes = {}
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith(".csv"):
                with open(os.path.join(d, f), encoding="utf-8-sig") as fh:
                    for line in fh:
                        c = line.rstrip("\n").split(",")
                        if len(c) >= 5 and c[0] != "Callsign":
                            routes[c[0]] = c[4].split("-")
    return routes


def scheduled(route, known, p, departing, pos):
    """The unseen end of a flight from its callsign's published route, only when the plane's path agrees: `known` (the
    seen end) is a stop on it, and `p`, where the plane was first (or last) seen, sits in the corridor between that
    stop and the one before (or after), flying away from (or towards) it. Otherwise ""."""
    found = set()
    for i, stop in enumerate(route):
        j = i - 1 if departing else i + 1
        if stop != known or not 0 <= j < len(route) or route[j] not in pos or known not in pos:
            continue
        c, k = (0, *pos[route[j]]), (0, *pos[known])
        if nm(c, p) + nm(p, k) > nm(c, k) * 1.1 + 50:
            continue  # off the corridor between the two
        if p[5] is not None and nm(c, p) > 30:
            want = bearing(c, p) if departing else bearing(p, c)
            if abs((p[5] - want + 180) % 360 - 180) > 60:
                continue  # flying the wrong way for it
        found.add(route[j])
    return found.pop() if len(found) == 1 else ""


def only_airport(grid, p):
    """The airport a plane first seen climbing low can only have left from: every airport (any size) within climbing
    range, 4 nm plus 1 nm per 300 ft above it, below 5000 ft; given only if there's exactly one."""
    if not isinstance(p[3], int) or (p[7] or 0) < LEVEL_FPM:
        return ""
    found = set()
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for ident, alat, alon, elev, size in grid.get((math.floor(p[1]) + dy, math.floor(p[2]) + dx), ()):
                agl = p[3] - elev
                if 0 <= agl <= 5000 and nm(p, (0, alat, alon)) <= 4 + agl / 300:
                    found.add(ident)
    return found.pop() if len(found) == 1 else ""


def legs(trace, day_start, grid, routes=None, pos=None):
    """Flights in one aircraft's day: (callsign, from, departed, to, arrived, scheduled from, scheduled to), times as
    `end` gives them.

    Beyond what was seen, only certainties are added, and their times stay unknown:
    - first seen climbing low with exactly one airport in range: it left from there (`only_airport`);
    - an unseen departure right after a known landing, or an unseen landing right before a seen departure, when the
      gap is too short for an unseen flight in between. Any stop elsewhere costs at least the straight line at 550 kt
      plus 15 min on the ground, so a shorter gap means it left from (or went to) that airport.
    An end still unknown may get the callsign's published route (`scheduled`) in its own column, never as seen."""
    found = []
    bounds = [0] + splits(trace) + [len(trace)]
    for start, stop in zip(bounds, bounds[1:]):
        pts = trace[start:stop]
        air = [k for k, p in enumerate(pts) if p[3] != "ground"]
        if not air:
            continue  # taxiing only
        i0, i1 = air[0], air[-1]
        cs = next((c for c in map(callsign, pts) if c), "")
        dep, t0 = end(grid, pts, i0, i0 - 1, day_start, False)
        arr, t1 = end(grid, pts, i1, i1 + 1 if i1 + 1 < len(pts) else -1, day_start, True)
        dep = dep or only_airport(grid, pts[i0])
        found.append([cs, dep, t0, arr, t1, pts[i0], pts[i1], pts[0][0], pts[-1][0]])
    no_stop = lambda a, b: b[0] - a[0] < nm(a, b) / 550 * 3600 + 900
    seen_dep = [f[1] for f in found]  # chain only from airports found directly, never from another inference
    seen_arr = [f[3] for f in found]
    for k in range(1, len(found)):
        if not found[k][1] and seen_arr[k - 1] and no_stop(found[k - 1][6], found[k][5]):
            found[k][1] = seen_arr[k - 1]
    for k in range(len(found) - 1):
        if not found[k][3] and seen_dep[k + 1] and no_stop(found[k][6], found[k + 1][5]):
            found[k][3] = seen_dep[k + 1]
    out = []
    for f in found:
        route = (routes or {}).get(f[0])
        sdep = scheduled(route, f[3], f[5], True, pos) if route and f[3] and not f[1] else ""
        sarr = scheduled(route, f[1], f[6], False, pos) if route and f[1] and not f[3] else ""
        # No airport at either end: kept only at the day's edges, where it may join the neighbouring day's half.
        if f[1] or f[3] or f[7] <= 300 or f[8] >= 86100:
            out.append(tuple(f[:5]) + (sdep, sarr))
    return out


def part_ref(tag, parts, offset, size):
    """Where a trace's bytes sit in the release's split tar, so the apps can fetch just that plane's day with an HTTP
    range request: "YYYYMMDD.N.aa.offset.size". Empty if it straddles two parts. `parts`: [(suffix, bytes)] in order."""
    for suffix, n in parts:
        if offset < n:
            return f"{tag[1:11].replace('.', '')}.{tag.rsplit('-', 1)[1]}.{suffix}.{offset}.{size}" if offset + size <= n else ""
        offset -= n
    return ""


def load_positions(path=AIRPORTS):
    with open(path, encoding="utf-8") as f:
        return {c[0]: (float(c[5]), float(c[6])) for c in (line.split("\t") for line in f)}


def cmd_legs(day_start, out_path, tag="", parts=""):
    grid, pos = load_airports(), load_positions()
    # VRS standing-data's routes folder, when the workflow has checked it out.
    routes = load_routes(os.environ["ROUTES"]) if os.environ.get("ROUTES") else {}
    parts = [(name.rsplit(".", 1)[1], int(n)) for name, n in (p.rsplit(":", 1) for p in parts.split(",") if p)]
    n = 0
    with tarfile.open(fileobj=sys.stdin.buffer, mode="r|") as tar, gzip.open(out_path, "wt", encoding="utf-8") as out:
        for m in tar:
            # "~" hexes are TIS-B/other non-ICAO addresses, not airframes.
            if not (m.isfile() and "/traces/" in m.name) or "_~" in m.name:
                continue
            j = json.loads(gzip.decompress(tar.extractfile(m).read()))
            ref = part_ref(tag, parts, m.offset_data, m.size) if parts else ""
            for cs, dep, t0, arr, t1, sdep, sarr in legs(j["trace"], day_start, grid, routes, pos):
                out.write(f"{j['icao']}\t{cs}\t{dep}\t{t0}\t{arr}\t{t1}\t{ref}\t{sdep}\t{sarr}\n")
                n += 1
    print(f"{n} flights", file=sys.stderr)


def merge(rows):
    """One aircraft's rows sorted by departure, each (callsign, from, departed, to, arrived, trace refs, scheduled from,
    scheduled to). Joins a flight split at midnight UTC, then drops rows with no airport."""
    out = []
    for r in rows:
        r = tuple(r) + ("",) * (8 - len(r))
        p = out[-1] if out else None
        if p and not p[3] and not r[1] and 0 <= minutes(r[2]) - minutes(p[4]) <= 30 and (not p[0] or not r[0] or p[0] == r[0]):
            # Both days' trace refs so the app can fetch the whole path.
            out[-1] = (p[0] or r[0], p[1], p[2], r[3], r[4], " ".join(x for x in (p[5], r[5]) if x), p[6], r[7])
        else:
            out.append(r)
    return [r for r in out if r[1] or r[3]]


def incomplete_dropped(rows, days):
    """Drops a flight cut at midnight UTC whose other half is in a day we don't have (still flying when the newest day
    ended, or took off before the oldest began): it isn't unknown, just incomplete, and joins up once that day exists.
    `days` holds the days present, as days since 1970."""
    def cut(t, at_end):
        m = minutes(t)
        day, into = divmod(m, 1440)
        return t.startswith("~") and (into >= 1435 if at_end else into <= 5) and (day + 1 if at_end else day - 1) not in days
    return [r for r in rows if not ((not r[3] and cut(r[4], True)) or (not r[1] and cut(r[2], False)))]


def stale_dropped(rows_by_hex):
    """Blanks a scheduled end the window contradicts: the same callsign seen (both ends) flying from or to the known
    end, to or from somewhere else, and never this route. Published routes go stale (RYR3WX listed from Alicante, seen
    from Murcia). Rows are (callsign, from, departed, to, arrived, refs, scheduled from, scheduled to)."""
    seen = defaultdict(set)
    for rows in rows_by_hex.values():
        for r in rows:
            if r[0] and r[1] and r[3]:
                seen[r[0]].add((r[1], r[3]))
    for rows in rows_by_hex.values():
        for i, r in enumerate(rows):
            s = seen.get(r[0])
            if not s:
                continue
            sdep, sarr = r[6], r[7]
            if sdep and (sdep, r[3]) not in s and any(b == r[3] for _, b in s):
                sdep = ""
            if sarr and (r[1], sarr) not in s and any(a == r[1] for a, _ in s):
                sarr = ""
            rows[i] = r[:6] + (sdep, sarr)


def cmd_shards(days_dir, site_dir):
    files = sorted(f for f in os.listdir(days_dir) if f.endswith(".tsv.gz"))[-DAYS:]
    by_hex = defaultdict(list)
    for f in files:
        with gzip.open(os.path.join(days_dir, f), "rt", encoding="utf-8") as fh:
            for line in fh:
                h, *row = line.rstrip("\n").split("\t")
                by_hex[h].append(tuple(row) + ("",) * (8 - len(row)))
    stale_dropped(by_hex)
    days = {(datetime.date.fromisoformat(f[:10]) - datetime.date(1970, 1, 1)).days for f in files}
    shards = defaultdict(list)
    for h, rows in by_hex.items():
        for r in incomplete_dropped(merge(sorted(rows, key=lambda r: minutes(r[2]))), days):
            # hex callsign from departed to arrived traces scheduled-from scheduled-to
            shards[h[:4]].append("\t".join((h, *r)) + "\n")
    os.makedirs(os.path.join(site_dir, "h"), exist_ok=True)
    for k, lines in shards.items():
        with open(os.path.join(site_dir, "h", k + ".txt"), "w", encoding="utf-8") as fh:
            fh.writelines(sorted(lines))
    print(f"{len(files)} days, {len(by_hex)} aircraft, {len(shards)} shards", file=sys.stderr)


if __name__ == "__main__":
    if sys.argv[1] == "legs":
        cmd_legs(float(sys.argv[2]), sys.argv[3], *sys.argv[4:6])
    elif sys.argv[1] == "shards":
        cmd_shards(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "test":
        g, pos = load_airports(), load_positions()
        L = lambda t, routes=None: [x[:5] if routes is None else x for x in legs(t, 0, g, routes, pos)]
        assert airport_at(g, -34.945, 138.531, "ground")[0] == "YPAD"  # Adelaide
        assert airport_at(g, -34.945, 138.531, 9000)[0] == ""           # overflying it
        assert airport_at(g, -34.945, 138.531, None)[0] == ""           # no altitude, no claim
        P = lambda t, lat, lon, alt, flags=0, cs=None, vr=0: [t, lat, lon, alt, 0, 0, flags, vr, {"flight": cs} if cs else None]
        # Sydney to Singapore, takeoff and landing both seen; then back, with the landing seen only at 1800 ft
        # 6 nm out (airport known, time not); then taxiing only.
        t = [P(0, -33.95, 151.18, "ground", cs="QFA1  "), P(60, -33.96, 151.17, 300), P(900, -30.0, 145.0, 35000),
             P(29000, 0.0, 105.0, 35000), P(30000, 1.36, 104.0, 500), P(30060, 1.35, 103.99, "ground"),
             P(34000, 1.36, 103.99, "ground", 2), P(34100, 1.37, 104.0, 400, cs="QFA2"), P(35000, -5.0, 110.0, 35000),
             P(59000, -33.0, 150.0, 20000), P(60000, -33.95, 151.29, 1800), P(70000, -33.95, 151.18, "ground", 2)]
        assert L(t) == [("QFA1", "YSSY", "1", "WSSS", "500"), ("QFA2", "WSSS", "568", "YSSY", "~1000")]
        # First seen at cruise, last seen landing: origin unknown, its time only a date.
        t = [P(0, -20.0, 140.0, 35000, cs="QFA3"), P(5000, -33.96, 151.17, 300), P(5050, -33.95, 151.18, "ground")]
        assert L(t) == [("QFA3", "", "~0", "YSSY", "83")]
        # Landed at Sydney, then first seen 10 min later climbing 40 nm away: it can only have left Sydney (an extra
        # stop needs 15 min on the ground alone). Seen 3 h later instead, it could have been anywhere in between.
        for later, want in ((600, "YSSY"), (10800, "")):
            t = [P(0, -33.0, 150.5, 20000, cs="QFA5"), P(900, -33.96, 151.17, 300), P(960, -33.95, 151.18, "ground"),
                 P(1000, -33.95, 151.18, "ground", 2, cs="QFA6"), P(960 + later, -33.6, 150.5, 9000, vr=2000), P(960 + later + 3000, -27.4, 153.1, 300),
                 P(960 + later + 3060, -27.38, 153.12, "ground")]
            assert L(t)[1][1:2] == (want,) and L(t)[1][2].startswith("~"), L(t)
        # Last seen at cruise 150 nm out, then 20 min later on the ground at Brisbane, departing: it landed there.
        t = [P(0, -33.95, 151.18, "ground", cs="QFA7"), P(60, -33.96, 151.17, 300, vr=2000), P(1200, -30.0, 152.0, 35000),
             P(2400, -27.4, 153.12, "ground", 2, cs="QFA8"), P(2500, -27.38, 153.1, 400, vr=2000), P(3800, -20.0, 150.0, 35000)]
        assert [(a, b) for _, a, _, b, _ in L(t)] == [("YSSY", "YBBN"), ("YBBN", "")]
        # First seen climbing through 3000 ft over Alice Springs, the only airport in range: it left there. Over
        # Launceston, strips within range make it ambiguous.
        assert only_airport(g, P(0, -23.8, 133.9, 3000, vr=1500)) == "YBAS"
        assert only_airport(g, P(0, -41.55, 147.2, 4000, vr=1500)) == ""
        assert only_airport(g, P(0, -23.8, 133.9, 3000, vr=-1500)) == ""  # descending: not a departure
        assert only_airport(g, P(0, -33.9, 151.0, 4000, vr=1500)) == ""  # Sydney basin: several airports in range
        # Down low at Sydney, unseen for an hour, then climbing out of Sydney again: two flights.
        t = [P(0, -33.0, 150.0, 20000), P(100, -33.95, 151.18, 1000, vr=-700), P(4000, -33.95, 151.18, 900, vr=1500), P(4500, -33.0, 150.0, 20000)]
        assert [(a, b) for _, a, _, b, _ in L(t)] == [("", "YSSY"), ("YSSY", "")]
        # Out and back with the turnaround unseen at cruise height: the new callsign splits it at the long gap.
        t = [P(0, -33.95, 151.18, "ground", cs="QFA1"), P(60, -33.95, 151.17, 300, vr=2000), P(9000, -20.0, 140.0, 30000),
             P(9100, -20.1, 140.1, 30000, cs="QFA2"), P(20000, -33.96, 151.17, 300), P(20060, -33.95, 151.18, "ground")]
        assert [(cs, a, b) for cs, a, _, b, _ in L(t)] == [("QFA1", "YSSY", ""), ("QFA2", "", "YSSY")]
        # Still flying when the newest day (day 1) ended: dropped until day 2 exists. Landed unseen mid-day: kept.
        assert incomplete_dropped([("A", "YSSY", "100", "", "~2879"), ("B", "YSSY", "1500", "", "~2000")], {1}) == [("B", "YSSY", "1500", "", "~2000")]
        assert len(incomplete_dropped([("A", "YSSY", "100", "", "~2879")], {1, 2})) == 1
        # A flight split at midnight UTC joins up.
        assert merge([("QFA1", "YSSY", "100", "", "~1439"), ("QFA1", "", "~1440", "EGLL", "2000")])[0][:5] == ("QFA1", "YSSY", "100", "EGLL", "2000")
        assert merge([("Q", "YSSY", "100", "", "~1439", "a"), ("Q", "", "~1440", "EGLL", "2000", "b")])[0][5] == "a b"
        # Parked at Avalon (altitude 0, not "ground"), the vertical rate jittering a step below zero: still its departure.
        t = [P(0, -38.04, 144.47, 0, cs="POL32", vr=-64), P(60, -38.03, 144.48, 500, vr=800), P(900, -37.0, 145.0, 3000)]
        assert L(t)[0][1] == "YMAV", L(t)
        # First seen climbing out of Launceston among strips, landed Melbourne: the published route LST-MEL fits, so
        # it's given, as scheduled. A route whose origin is the wrong way (PER-MEL) or off the corridor isn't.
        t = [P(0, -41.3, 147.1, 7775, cs="VOZ1361", vr=2880), P(1500, -37.67, 144.84, 300), P(1560, -37.67, 144.84, "ground")]
        t[0][5] = 338.0
        assert L(t, {"VOZ1361": ["YMLT", "YMML"]}) == [("VOZ1361", "", "~0", "YMML", "25", "YMLT", "")]
        assert L(t, {"VOZ1361": ["YPPH", "YMML"]})[0][5] == ""
        assert L(t, {"VOZ1361": ["YSSY", "YMML"]})[0][5] == ""
        # Took off Sydney, last seen at cruise near Hawaii heading north-east: QF75 is Sydney-Vancouver, so that.
        t = [P(0, -33.95, 151.18, "ground", cs="QFA75"), P(60, -33.96, 151.17, 300, vr=2000), P(40000, 20.5, -157.5, 35000)]
        t[2][5] = 36.0
        assert L(t, {"QFA75": ["YSSY", "CYVR"]})[0][5:] == ("", "CYVR")
        # Multi-stop: off Sydney towards Singapore, the leg after Sydney.
        assert scheduled(["YSSY", "WSSS", "EGLL"], "YSSY", [0, -20.0, 130.0, 35000, 480, 300.0, 0, 0], False, pos) == "WSSS"
        # A scheduled origin the window contradicts (RYR3WX seen Murcia-Bristol, listed Alicante-Bristol) is dropped.
        rows = {"a": [("RYR3WX", "LEMU", "1", "EGGD", "2", "", "", ""), ("RYR3WX", "", "~3", "EGGD", "4", "", "LEAL", "")],
                "b": [("RYR3WX", "", "~5", "EGGD", "6", "", "LEMU", ""), ("QFA1", "YSSY", "7", "", "~8", "", "", "WSSS")]}
        stale_dropped(rows)
        assert rows["a"][1][6] == "" and rows["b"][0][6] == "LEMU" and rows["b"][1][7] == "WSSS"
        # Trace refs: the part holding the bytes, or none when they straddle two parts.
        parts = [("aa", 1000), ("ab", 1000)]
        assert part_ref("v2026.10.26-planes-readsb-prod-0", parts, 1500, 100) == "20261026.0.ab.500.100"
        assert part_ref("v2026.10.26-planes-readsb-prod-0", parts, 950, 100) == ""
        print("ok")
