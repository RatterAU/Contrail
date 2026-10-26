import XCTest

/// The README's iPhone screenshots: Melbourne in dark mode, an airliner with a verified route, and the airport.
/// Run with the status bar overridden (`xcrun simctl status_bar booted override --time 9:41 ...`).
@MainActor
final class ReadmeShots: XCTestCase {
    func testReadmeShots() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-debugCamera", "0.55,-37.72,144.92"]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow While Using App"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        sleep(15)
        let dir = ProcessInfo.processInfo.environment["TOUR_DIR"] ?? "/tmp/contrail-tour"
        func shot(_ n: String) { try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/\(n).png")) }
        shot("readme-map")

        let planes = app.buttons.matching(identifier: "plane")
        let screen = app.frame.insetBy(dx: 40, dy: 160)
        let airliners = (0..<planes.count).map { planes.element(boundBy: $0) }.filter {
            $0.label.range(of: "^(QFA|VOZ|JST|QLK|RXA)[0-9]+, [0-9,]+ ft", options: .regularExpression) != nil
                && screen.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY))
        }
        let alt = { (e: XCUIElement) in Int(e.label.split(separator: ",").dropFirst().joined().filter(\.isNumber)) ?? 0 }
        // A plane well into its descent: a full route card, "Right now" lines and a profile chart.
        let plane = try XCTUnwrap(airliners.filter { (4_000...20_000).contains(alt($0)) }.first ?? airliners.max { alt($0) < alt($1) })
        plane.tap()
        sleep(10)
        shot("readme-aircraft-medium")
        app.swipeUp()
        sleep(3)
        shot("readme-aircraft")
        app.buttons["Close"].firstMatch.tap()
        sleep(1)

        let search = app.textFields.firstMatch
        search.tap()
        search.typeText("Melbourne")
        sleep(4)
        app.buttons.containing(NSPredicate(format: "label CONTAINS 'MEL'")).firstMatch.tap()
        sleep(10)
        app.swipeUp()
        sleep(3)
        shot("readme-airport")
    }
}
