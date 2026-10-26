"""Append small airports with a real 4-letter ICAO code (e.g. Parafield, YPPF) to the bundled TSVs.

The original prep.py (not in this repo) kept large/medium airports plus small ones with an IATA code,
which dropped busy GA fields. Run from the repo root with OurAirports' airports.csv and runways.csv:

    python3 tools/add_small_airports.py path/to/airports.csv path/to/runways.csv

Idempotent: airports already in airports.tsv are skipped. The time zone is copied from the nearest
already-bundled airport in the same country (the same approximation prep.py appears to use).
"""
import csv
import math
import re
import sys

ASSETS = "app/src/main/assets/"


def main(airports_csv, runways_csv):
    with open(ASSETS + "airports.tsv", encoding="utf-8") as f:
        existing = [line.rstrip("\n").split("\t") for line in f]
    have = {r[0] for r in existing}
    by_country = {}
    for r in existing:
        if r[9]:
            by_country.setdefault(r[4], []).append((float(r[5]), float(r[6]), r[9]))

    def tz_for(country, lat, lon):
        cands = by_country.get(country)
        if not cands:
            return ""
        k = math.cos(math.radians(lat))
        return min(cands, key=lambda c: (c[0] - lat) ** 2 + ((c[1] - lon) * k) ** 2)[2]

    def clean(s):
        return s.replace("\t", " ").replace("\n", " ").strip()

    added = []
    for r in csv.DictReader(open(airports_csv, encoding="utf-8")):
        icao = r["icao_code"] or r["ident"]
        if r["type"] != "small_airport" or not re.fullmatch(r"[A-Z]{4}", icao) or icao in have:
            continue
        lat, lon = float(r["latitude_deg"]), float(r["longitude_deg"])
        added.append([icao, r["iata_code"], clean(r["name"]), clean(r["municipality"]), r["iso_country"],
                      f"{lat:.5f}", f"{lon:.5f}", r["elevation_ft"], "S", tz_for(r["iso_country"], lat, lon)])
        have.add(icao)
    new = {a[0] for a in added}

    runways = []
    for r in csv.DictReader(open(runways_csv, encoding="utf-8")):
        if r["airport_ident"] in new and r["closed"] != "1":
            runways.append([r["airport_ident"], r["le_ident"], r["he_ident"], r["length_ft"], clean(r["surface"]),
                            r["le_heading_degT"], r["he_heading_degT"],
                            r["le_latitude_deg"], r["le_longitude_deg"], r["he_latitude_deg"], r["he_longitude_deg"]])

    with open(ASSETS + "airports.tsv", "a", encoding="utf-8") as f:
        f.writelines("\t".join(a) + "\n" for a in added)
    with open(ASSETS + "runways.tsv", "a", encoding="utf-8") as f:
        f.writelines("\t".join(r) + "\n" for r in runways)
    print(f"added {len(added)} airports, {len(runways)} runways")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
