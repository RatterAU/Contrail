import XCTest

/// An airline flight near Melbourne in dark mode: the route card, times and the map's route line.
@MainActor
final class RouteShots: XCTestCase {
    func testAirlinerWithRoute() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-debugCamera", "2.5,-37.4,144.9"]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow While Using App"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        sleep(12)
        let dir = "/tmp/contrail-tour"
        func shot(_ n: String) { try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/\(n).png")) }
        shot("20-melbourne")
        let planes = app.buttons.matching(identifier: "plane")
        let screen = app.frame.insetBy(dx: 30, dy: 140)
        // Airborne airline flights, highest first: likely cruising with a published route.
        let airliners = (0..<planes.count).map { planes.element(boundBy: $0) }.filter {
            $0.label.range(of: "^(QFA|VOZ|JST|QLK|RXA)[0-9]+, [0-9,]+ ft", options: .regularExpression) != nil
                && screen.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY))
        }
        let alt = { (e: XCUIElement) in Int(e.label.split(separator: ",").dropFirst().joined().filter(\.isNumber)) ?? 0 }
        let plane = try XCTUnwrap(airliners.max { alt($0) < alt($1) })
        plane.tap()
        sleep(8)
        shot("21-route")
        app.swipeUp()
        sleep(2)
        shot("22-route-large")
        app.buttons["Close"].firstMatch.tap()
        app.buttons["Map layers"].tap()
        app.switches.containing(NSPredicate(format: "label CONTAINS 'Rain radar'")).firstMatch.switches.firstMatch.tap()
        app.buttons["Satellite"].tap()
        app.buttons["Done"].tap()
        sleep(10)
        shot("23-satellite-radar")
        app.buttons["Map layers"].tap()
        app.buttons["Muted"].tap()
        app.switches.containing(NSPredicate(format: "label CONTAINS 'Rain radar'")).firstMatch.switches.firstMatch.tap()
        app.buttons["Done"].tap()
    }
}
