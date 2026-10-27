<div align="center">

<img src="docs/hero.svg" alt="Contrail — where did that plane come from, where's it going, and what is it? A live flight tracker for Android and iPhone, showing Virgin Australia VA223 from Melbourne to Adelaide, on approach, landing in 6 minutes" width="100%">

<br><br>

**A flight tracker for Android and iPhone with no ads, no paywalls and no nonsense.**

You see a plane. You tap it. You know **where it came from, where it's going, and exactly what it is.**

<br>

<img src="docs/promo/contrail-launch.gif" width="100%" alt="Contrail in 26 seconds: ads, paywalls and sign-ups crossed out. The sky, live: every plane overhead in real time. Tap any plane for its route, altitude and speed. Hold and drag to replay the flight's altitude and speed. Arrivals, departures and weather for any airport. Contrail: no ads, no paywalls, just works.">

<sub>The whole app in 26 seconds · [watch in full quality](docs/promo/contrail-launch.mp4)</sub>

<br><br>

### [Get it for Android](../../releases/latest) &nbsp;·&nbsp; [Build it for iPhone](#on-an-iphone)

<sub>Free, about 20 MB, installs in two minutes. No store, no sign-up.</sub>

<br>

![Android 10+](https://img.shields.io/badge/Android-10%2B-0B1B33?style=flat-square&logo=android&logoColor=white)
![iOS 26+](https://img.shields.io/badge/iOS-26%2B-0B1B33?style=flat-square&logo=apple&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Swift](https://img.shields.io/badge/Swift-SwiftUI-F05138?style=flat-square&logo=swift&logoColor=white)
![MIT](https://img.shields.io/badge/licence-MIT-555?style=flat-square)
![No ads](https://img.shields.io/badge/ads-none-1F7A4D?style=flat-square)
![No accounts](https://img.shields.io/badge/accounts-none-1F7A4D?style=flat-square)
![No tracking](https://img.shields.io/badge/analytics-none-1F7A4D?style=flat-square)

[**Why**](#why) · [**What it does**](#what-it-does) · [**Install it**](#install-it) · [**Under the hood**](#under-the-hood) · [**Privacy**](#privacy)

</div>

---

## Why

Every flight tracker does the same thing. It shows you a plane, then an ad. Tap for the route and it wants a subscription. Look at where the plane has been and it wants a bigger one. Meanwhile the data underneath is free: aircraft broadcast their own position over open radio, and volunteers around the world pick it up and share it. The paywall is wrapped around something nobody paid for.

Contrail is the same job without the garbage. No ads, no paywall, no account, no "Gold" tier, no pop-ups asking you to rate it. Nothing is locked. It just works, it's clean, and it does its job.

Your phone talks straight to free public feeds. There's no server in between to pay for, and nothing collecting anything.

<br>

<div align="center">

<img src="docs/screenshots/map-dark.png" width="270" alt="Dark map of Adelaide with 49 live aircraft drawn as coloured plane icons by altitude, airline tail logos on the airliners, and callsign labels">
&nbsp;
<img src="docs/screenshots/aircraft.png" width="270" alt="Aircraft sheet for Virgin Australia VA223: Melbourne to Adelaide, took off 56 minutes ago, lands in an estimated 6 minutes at 1:17 PM, 96% of the way, with a Right now card saying it's on final approach with a 6 knot crosswind, and a flight profile chart">
&nbsp;
<img src="docs/screenshots/airport.png" width="270" alt="Adelaide Airport sheet: live weather showing VFR, 17 degrees, wind 270 at 6 knots, runway 23 in use, and an arrivals board where VA223 has landed at 1:16 PM and the next Qantas and Jetstar arrivals are estimated">

<sub><b>See it</b> · <b>Tap it</b> · <b>Know where it's landing</b></sub>

<br>

<sub>Real screenshots, real traffic, nothing mocked up. The app estimated VA223 would land at 1:17. It landed at 1:16.</sub>

</div>

## What it does

**Tap a plane, know everything about it.** Where it took off, where it's landing, and when. The airline, the exact aircraft type, its registration, how old it is and where it's registered. A real photo of that very aircraft, with the photographer credited. How high, how fast, climbing or descending, and whether it's on autopilot. The sheet leads with plain English ("On final approach into Adelaide", "6 kt crosswind from the left", "12 km NW of Woodside"). The raw numbers are folded away under *Technical details* for anyone who wants them.

**See everything that's flying, right now.** Every aircraft in range is on the map, coloured by altitude and pointing the way it's going. Airliners carry their airline's tail logo. Between position updates each plane glides along at its real speed and heading instead of jumping, so the map looks alive rather than refreshing.

**Routes it can prove.** Most trackers look up a route by flight number and show it as fact, even when the aircraft is flying something else that day. Contrail checks the plane's position and heading against the route first. If they don't agree, it says so instead of guessing. Airlines reuse callsigns, so the published route is sometimes simply wrong: Qatar's QTR65Q into Adelaide is listed everywhere as Doha to Colombo. Then Contrail reads the real route off where the plane actually flew: the airport its flown path started at, and the one it's on final approach to or has landed at. Landing times are always marked *est.*, because that's what they are.

**Where it's actually been.** Tap a plane and its whole flown path is drawn back to the runway it left from, coloured by altitude. Beside it is a profile chart of altitude and speed for the whole flight, which you can scrub through.

**Where it flew before today.** Every plane's flights from the last two weeks, from where to where and when, each time in that airport's own time zone. It comes from adsb.lol's openly licensed daily history, boiled down by a free daily job into a tiny file per few hundred aircraft, so looking one up costs a few kilobytes.

**Weather the way pilots see it.** Satellite clouds, day and night. Turbulence, icing, thunderstorm, ash and cyclone warnings drawn where they apply, with their heights. Wind as drifting streaks at the surface, at 18,000 ft and at airliner cruising height. All free, all optional, and all off by default.

**Any airport, live.** Weather in plain language, the runway in use (worked out from the planes actually using it, or from the wind when it's quiet), and arrivals and departures boards. There's no free public schedule, so the boards are built live from the aircraft actually inbound and outbound. A flight shows up when it's in the air, with its estimate counting down.

**What's overhead.** *Planes near me* lists everything around you, nearest first, with which way to look and how high: "Look NE, 3° up". Point the phone and the arrows turn with you. *Sky view* puts labels on the planes through your camera.

**Times you don't have to convert.** Every time shows in the airport's own time zone, and says so. A flight leaving Melbourne shows Melbourne time, because "12:44 PM" means nothing if you don't know whose 12:44 it is.

**It looks like it was designed, not assembled.** Clean, quiet and consistent: a floating glass control stack over the map, inset grouped lists, proper light and dark modes. Dark, light, streets and satellite maps, with a rain radar layer when you want it.

**Nothing runs when it's closed.** No notifications, no background tracking, no battery drain. Close the app and it stops.

<br>

<div align="center">

<img src="docs/demo.gif" width="300" alt="Screen recording: tapping a Royal Flying Doctor Service PC-12 climbing out of Adelaide opens its sheet, draws its path on the map, and scrolls through its altitude, autopilot target, crosswind, flight profile and aircraft details">

<sub>Tap a plane: a Royal Flying Doctor Service PC-12, climbing to 23,000 ft on autopilot.</sub>

</div>

<br>

<div align="center">

<table>
<tr>
<td align="center" width="20%"><img src="docs/screenshots/trail.png" width="190" alt="Dark map with an Alliance Airlines E190 selected, its flown path drawn as a line shading from green to yellow to orange as it descended into Adelaide"></td>
<td align="center" width="20%"><img src="docs/screenshots/photo.png" width="190" alt="Aircraft sheet for a parked QantasLink Dash 8 at Adelaide, with a photo of that exact aircraft credited to its photographer, and its type, registration, age and size"></td>
<td align="center" width="20%"><img src="docs/screenshots/nearby.png" width="190" alt="Near you: the closest plane, VOZ223, 13 km away, look NE 3 degrees up, then a list of nearby aircraft with direction, elevation, distance and altitude"></td>
<td align="center" width="20%"><img src="docs/screenshots/layers.png" width="190" alt="Map layers sheet: map style segmented control with Auto, Dark, Light, Streets and Satellite, toggles for rain radar, airports, callsign labels and trails, and the altitude colour key"></td>
<td align="center" width="20%"><img src="docs/screenshots/about.png" width="190" alt="About page: version 0.1, accounts or tracking none, both live feeds working at around 400 ms, and the list of data sources"></td>
</tr>
<tr>
<td align="center"><sub><b>Where it's been</b><br>Coloured by altitude</sub></td>
<td align="center"><sub><b>That exact plane</b><br>Photographer credited</sub></td>
<td align="center"><sub><b>Near you</b><br>Which way to look</sub></td>
<td align="center"><sub><b>Your map</b><br>Five styles, radar</sub></td>
<td align="center"><sub><b>Nothing hidden</b><br>Every source listed</sub></td>
</tr>
</table>

</div>

## Install it

There's no Play Store or App Store listing. It's a free side project. On Android you install it straight from this page in about two minutes. On iPhone you build it yourself in Xcode ([below](#on-an-iphone)).

### What you need

| | |
|:--|:--|
| **An Android phone** | Android 10 or newer with a 64-bit processor. That's almost any phone from 2019 onwards |
| **About 20 MB** | That's the whole app, including every airport in the world |

### Step 1: Download it

On your phone, open the [**latest release**](../../releases/latest) and tap **`app-release.apk`** to download it.

### Step 2: Allow the install

When the download finishes, tap it to open it. Android will say something like *"For your security, your phone is not allowed to install unknown apps from this source"*.

Tap **Settings**, turn on **Allow from this source**, then go back.

<sub>This permission is only for the app you downloaded it with (usually Chrome). You can turn it off again afterwards.</sub>

### Step 3: Install and open it

Tap **Install**, then **Open**. Allow location when it asks, so the map can start where you are and *Planes near me* can work. That's it.

> [!NOTE]
> **Google Play Protect may warn you that it doesn't recognise the app.** That's expected for any app that isn't from the Play Store. Tap **More details → Install anyway**. The full source is right here if you want to check what it does.

> [!TIP]
> **Updating:** download the newer `app-release.apk` from [Releases](../../releases) and install it over the top. Your settings are kept.

<details>
<summary><b>If something goes wrong</b></summary>

<br>

| Problem | Fix |
|:--|:--|
| *"App not installed"* | An older copy signed differently is already on the phone. Uninstall it first, then install again |
| *"There was a problem parsing the package"* | The download didn't finish. Delete it and download again |
| The map is empty | Check you're online. The status chip at the top left goes green with an aircraft count once the feeds answer |
| *Planes near me* says it can't find you | Settings → Apps → Contrail → Permissions → Location → **Allow only while using the app** |
| Few planes in a remote area | Coverage comes from volunteer receivers. Over cities it's dense; over the outback and the ocean it thins out |

</details>

<details>
<summary><b>Building it yourself</b></summary>

<br>

You need [Android Studio](https://developer.android.com/studio), for its bundled JDK and the Android SDK.

```bash
git clone https://github.com/RatterAU/Contrail.git
cd Contrail
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS

./gradlew testDebugUnitTest   # 66 JVM tests
./gradlew installDebug        # build and install on a connected phone or emulator
./gradlew assembleRelease     # arm64 release APK (unsigned without your own keystore.properties)
```

To sign a release, put your own keystore details in `keystore.properties` at the project root:

```properties
storeFile=keystore/your-key.jks
storePassword=…
keyAlias=…
keyPassword=…
```

</details>

### On an iPhone

There's an iPhone version too, in [`ios/`](ios). It isn't a copy of the Android app squeezed onto iOS: it's a native SwiftUI app with Apple Maps, Liquid Glass and sheets that slide up and stay out of the way the way they do in Maps. Same data, same rules about routes.

<div align="center">

<img src="docs/screenshots/ios/map.png" width="250" alt="iPhone map of Melbourne at night with Rex flight ZL3577 selected: its flown path drawn from the runway, the dashed route ahead to Burnie, and the aircraft sheet half open below">
&nbsp;
<img src="docs/screenshots/ios/aircraft.png" width="250" alt="iPhone aircraft sheet for Rex ZL3577, Melbourne to Burnie: took off 5 minutes ago, lands in an estimated 51 minutes, climbing through 7,900 ft out of Melbourne, with a flight profile chart">
&nbsp;
<img src="docs/screenshots/ios/airport.png" width="250" alt="iPhone Melbourne Airport sheet: live weather, runway 16 in use, and an arrivals board with Virgin flights on approach and en route">

</div>

It isn't on the App Store, so you build it yourself on a Mac:

1. Install [Xcode](https://apps.apple.com/app/xcode/id497799835), which is free.
2. Download this repo and open `ios/Contrail.xcodeproj`.
3. Plug in your iPhone, pick it as the destination, set your Apple ID as the signing team, and press Run.

It needs iOS 26. With a free Apple ID, the app has to be re-run from Xcode every 7 days, and you can have at most 3 such apps on the phone at once.

<sub>The project is generated from `ios/project.yml` by [XcodeGen](https://github.com/yonaskolb/XcodeGen). You only need it if you change that file.</sub>

## Under the hood

Kotlin and Jetpack Compose with MapLibre on Android, SwiftUI and MapKit on iPhone, and no backend server. The one thing this repo hosts is the past-flights files: a GitHub Actions job builds them once a day and publishes them on GitHub Pages. Both apps share the same bundled airport data and the same logic, tested on each side. Live positions come in about once a second from two volunteer ADS-B networks that serve the same format. Everything else is looked up only when you tap something.

<img src="docs/architecture.svg" width="100%" alt="How Contrail works: live positions from adsb.lol and adsb.fi go through a scheduler into the live traffic store, then each plane's track predicts its position for the map. Tapping a plane runs lookups to adsbdb, airport-data, OpenSky and logo sets, and a route check against the live track and bundled airport data fills the aircraft sheet. Airport boards read the live traffic and bundled data.">

Three decisions carry most of the weight:

- **Two feeds, never one.** Both networks rate-limit hard. A scheduler takes turns between them, at no more than one request per source every 1.1 s. A source that fails or times out (8 s) sits out for a minute while the other carries the load. You never see the switch; the About page shows each feed's live status.
- **The plane's position is predicted, not just reported.** Positions arrive in bursts, seconds apart. Each plane is moved forward along its own speed and track (dead reckoning, capped at 30 s so a lost plane can't fly off into nowhere). It eases toward each new fix over 900 ms instead of snapping. Anything that calculates, like ETAs and distances, uses the true predicted position, never the smoothed one drawn on screen.
- **Nothing is shown as fact that can't be checked.** Route databases are keyed by flight number and are often stale. Before a route is drawn, the plane has to be inside the corridor between the two airports, between them rather than beyond, and heading the right way. Fail any of those and the app says so.

<details>
<summary><b>Things the data doesn't tell you, that I had to find out</b></summary>

<br>

| What's actually true | What the app does about it |
|:--|:--|
| Both feeds rate-limit to about **one request a second**. During testing adsb.lol started answering **HTTP 429** | Round-robin with a 1.1 s floor per source. A source that refuses is benched for 60 s and the other takes over |
| The same server returns aircraft under **two different keys**: `aircraft` for an area query, `ac` for a lookup by transponder | One parser handles both, with tests for each |
| adsbdb returns **404 for the whole request** when it knows the aircraft but not the callsign | Retry for the aircraft alone, so an unknown flight number doesn't cost you the plane's type, photo and registration |
| Routes by flight number are **often wrong on the day** | Verified against position and heading. Otherwise it says *Route unconfirmed* |
| The flown-path endpoints on the ADS-B sites are **bot-protected (403)** | Flown paths come from OpenSky's anonymous track endpoint instead: keyless, about 400 lookups a day, cached 10 minutes per plane. Gaps in coverage are bridged along the great circle |
| **There's no free public flight schedule** | Arrivals and departures boards are built live from aircraft within ~250 nm, and the board says so |
| The standard time zone data names Melbourne Airport's zone **`Australia/Hobart`** | Times are labelled with the airport's own city, never the zone ID, so you don't get "Hobart time" in Melbourne |
| Aircraft photos carry the **photographer's credit burned into the bottom 4% of the image** | Photos are cropped from the top, so the credit strip is never cut off, and the photographer is named on top as well |
| Dividing distance left by current speed makes a landing **drift 40 minutes early** as the plane slows on approach | Departure estimates use the fastest speed recorded on the flight |
| The original airport data dropped **every airfield without an airline code**, Parafield included | About 12,000 small airfields and 9,000 runways added back from OurAirports. They appear as you zoom in |

</details>

<details>
<summary><b>Why the map stays smooth, and the battery lasts</b></summary>

<br>

- **The location dot doesn't follow the compass.** Compass mode redraws the whole map on every sensor tick, about 45 times a second, nonstop. That one setting was 90% of the app's CPU. Without it, map-screen CPU on the test device went from **177% to 21%**.
- **Redraws are paced by zoom.** Zoomed into a city, planes move 10 times a second. Zoomed out to a continent, a plane moves less than a pixel a second, so the map redraws every 2 s. Planes off screen aren't drawn at all, and layers that haven't changed aren't re-uploaded.
- **Fast-changing things don't redraw everything.** The 30 Hz compass and the one-second clocks are read only in the small piece of screen that shows them. Read high up, they'd rebuild the whole app 30 times a second. *Near you* went from 706 frames to 22 in 15 seconds with the phone held still.
- **Sensor noise is rounded off** before it drives an animation (arrows turn in 2° steps), or the screen never goes idle.
- **All parsing and merging runs off the main thread**, and the map stops redrawing entirely while a full-screen page covers it.

</details>

<details>
<summary><b>Source layout</b></summary>

<br>

```
data/
  feed/      ADS-B sources, the round-robin scheduler, parser, flown paths, tracks
  enrich/    Aircraft details, routes, photos, airline logos
  static/    Bundled airports, runways and airlines (18k airports, loaded at start)
  weather/   METAR, TAF, rain radar
  user/      Settings (DataStore). There is no database
domain/      Pure, tested logic: dead reckoning, route checks and ETAs,
             flight phase, airport boards, sky geometry, search, units
ui/
  map/       MapLibre, layers, the main screen and its sheets
  aircraft/  Aircraft sheet, route card, profile chart, photos
  airport/   Weather, runways, boards
  nearby/    Planes near me
  ar/        Sky view
  theme/     Colours, type, and the shared grouped-list controls
app/src/test/  66 JVM tests
```

</details>

## Privacy

Your location is used to centre the map and to find planes near you, and nothing else. The only thing that leaves your phone is a point and a radius ("which planes are near here?") sent to the live feeds, the same way any map asks for the area you're looking at. The app doesn't keep it, and there's no account for it to be tied to. There's no account, no analytics, no ads, no crash reporting and no server of my own. Past flights are plain files on this repo's GitHub Pages; asking for one sends only the first four characters of that plane's transponder code. The app only talks to the public sources listed below, and the About page lists them too, with live status.

## Data & licence

All sources are free and keyless. None of them needed a sign-up.

| What | From |
|:--|:--|
| Live positions | [adsb.lol](https://adsb.lol) (ODbL) and [adsb.fi](https://adsb.fi), fed by volunteer receivers |
| Aircraft details and routes | [adsbdb](https://www.adsbdb.com), plus multi-stop route legs from [adsb.lol](https://adsb.lol) |
| Flown paths | [OpenSky Network](https://opensky-network.org) |
| Past flights | [adsb.lol](https://github.com/adsblol) daily history (ODbL), processed by this repo's [daily job](.github/workflows/history.yml) and served from GitHub Pages under the same licence |
| Aircraft photos | [airport-data.com](https://www.airport-data.com), credited to each photographer |
| Airline logos | [Jxck-S/airline-logos](https://github.com/Jxck-S/airline-logos), [sexym0nk3y/airline-logos](https://github.com/sexym0nk3y/airline-logos) |
| Airport weather | [aviationweather.gov](https://aviationweather.gov) |
| Rain radar | [RainViewer](https://www.rainviewer.com) |
| Satellite clouds | [NASA GIBS](https://earthdata.nasa.gov/gibs): Himawari, GOES-West and GOES-East |
| Hazard warnings | International SIGMETs from [aviationweather.gov](https://aviationweather.gov) |
| Wind | [Open-Meteo](https://open-meteo.com) |
| Maps | [OpenFreeMap](https://openfreemap.org) and [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors; satellite by [EOX](https://maps.eox.at) |
| Airports, runways, airlines | [OurAirports](https://ourairports.com) (public domain), [OpenFlights](https://openflights.org) (ODbL), bundled in the app |

Not affiliated with or endorsed by any airline, airport or data provider. Photos remain the property of their photographers.

Code is MIT licensed. See [LICENSE](LICENSE).
