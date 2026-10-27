import XCTest

/// Drives the app through the beats of the promo video while the screen is recorded externally.
/// Writes "<epoch> <marker>" lines to $TOUR_DIR/markers.txt so the recording can be cut.
@MainActor
final class PromoClips: XCTestCase {
    func testClips() throws {
        let dir = ProcessInfo.processInfo.environment["TOUR_DIR"] ?? "/tmp/contrail-tour"
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let log = "\(dir)/markers.txt"; try? "".write(toFile: log, atomically: true, encoding: .utf8)
        func mark(_ n: String) {
            let l = "\(Date().timeIntervalSince1970) \(n)\n"
            if let h = FileHandle(forWritingAtPath: log) { h.seekToEndOfFile(); h.write(Data(l.utf8)); try? h.close() }
        }
        let app = XCUIApplication()
        app.launchArguments = ["-debugCamera", "2.4,-37.75,144.9"]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow While Using App"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        sleep(14)
        mark("wide")
        sleep(6)
        mark("pinch1")
        app.pinch(withScale: 1.7, velocity: 0.8)
        sleep(6)
        mark("zoomed")
        let planes = app.buttons.matching(identifier: "plane")
        let screen = app.frame.insetBy(dx: 40, dy: 160)
        print("PROMO planes", planes.count, (0..<min(planes.count, 12)).map { planes.element(boundBy: $0).label })
        let airliners = (0..<planes.count).map { planes.element(boundBy: $0) }.filter {
            $0.label.range(of: "^[A-Z]{3}[0-9]+[A-Z]*, [0-9,]+ ft", options: .regularExpression) != nil
                && screen.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) && $0.frame.midY < app.frame.height * 0.6
        }
        let alt = { (e: XCUIElement) in Int(e.label.split(separator: ",").dropFirst().joined().filter(\.isNumber)) ?? 0 }
        let plane = try XCTUnwrap(airliners.filter { (3_000...40_000).contains(alt($0)) }.first ?? airliners.max { alt($0) < alt($1) } ?? airliners.first ?? planes.firstMatch)
        mark("tap")
        plane.tap()
        sleep(10)
        mark("sheet")
        app.swipeUp()
        sleep(4)
        mark("sheetLarge")
        sleep(3)
        let title = app.staticTexts["Flight profile"]
        var n = 0
        while n < 4, title.exists, title.frame.minY > app.frame.height * 0.45 { app.swipeUp(velocity: .slow); sleep(1); n += 1 }
        sleep(2)
        mark("profile")
        if title.exists {
            let y = title.frame.maxY + 95
            let o = app.coordinate(withNormalizedOffset: .zero)
            let p0 = o.withOffset(CGVector(dx: app.frame.width * 0.22, dy: y))
            let p1 = o.withOffset(CGVector(dx: app.frame.width * 0.82, dy: y))
            let p2 = o.withOffset(CGVector(dx: app.frame.width * 0.4, dy: y))
            mark("scrub")
            p0.press(forDuration: 0.8, thenDragTo: p1, withVelocity: .slow, thenHoldForDuration: 1.2)
            sleep(1)
            p1.press(forDuration: 0.8, thenDragTo: p2, withVelocity: .slow, thenHoldForDuration: 1.2)
        }
        sleep(2)
        mark("scrubbed")
        app.swipeUp(velocity: .slow)
        sleep(3)
        mark("sheetEnd")
        app.swipeDown(velocity: .fast)
        sleep(1)
        app.buttons["Close"].firstMatch.tap()
        sleep(2)
        mark("search")
        let search = app.textFields.firstMatch
        search.tap()
        sleep(1)
        search.typeText("Melbourne")
        sleep(4)
        mark("results")
        app.buttons.containing(NSPredicate(format: "label CONTAINS 'MEL'")).firstMatch.tap()
        sleep(9)
        mark("airport")
        app.swipeUp()
        sleep(4)
        mark("airportLarge")
        sleep(3)
        mark("end")
    }
}
