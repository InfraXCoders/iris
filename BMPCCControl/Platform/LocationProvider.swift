import Foundation
import CoreLocation

/// One-shot location for a recce session (sun times, report).
@MainActor
final class LocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var location: CLLocation?
    @Published var error: String?
    @Published var isLocating = false
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func request() {
        error = nil
        isLocating = true
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()      // the delegate asks for the location once allowed
        case .denied, .restricted:
            isLocating = false
            error = "Location access is off. Turn it on in Settings › Privacy › Location Services."
        default:
            manager.requestLocation()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let last = locations.last
        Task { @MainActor in
            self.location = last
            self.isLocating = false
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let message = error.localizedDescription
        Task { @MainActor in
            self.error = message
            self.isLocating = false
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            guard self.isLocating else { return }
            switch status {
            case .denied, .restricted:
                self.isLocating = false
                self.error = "Location access is off. Turn it on in Settings › Privacy › Location Services."
            case .notDetermined:
                break
            default:
                self.manager.requestLocation()
            }
        }
    }
}
