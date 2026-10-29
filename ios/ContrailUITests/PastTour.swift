import XCTest

/// Past flights and replay on a live airliner near Adelaide, saving screenshots to $TOUR_DIR (default
/// /tmp/contrail-tour). Needs network.
@MainActor
final class PastTour: XCTestCase {
    let app = XCUIApplication()
    let dir = ProcessInfo.processInfo.environment["TOUR_DIR"] ?? "/tmp/contrail-tour"

    func shot(_ name: String) {
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/\(name).png"))
    }

    func testPastFlightsAndReplay() throws {
        app.launchArguments = ["-debugCamera", "2.5,-34.95,138.53"]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow While Using App"]
        if allow.waitForExistence(timeout: 4) { allow.tap() }
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label ENDSWITH 'aircraft'")).firstMatch.waitForExistence(timeout: 20))
        sleep(3)
        let planes = app.buttons.matching(identifier: "plane")
        let screen = app.frame
        let candidates = (0..<planes.count).map { planes.element(boundBy: $0) }
            .filter { screen.insetBy(dx: 30, dy: 120).contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) && $0.frame.midY < screen.height * 0.5 }
        let plane = candidates.first { $0.label.range(of: "^(QFA|VOZ|JST|QLK|RXA)[0-9]", options: .regularExpression) != nil } ?? candidates.first ?? planes.firstMatch
        plane.tap()
        sleep(5)
        shot("p1-aircraft")
        let row = app.buttons.matching(identifier: "pastFlight").firstMatch
        for _ in 0..<8 where !(row.exists && row.isHittable) { app.swipeUp(velocity: .slow); sleep(1) }
        shot("p2-past-list")
        XCTAssertTrue(row.exists, "past flights listed")
        row.tap()
        sleep(6)
        shot("p3-past-detail")
        app.buttons["Play"].firstMatch.tap()
        app.buttons["1000×"].firstMatch.tap()
        sleep(2)
        shot("p4-replaying")
        app.swipeDown()
        sleep(2)
        shot("p5-replay-map")
    }
}
