import XCTest

/// The README's iPhone screenshots, around Adelaide in dark mode. Needs network. Pick live flights first and pass them
/// as TEST_RUNNER_SHOT_FLIGHT (an airliner with a verified route, ideally climbing or descending) and
/// TEST_RUNNER_SHOT_PHOTO (one with an airport-data.com photo). Run with the status bar overridden
/// (`xcrun simctl status_bar booted override --time 9:41 ...`) and the location set to Adelaide.
@MainActor
final class ReadmeShots: XCTestCase {
    let app = XCUIApplication()
    let env = ProcessInfo.processInfo.environment
    var dir: String { env["TOUR_DIR"] ?? "/tmp/contrail-tour" }

    func shot(_ n: String) {
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/readme-\(n).png"))
    }

    func launch(_ camera: String) {
        app.terminate()
        app.launchArguments = ["-debugCamera", camera]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow While Using App"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        _ = app.staticTexts.containing(NSPredicate(format: "label ENDSWITH 'aircraft'")).firstMatch.waitForExistence(timeout: 20)
    }

    func open(_ query: String, _ label: String) {
        let search = app.textFields.firstMatch
        search.tap()
        search.typeText(query)
        sleep(4)
        // Not the plane on the map, which has the same callsign in its label.
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND identifier != 'plane'", label)).firstMatch.tap()
    }

    func close() {
        app.buttons["Close"].firstMatch.tap()
        sleep(1)
    }

    /// In the open layers sheet: clouds, hazards and wind on or off.
    func weather(_ on: Bool) {
        for name in ["Clouds", "Hazards"] {
            let s = app.switches.containing(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
            if (s.value as? String == "1") != on { s.switches.firstMatch.tap() }
        }
        let wind = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Wind'")).firstMatch
        for _ in 0..<3 where !(wind.exists && wind.isHittable) { app.swipeUp(velocity: .slow); sleep(1) }
        wind.tap()
        sleep(1)
        app.buttons[on ? "FL340" : "Off"].firstMatch.tap()
        sleep(1)
    }

    func testReadmeShots() throws {
        let flight = try XCTUnwrap(env["SHOT_FLIGHT"], "set TEST_RUNNER_SHOT_FLIGHT")
        let photo = env["SHOT_PHOTO"]

        launch("0.6,-34.92,138.58")
        app.buttons["Map layers"].tap()
        sleep(1)
        app.buttons["Muted"].firstMatch.tap()
        weather(false)
        app.buttons["Done"].tap()
        sleep(12)
        shot("map")

        open(flight, flight)
        sleep(12)
        shot("aircraft-medium")
        app.swipeUp()
        sleep(3)
        shot("aircraft")

        // Past flights, then one replayed part-way.
        // Scroll until the card's header sits in the top third, summary and first rows below it.
        let header = app.staticTexts["Most flown"].firstMatch
        for _ in 0..<20 where !(header.exists && header.isHittable && header.frame.minY < app.frame.height * 0.3) {
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
            start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
            sleep(1)
        }
        sleep(2)
        shot("past-flights")
        app.buttons.matching(identifier: "pastFlight").element(boundBy: 2).tap()
        sleep(8)
        app.buttons["60×"].firstMatch.tap()
        app.buttons["Play"].firstMatch.tap()
        sleep(20)
        let pause = app.buttons["Pause"].firstMatch
        if pause.exists { pause.tap() }
        sleep(1)
        shot("replay")
        app.swipeDown()
        sleep(3)
        shot("replay-map")
        app.buttons["Back"].firstMatch.tap()
        sleep(1)
        close()

        if let photo {
            open(photo, photo)
            sleep(12)
            app.swipeUp()
            sleep(2)
            // The photo sits just above "About this aircraft": stop with that header low on screen.
            let about = app.staticTexts["About this aircraft"].firstMatch
            for _ in 0..<25 where !(about.exists && about.isHittable && about.frame.minY < app.frame.height * 0.8) {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
                    .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55)))
                sleep(1)
            }
            sleep(4)
            shot("photo")
            close()
        }

        open("Adelaide", "ADL")
        sleep(10)
        app.swipeUp()
        sleep(3)
        shot("airport")
        close()

        app.buttons["More"].tap()
        app.buttons["Planes near me"].firstMatch.tap()
        sleep(8)
        shot("nearby")
        app.buttons["Close"].firstMatch.tap()
        sleep(1)

        // Weather across Australia: clouds, hazards and FL340 wind.
        app.buttons["Map layers"].tap()
        sleep(1)
        weather(true)
        shot("layers")
        app.buttons["Done"].tap()
        launch("38,-27,134")
        sleep(25)
        shot("weather")
        app.buttons["Map layers"].tap()
        sleep(1)
        weather(false)
        app.buttons["Done"].tap()

        app.buttons["More"].tap()
        app.buttons["Settings"].firstMatch.tap()
        sleep(1)
        shot("settings")
        app.buttons["Done"].tap()
        app.buttons["More"].tap()
        app.buttons["About"].firstMatch.tap()
        sleep(3)
        shot("about")
        app.buttons["Done"].tap()
    }
}
