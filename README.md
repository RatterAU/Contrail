# Contrail flight history data

This repo only builds and publishes the **past-flights data** used by the Planeline flight tracker (iPhone) and Contrail (Android). It contains no app code.

A daily GitHub Actions job (`.github/workflows/history.yml`) reads yesterday's openly licensed ADS-B history from [adsb.lol](https://www.adsb.lol/) (globe_history releases) and splits it into small per-aircraft files with `tools/history.py`. They're served from GitHub Pages at `https://ratterau.github.io/Contrail/` (`h/` past flights per aircraft, `r/` routes seen by callsign). The last 14 days are kept.

## Data and licences

- Flight history: © adsb.lol contributors, [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/). The published files are a derived database under the same licence.
- Published routes for unseen ends: [VRS standing data](https://github.com/vradarserver/standing-data) (CC0).
- Airports: [OurAirports](https://ourairports.com/data/) (public domain).

`tools/history.py` is MIT licensed.
