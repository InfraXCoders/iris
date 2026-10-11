import Foundation
import AVFoundation
import RecceKit
import CoreMedia
import SwiftUI

/// Runs the phone/Mac camera for the director's viewfinder and reports what it can see.
///
/// iPhone: the widest back camera (triple/dual-wide virtual camera when available, so zoom moves across the
/// ultra-wide, wide and tele lenses). Its field of view comes from the hardware (`videoFieldOfView`).
/// Mac: any connected camera. The Mac can't report a field of view, so it comes from a setting; when the camera
/// is a Blackmagic camera over USB, the picture *is* the cinema frame and only guides are drawn.
@MainActor
final class CameraController: NSObject, ObservableObject {
    enum Status: Equatable { case idle, starting, running, denied, unavailable(String) }

    @Published private(set) var status: Status = .idle
    /// Horizontal FOV across the long side of the picture at zoom 1 (degrees).
    @Published private(set) var baseLongSideFov: Double = 70
    /// Long side / short side of the picture (e.g. 4:3 -> 1.333).
    @Published private(set) var formatAspect: Double = 4.0 / 3.0
    @Published private(set) var zoom: Double = 1
    @Published private(set) var minZoom: Double = 1
    @Published private(set) var maxZoom: Double = 1
    /// True when the preview is shown with the long side vertical (phone held upright).
    @Published private(set) var isPortrait = false
    @Published private(set) var deviceName = ""
    /// True when the Mac's camera is a Blackmagic cinema camera over USB.
    @Published private(set) var isCinemaCameraFeed = false
    @Published private(set) var fovIsMeasured = false
    /// Where `baseLongSideFov` comes from (shown in the viewfinder's status chip).
    @Published private(set) var fovSource: PhoneOptics.Source = .assumed
    /// Multi-lens cameras: the zoom at which each lens takes over, e.g. [(1, "Ultra-wide"), (2, "Main"), (6, "Tele")].
    @Published private(set) var lensStops: [(zoom: Double, name: String)] = []
    /// Manual exposure (for matching the cinema camera's exposure).
    @Published private(set) var customExposureSupported = false
    @Published private(set) var isoRange: ClosedRange<Double> = 25...3200
    @Published private(set) var durationRange: ClosedRange<Double> = (1.0 / 8000)...(1.0 / 2)
    @Published private(set) var lensAperture: Double = 1.8
    @Published private(set) var biasRange: ClosedRange<Double> = -2...2
    @Published var availableDevices: [AVCaptureDevice] = []

    let session = AVCaptureSession()
    /// The live picture with LUT and exposure tools, and the scopes (frames from `videoOutput`).
    let feed = PictureFeed()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private var pictureActive = false
    private let sessionQueue = DispatchQueue(label: "bmpcc.camera.session")
    private var device: AVCaptureDevice?
    private var input: AVCaptureDeviceInput?
    private var photoDelegate: PhotoDelegate?
    #if os(iOS)
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservation: NSKeyValueObservation?
    private weak var previewLayer: AVCaptureVideoPreviewLayer?
    #endif

    /// Mac only: the assumed horizontal FOV of the Mac's camera (Settings).
    static let macFovKey = "macCameraHorizontalFov"

    // MARK: start / stop

