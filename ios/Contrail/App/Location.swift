import CoreLocation
import Observation

/// The user's position (and, while something shows a compass, the phone's heading). In-use only: nothing runs
/// once the app leaves the screen.
@MainActor @Observable
final class LocationService: NSObject, CLLocationManagerDelegate {
    private(set) var location: CLLocation?
    /// Degrees true, quantised to 2° so the arrows that follow it settle instead of animating forever.
    private(set) var heading: Double?
    private(set) var authorization: CLAuthorizationStatus

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private var headingUsers = 0

    override init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 10
        manager.pausesLocationUpdatesAutomatically = false
        manager.headingFilter = 2
    }

    var denied: Bool { authorization == .denied || authorization == .restricted }

    func start() {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.startUpdatingLocation()
    }

    func stop() { manager.stopUpdatingLocation() }

    /// Reference-counted so two compass views don't stop each other's updates.
    func startHeading() {
        headingUsers += 1
        if headingUsers == 1, CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
    }

    func stopHeading() {
        headingUsers = max(headingUsers - 1, 0)
        if headingUsers == 0 { manager.stopUpdatingHeading() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last else { return }
        Task { @MainActor in self.location = l }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading h: CLHeading) {
        let deg = h.trueHeading >= 0 ? h.trueHeading : h.magneticHeading
        let q = (deg / 2).rounded() * 2
        Task { @MainActor in if self.heading != q { self.heading = q } }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let s = manager.authorizationStatus
        Task { @MainActor in
            self.authorization = s
            if s == .authorizedWhenInUse || s == .authorizedAlways { self.manager.startUpdatingLocation() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
