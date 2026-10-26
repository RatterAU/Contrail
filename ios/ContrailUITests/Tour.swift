import XCTest

/// Walks through every screen and saves a screenshot of each to $TOUR_DIR (default /tmp/contrail-tour), for
/// checking layout by eye. Needs network: it uses live traffic.
@MainActor
final class Tour: XCTestCase {
    let app = XCUIApplication()
    let dir = ProcessInfo.processInfo.environment["TOUR_DIR"] ?? "/tmp/contrail-tour"

    func shot(_ name: String) {
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/\(name).png"))
    }

    func allowLocation() {
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow While Using App"]
        if allow.waitForExistence(timeout: 4) { allow.tap() }
    }

    func testTour() throws {
        app.launch()
        allowLocation()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label ENDSWITH 'aircraft'")).firstMatch.waitForExistence(timeout: 20), "traffic loads")
        sleep(3)
        shot("01-map")

        // An airborne plane that's on screen, above where the sheet will sit.
        let planes = app.buttons.matching(identifier: "plane")
        XCTAssertGreaterThan(planes.count, 0)
        let screen = app.frame
        let candidates = (0..<planes.count).map { planes.element(boundBy: $0) }
            .filter { !$0.label.hasSuffix("Ground") && screen.insetBy(dx: 30, dy: 120).contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) && $0.frame.midY < screen.height * 0.45 }
        // Prefer an airline flight (callsign like QFA884), which has a route to show.
        let plane = candidates.first { $0.label.range(of: "^[A-Z]{3}[0-9]", options: .regularExpression) != nil } ?? candidates.first ?? planes.firstMatch
        plane.tap()
        sleep(5)
        shot("02-aircraft")
        app.swipeUp()
        sleep(2)
        shot("03-aircraft-large")
        app.swipeUp(velocity: .slow)
        sleep(2)
        shot("04-aircraft-more")
        app.swipeUp()
        sleep(1)
        shot("05-aircraft-end")
        app.buttons["Close"].firstMatch.tap()
        sleep(1)

        let search = app.textFields.firstMatch
        search.tap()
        search.typeText("Adelaide")
        sleep(4)
        shot("06-search")
        app.buttons.containing(NSPredicate(format: "label CONTAINS 'ADL'")).firstMatch.tap()
        sleep(8)
        shot("07-airport")
        app.swipeUp()
        sleep(2)
        shot("08-airport-large")
        app.buttons["Close"].firstMatch.tap()
        sleep(1)

        app.buttons["Map layers"].tap()
        sleep(1)
        shot("09-layers")
        app.buttons["Done"].tap()
        app.buttons["Filters"].tap()
        sleep(1)
        shot("10-filters")
        app.buttons["Done"].tap()

        app.buttons["More"].tap()
        app.buttons["Planes near me"].firstMatch.tap()
        sleep(6)
        shot("11-nearby")
        app.buttons["Close"].firstMatch.tap()

        app.buttons["More"].tap()
        app.buttons["Settings"].firstMatch.tap()
        sleep(1)
        shot("12-settings")
        app.buttons["Done"].tap()
        app.buttons["More"].tap()
        app.buttons["About"].firstMatch.tap()
        sleep(1)
        shot("13-about")
        app.buttons["Done"].tap()
    }
}