    func start(preferredDeviceID: String? = nil) async {
        guard status != .running && status != .starting else { return }
        status = .starting
        let allowed: Bool
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: allowed = true
        case .notDetermined: allowed = await AVCaptureDevice.requestAccess(for: .video)
        default: allowed = false
        }
        guard allowed else { status = .denied; return }
        refreshDevices()
        guard let dev = chooseDevice(preferredID: preferredDeviceID) else {
            status = .unavailable("No camera found.")
            return
        }
        do {
            try configure(with: dev)
        } catch {
            status = .unavailable(error.localizedDescription)
            return
        }
        let s = session
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            sessionQueue.async {
                if !s.isRunning { s.startRunning() }
                cont.resume()
            }
        }
        status = session.isRunning ? .running : .unavailable("The camera did not start.")
    }

    func stop() {
        let s = session
        sessionQueue.async { if s.isRunning { s.stopRunning() } }
        status = .idle
    }

    func switchTo(deviceID: String) async {
        stop()
        await start(preferredDeviceID: deviceID)
    }

    // MARK: devices

    private func refreshDevices() {
        #if os(iOS)
        availableDevices = []
        #else
        var types: [AVCaptureDevice.DeviceType] = [.builtInWideAngleCamera]
        if #available(macOS 14.0, *) { types += [.external, .continuityCamera, .deskViewCamera] }
        availableDevices = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .unspecified).devices
        #endif
    }

    private func chooseDevice(preferredID: String?) -> AVCaptureDevice? {
        #if os(iOS)
        let order: [AVCaptureDevice.DeviceType] = [.builtInTripleCamera, .builtInDualWideCamera, .builtInUltraWideCamera,
                                                   .builtInDualCamera, .builtInWideAngleCamera]
        for type in order {
            if let d = AVCaptureDevice.default(type, for: .video, position: .back) { return d }
        }
        return AVCaptureDevice.default(for: .video)
        #else
        if let id = preferredID, let d = availableDevices.first(where: { $0.uniqueID == id }) { return d }
        // Prefer a connected Blackmagic camera, then any external camera, then the built-in one.
        return availableDevices.first(where: { $0.localizedName.localizedCaseInsensitiveContains("blackmagic") })
            ?? availableDevices.first(where: { $0.deviceType != .builtInWideAngleCamera })
            ?? AVCaptureDevice.default(for: .video)
        #endif
    }

    private func configure(with dev: AVCaptureDevice) throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if let old = input { session.removeInput(old) }
        #if os(iOS)
        session.sessionPreset = .photo           // 4:3 = the most picture area for framing
        #else
        if session.canSetSessionPreset(.high) { session.sessionPreset = .high }
        #endif
        let newInput = try AVCaptureDeviceInput(device: dev)
        guard session.canAddInput(newInput) else { throw CameraError.cannotUse(dev.localizedName) }
        session.addInput(newInput)
        input = newInput
        if !session.outputs.contains(photoOutput), session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
        #if os(iOS)
        if !session.outputs.contains(videoOutput) {
            videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.deliversPreviewSizedOutputBuffers = true
            if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
        }
        #endif
        device = dev
        deviceName = dev.localizedName
        readGeometry(dev)
        #if os(iOS)
        if let layer = previewLayer { attachRotation(device: dev, layer: layer) }
        #endif
    }

    private func readGeometry(_ dev: AVCaptureDevice) {
        let dims = CMVideoFormatDescriptionGetDimensions(dev.activeFormat.formatDescription)
        let long = Double(max(dims.width, dims.height)), short = Double(min(dims.width, dims.height))
        formatAspect = short > 0 ? long / short : 4.0 / 3.0
        #if os(iOS)
        let format = dev.activeFormat
        var fov = Double(format.videoFieldOfView)
        if dev.isGeometricDistortionCorrectionEnabled {
            let corrected = Double(format.geometricDistortionCorrectedVideoFieldOfView)
            if corrected > 0 { fov = corrected }
        }
        // Auto-calibration: the angle the camera reports for its active format, with a 70° fallback.
        let checked = PhoneOptics.checked(fov > 0 ? fov : nil)
        baseLongSideFov = checked.fov
        fovSource = checked.source
        fovIsMeasured = checked.source == .reported
        minZoom = Double(dev.minAvailableVideoZoomFactor)
        maxZoom = min(Double(dev.maxAvailableVideoZoomFactor), 15)
        zoom = Double(dev.videoZoomFactor)
        isCinemaCameraFeed = false
        // Which lens a multi-lens camera uses at each zoom (it switches by itself).
        let switches = dev.virtualDeviceSwitchOverVideoZoomFactors.map { $0.doubleValue }
        let types = dev.constituentDevices.map { $0.deviceType }
        if !types.isEmpty {
            var stops: [(zoom: Double, name: String)] = []
            for (i, t) in types.enumerated() {
                let z: Double = i == 0 ? 1 : (i - 1 < switches.count ? switches[i - 1] : 1)
                let name: String
                switch t {
                case .builtInUltraWideCamera: name = "Ultra-wide"
                case .builtInTelephotoCamera: name = "Tele"
                default: name = "Main"
                }
                stops.append((zoom: z, name: name))
            }
            lensStops = stops
        } else {
            lensStops = [(zoom: 1, name: dev.deviceType == .builtInUltraWideCamera ? "Ultra-wide" : "Main")]
        }
        customExposureSupported = dev.isExposureModeSupported(.custom)
        isoRange = Double(format.minISO)...max(Double(format.maxISO), Double(format.minISO) + 1)
        let minD = CMTimeGetSeconds(format.minExposureDuration), maxD = CMTimeGetSeconds(format.maxExposureDuration)
        if minD > 0, maxD > minD { durationRange = minD...maxD }
        lensAperture = dev.lensAperture > 0 ? Double(dev.lensAperture) : 1.8
        biasRange = Double(dev.minExposureTargetBias)...max(Double(dev.maxExposureTargetBias), Double(dev.minExposureTargetBias))
        #else
        let saved = UserDefaults.standard.double(forKey: Self.macFovKey)
        baseLongSideFov = saved > 0 ? saved : 70
        fovIsMeasured = false
        fovSource = saved > 0 ? .manual : .assumed
        lensStops = [(zoom: 1, name: "Camera")]
        minZoom = 1; maxZoom = 1; zoom = 1
        isCinemaCameraFeed = dev.localizedName.localizedCaseInsensitiveContains("blackmagic")
        #endif
    }

    /// Mac: re-read the FOV setting after the user changes it.
    func reloadMacFov() {
        if let d = device { readGeometry(d) }
    }

    // MARK: zoom

    func setZoom(_ factor: Double, animated: Bool = true) {
        #if os(iOS)
        guard let dev = device else { return }
        let z = min(max(factor, minZoom), maxZoom)
        do {
            try dev.lockForConfiguration()
            if animated { dev.ramp(toVideoZoomFactor: CGFloat(z), withRate: 8) } else { dev.videoZoomFactor = CGFloat(z) }
            dev.unlockForConfiguration()
            zoom = z
        } catch {
            // Zoom is a convenience; framing stays correct because frame lines use the zoom actually applied.
        }
        #endif
    }

    /// The lens a multi-lens camera is using at the current zoom.
    var currentLensName: String {
        lensStops.last(where: { zoom >= $0.zoom - 0.001 })?.name ?? "Main"
    }

    /// The zoom as the Camera app shows it (main lens = 1×, so the ultra-wide is 0.5×).
    var displayZoom: Double {
        guard let main = lensStops.first(where: { $0.name == "Main" }), main.zoom > 0 else { return zoom }
        return zoom / main.zoom
    }

    // MARK: live picture (LUT, false colour, zebras, peaking, scopes)

    /// Frames go to the picture feed only while a tool is on (saves battery otherwise).
    func setPictureActive(_ on: Bool) {
        guard on != pictureActive else { return }
        pictureActive = on
        videoOutput.setSampleBufferDelegate(on ? feed : nil, queue: on ? feed.queue : nil)
    }

    // MARK: exposure

    /// Phone auto-exposure, made brighter or darker by `bias` stops.
    func setAutoExposure(bias: Double) {
        #if os(iOS)
        guard let dev = device else { return }
        do {
            try dev.lockForConfiguration()
            if dev.isExposureModeSupported(.continuousAutoExposure) { dev.exposureMode = .continuousAutoExposure }
            dev.setExposureTargetBias(Float(min(max(bias, biasRange.lowerBound), biasRange.upperBound)), completionHandler: nil)
            dev.unlockForConfiguration()
        } catch {}
        #endif
    }

    /// Manual ISO and exposure time (matching the cinema camera's exposure).
    func setManualExposure(iso: Double, seconds: Double) {
        #if os(iOS)
        guard let dev = device, customExposureSupported else { return }
        do {
            try dev.lockForConfiguration()
            let t = CMTime(seconds: min(max(seconds, durationRange.lowerBound), durationRange.upperBound), preferredTimescale: 1_000_000)
            dev.setExposureModeCustom(duration: t, iso: Float(min(max(iso, isoRange.lowerBound), isoRange.upperBound)), completionHandler: nil)
            dev.unlockForConfiguration()
        } catch {}
        #endif
    }

    // MARK: rotation (iOS)

    #if os(iOS)
    func attach(previewLayer layer: AVCaptureVideoPreviewLayer) {
        previewLayer = layer
        if let dev = device { attachRotation(device: dev, layer: layer) }
    }

    private func attachRotation(device dev: AVCaptureDevice, layer: AVCaptureVideoPreviewLayer) {
        let coordinator = AVCaptureDevice.RotationCoordinator(device: dev, previewLayer: layer)
        rotationCoordinator = coordinator
        apply(angle: coordinator.videoRotationAngleForHorizonLevelPreview, to: layer)
        rotationObservation = coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { [weak self, weak layer] c, _ in
            let angle = c.videoRotationAngleForHorizonLevelPreview
            Task { @MainActor in
                guard let self, let layer else { return }
                self.apply(angle: angle, to: layer)
            }
        }
    }

    private func apply(angle: CGFloat, to layer: AVCaptureVideoPreviewLayer) {
        if let conn = layer.connection, conn.isVideoRotationAngleSupported(angle) { conn.videoRotationAngle = angle }
        feed.setRotation(angle: angle)
        let a = Int(angle.rounded()) % 360
        isPortrait = (a == 90 || a == 270)
    }
    #endif

    // MARK: photo

    func capturePhoto() async throws -> Data {
        #if os(iOS)
        if let conn = photoOutput.connection(with: .video), let c = rotationCoordinator {
            let angle = c.videoRotationAngleForHorizonLevelCapture
            if conn.isVideoRotationAngleSupported(angle) { conn.videoRotationAngle = angle }
        }
        #endif
        return try await withCheckedThrowingContinuation { cont in
            let delegate = PhotoDelegate { [weak self] result in
                Task { @MainActor in self?.photoDelegate = nil }
                cont.resume(with: result)
            }
            photoDelegate = delegate
            let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            photoOutput.capturePhoto(with: settings, delegate: delegate)
        }
    }

    enum CameraError: LocalizedError {
        case cannotUse(String)
        case noPhoto
        var errorDescription: String? {
            switch self {
            case .cannotUse(let name): return "Can't use the camera “\(name)”."
            case .noPhoto: return "The photo could not be captured."
            }
        }
    }
}

private final class PhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate {
    private let completion: (Result<Data, Error>) -> Void
    private var done = false
    init(completion: @escaping (Result<Data, Error>) -> Void) { self.completion = completion }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard !done else { return }
        done = true
        if let error { completion(.failure(error)); return }
        if let data = photo.fileDataRepresentation() { completion(.success(data)) }
        else { completion(.failure(CameraController.CameraError.noPhoto)) }
    }
}
