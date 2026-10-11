import Foundation
import AVFoundation
import CoreImage
import CoreImage.CIFilterBuiltins
import Metal
import QuartzCore
import SwiftUI
import RecceKit
#if os(iOS)
import UIKit
#endif

/// What the live picture shows (set by the viewfinder): the shot's LUT, false colour, zebras, focus peaking and
/// which scope to measure.
struct PictureSettings: Equatable {
    var lut: Lut3D?
    /// Changes whenever the LUT or its input changes (to rebuild the colour cube).
    var lutKey = ""
    var input: LutInput = .rec709
    /// Left of this fraction of the camera picture's width stays without the LUT (before / after). 0 = LUT everywhere.
    var split: Double = 0
    var falseColour = false
    var zebraLevel: Int?
    var peaking: PeakingLevel?
    var peakingColour: PeakingColour = .red
    var scope: ScopeKind = ScopeKind.none
    /// The part of the camera picture the scopes measure (normalised, top-left origin).
    var crop = CGRect(x: 0, y: 0, width: 1, height: 1)

    var drawsPicture: Bool { lut != nil || falseColour || zebraLevel != nil || peaking != nil }
    var active: Bool { drawsPicture || scope != ScopeKind.none }

    static func == (a: PictureSettings, b: PictureSettings) -> Bool {
        a.lut === b.lut && a.lutKey == b.lutKey && a.input == b.input && a.split == b.split && a.falseColour == b.falseColour
            && a.zebraLevel == b.zebraLevel && a.peaking == b.peaking && a.peakingColour == b.peakingColour
            && a.scope == b.scope && a.crop == b.crop
    }
}

/// One scope reading: the waveform / parade picture, and the histogram (also gives the clipped and crushed shares).
struct ScopeResult {
    var kind: ScopeKind
    var image: CGImage?
    var histogram: Scopes.Histogram
}

@MainActor
final class ScopeModel: ObservableObject {
    @Published var result: ScopeResult?
}

/// Takes the camera's frames and draws the picture with the tools on the GPU (Core Image built-in filters, full
/// preview resolution), filling the screen exactly like the camera preview. The scopes measure a small copy of the
/// picture inside the frame (through the LUT), with the same maths as the Android app (RecceKit).
///
/// Values are the camera's display (Rec.709) code values: colour management is off, so a 0.40 threshold means 40 %.
final class PictureFeed: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let device: MTLDevice?
    let queue = DispatchQueue(label: "bmpcc.picture", qos: .userInitiated)
    private let commandQueue: MTLCommandQueue?
    private let context: CIContext

    private let lock = NSLock()
    private var settings = PictureSettings()
    private var orientation: CGImagePropertyOrientation = .up
    private var layer: CAMetalLayer?
    private weak var scopes: ScopeModel?

    // Only used on `queue`.
    private var cube: (key: String, size: Int, data: Data)?
    private var lastScope: CFTimeInterval = 0

    override init() {
        device = MTLCreateSystemDefaultDevice()
        commandQueue = device?.makeCommandQueue()
        let options: [CIContextOption: Any] = [
            .workingColorSpace: NSNull(),
            .outputColorSpace: NSNull(),
            .workingFormat: NSNumber(value: CIFormat.RGBAh.rawValue),
            .cacheIntermediates: false,
        ]
        if let q = commandQueue { context = CIContext(mtlCommandQueue: q, options: options) } else { context = CIContext(options: options) }
        super.init()
    }

    // MARK: settings (from the main thread)

    func update(_ s: PictureSettings) { lock.lock(); settings = s; lock.unlock() }

    /// The preview's rotation (the same turn the camera preview applies to the picture).
    func setRotation(angle: CGFloat) {
        let o: CGImagePropertyOrientation
        switch ((Int(angle.rounded()) % 360) + 360) % 360 {
        case 90: o = .right
        case 180: o = .down
        case 270: o = .left
        default: o = .up
        }
        lock.lock(); orientation = o; lock.unlock()
    }

    func attach(_ layer: CAMetalLayer?) { lock.lock(); self.layer = layer; lock.unlock() }
    func attach(scopes model: ScopeModel?) { lock.lock(); scopes = model; lock.unlock() }

    // MARK: frames

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lock.lock()
        let s = settings, o = orientation, target = layer, model = scopes
        lock.unlock()
        guard s.active else { return }
        var src = CIImage(cvPixelBuffer: pixels).oriented(o)
        src = src.transformed(by: CGAffineTransform(translationX: -src.extent.minX, y: -src.extent.minY))
        let base = graded(src, s)
        if s.drawsPicture, let target { draw(tools(base, original: src, s), to: target) }
        if s.scope != ScopeKind.none, let model { measure(base, s, model: model) }
    }

    // MARK: LUT

    private func graded(_ img: CIImage, _ s: PictureSettings) -> CIImage {
        guard let lut = s.lut, let c = cubeData(lut, key: s.lutKey, input: s.input) else { return img }
        let f = CIFilter.colorCube()
        f.inputImage = img
        f.cubeDimension = Float(c.size)
        f.cubeData = c.data
        guard let out = f.outputImage?.cropped(to: img.extent) else { return img }
        let from = img.extent.width * CGFloat(min(max(s.split, 0), 1))
        if from <= 0 { return out }
        if from >= img.extent.width { return img }
        return out.cropped(to: CGRect(x: from, y: 0, width: img.extent.width - from, height: img.extent.height)).composited(over: img)
    }

    /// The LUT (after the input transform) as a Core Image colour cube, built once per LUT and input.
    private func cubeData(_ lut: Lut3D, key: String, input: LutInput) -> (size: Int, data: Data)? {
        if let c = cube, c.key == key { return (c.size, c.data) }
        let unitDomain = lut.domainMin == [0, 0, 0] && lut.domainMax == [1, 1, 1]
        let floats: [Float]
        let size: Int
        if input == .rec709 && unitDomain && lut.size <= 64 {
            size = lut.size
            floats = lut.cubeRGBA()
        } else {
            size = input == .rec709 ? 64 : 48
            var out = [Float](repeating: 1, count: size * size * size * 4)
            let n = Float(size - 1)
            var o = 0
            for b in 0..<size { for g in 0..<size { for r in 0..<size {
                var v = (Float(r) / n, Float(g) / n, Float(b) / n)
                if input == .bmdFilmGen5 { v = InputTransform.displayToGen5(v.0, v.1, v.2) }
                let x = lut.apply(v.0, v.1, v.2)
                out[o] = min(max(x.0, 0), 1); out[o + 1] = min(max(x.1, 0), 1); out[o + 2] = min(max(x.2, 0), 1)
                o += 4
            } } }
            floats = out
        }
        let data = floats.withUnsafeBufferPointer { Data(buffer: $0) }
        cube = (key, size, data)
        return (size, data)
    }

    // MARK: false colour, zebras, peaking

    private func tools(_ img: CIImage, original: CIImage, _ s: PictureSettings) -> CIImage {
        let extent = img.extent
        var out = img
        let l = (s.falseColour || s.zebraLevel != nil) ? luma(img) : nil
        if s.falseColour, let l {
            // ARRI's bands over a grey picture.
            var fc = l
            for band in FalseColour.bands {
                let mask = difference(above(l, band.low - 0.0001), above(l, band.high))
                fc = blend(solid(band.argb, extent), over: fc, mask: mask)
            }
            out = fc
        }
        if let z = s.zebraLevel, let l {
            let mask = above(l, Zebra.threshold(z) - 0.0001)
            let gen = CIFilter.stripesGenerator()
            gen.color0 = CIColor(red: 1, green: 1, blue: 1, alpha: 1)
            gen.color1 = CIColor(red: 0, green: 0, blue: 0, alpha: 0)
            gen.width = Float(max(4, extent.width / 180))
            gen.sharpness = 1
            let dark = scaled(out, 0.35)
            if let stripes = gen.outputImage?.transformed(by: CGAffineTransform(rotationAngle: .pi / 4)).cropped(to: extent) {
                out = blend(stripes.composited(over: dark), over: out, mask: mask)
            }
        }
        if let pk = s.peaking {
            // Edges of the camera picture (before the LUT): luma difference across 2 pixels, both directions.
            let lo = luma(original).clampedToExtent()
            let t = pk.threshold * 0.85
            let kernels: [[CGFloat]] = [[0, 0, 0, -1, 0, 1, 0, 0, 0], [0, 0, 0, 1, 0, -1, 0, 0, 0],
                                        [0, -1, 0, 0, 0, 0, 0, 1, 0], [0, 1, 0, 0, 0, 0, 0, -1, 0]]
            var edges: CIImage?
            for k in kernels {
                let c = CIFilter.convolutionRGB3X3()
                c.inputImage = lo
                c.weights = CIVector(values: k, count: 9)
                c.bias = 0
                guard let g = c.outputImage?.cropped(to: extent) else { continue }
                let e = above(g, t)
                edges = edges.map { maximum($0, e) } ?? e
            }
            if let edges { out = blend(solid(s.peakingColour.argb, extent), over: out, mask: edges) }
        }
        return out
    }

    /// Rec.709 luma as an opaque grey picture.
    private func luma(_ img: CIImage) -> CIImage {
        let m = CIFilter.colorMatrix()
        m.inputImage = img
        let w = CIVector(x: 0.2126, y: 0.7152, z: 0.0722, w: 0)
        m.rVector = w; m.gVector = w; m.bVector = w
        m.aVector = CIVector(x: 0, y: 0, z: 0, w: 0)
        m.biasVector = CIVector(x: 0, y: 0, z: 0, w: 1)
        return m.outputImage?.cropped(to: img.extent) ?? img
    }

    /// The picture made darker (zebra background).
    private func scaled(_ img: CIImage, _ k: CGFloat) -> CIImage {
        let m = CIFilter.colorMatrix()
        m.inputImage = img
        m.rVector = CIVector(x: k, y: 0, z: 0, w: 0)
        m.gVector = CIVector(x: 0, y: k, z: 0, w: 0)
        m.bVector = CIVector(x: 0, y: 0, z: k, w: 0)
        m.aVector = CIVector(x: 0, y: 0, z: 0, w: 1)
        return m.outputImage?.cropped(to: img.extent) ?? img
    }

    /// White where the value is above `t`, black elsewhere (opaque).
    private func above(_ img: CIImage, _ t: Double) -> CIImage {
        let f = CIFilter.colorThreshold()
        f.inputImage = img
        f.threshold = Float(t)
        let m = CIFilter.colorMatrix()
        m.inputImage = f.outputImage ?? img
        m.aVector = CIVector(x: 0, y: 0, z: 0, w: 0)
        m.biasVector = CIVector(x: 0, y: 0, z: 0, w: 1)
        return m.outputImage?.cropped(to: img.extent) ?? img
    }

    private func difference(_ a: CIImage, _ b: CIImage) -> CIImage {
        let f = CIFilter.differenceBlendMode()
        f.inputImage = a
        f.backgroundImage = b
        return f.outputImage ?? a
    }

    private func maximum(_ a: CIImage, _ b: CIImage) -> CIImage {
        let f = CIFilter.maximumCompositing()
        f.inputImage = a
        f.backgroundImage = b
        return f.outputImage ?? a
    }

    private func blend(_ top: CIImage, over bottom: CIImage, mask: CIImage) -> CIImage {
        let f = CIFilter.blendWithMask()
        f.inputImage = top
        f.backgroundImage = bottom
        f.maskImage = mask
        return f.outputImage?.cropped(to: bottom.extent) ?? bottom
    }

    private func solid(_ argb: UInt32, _ extent: CGRect) -> CIImage {
        CIImage(color: CIColor(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                               blue: CGFloat(argb & 0xFF) / 255, alpha: 1)).cropped(to: extent)
    }

    // MARK: drawing

    /// Fills the layer like the camera preview (aspect fill, centred).
    private func draw(_ img: CIImage, to layer: CAMetalLayer) {
        guard let commandQueue, let drawable = layer.nextDrawable(), let buffer = commandQueue.makeCommandBuffer() else { return }
        let size = CGSize(width: drawable.texture.width, height: drawable.texture.height)
        let e = img.extent
        guard e.width > 0, e.height > 0, size.width > 0, size.height > 0 else { return }
        let scale = max(size.width / e.width, size.height / e.height)
        let placed = img
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            .transformed(by: CGAffineTransform(translationX: (size.width - e.width * scale) / 2, y: (size.height - e.height * scale) / 2))
        context.render(placed, to: drawable.texture, commandBuffer: buffer, bounds: CGRect(origin: .zero, size: size),
                       colorSpace: CGColorSpaceCreateDeviceRGB())
        buffer.present(drawable)
        buffer.commit()
    }

    // MARK: scopes

    private func measure(_ base: CIImage, _ s: PictureSettings, model: ScopeModel) {
        let now = CACurrentMediaTime()
        guard now - lastScope > 0.12 else { return }
        lastScope = now
        let e = base.extent
        // Crop is top-left based; Core Image is bottom-left based.
        let r = CGRect(x: e.width * s.crop.minX, y: e.height * (1 - s.crop.maxY), width: e.width * s.crop.width, height: e.height * s.crop.height)
        guard r.width >= 4, r.height >= 4 else { return }
        let w = 160
        let h = max(8, min(120, Int((Double(w) * Double(r.height / r.width)).rounded())))
        let small = base.cropped(to: r)
            .transformed(by: CGAffineTransform(translationX: -r.minX, y: -r.minY))
            .transformed(by: CGAffineTransform(scaleX: CGFloat(w) / r.width, y: CGFloat(h) / r.height))
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        context.render(small, toBitmap: &bytes, rowBytes: w * 4, bounds: CGRect(x: 0, y: 0, width: w, height: h), format: .RGBA8, colorSpace: nil)
        let frame = bytes.withUnsafeBytes { raw in
            ScopeFrame.sample(raw.baseAddress!, bytesPerRow: w * 4, width: w, height: h, bgra: false, outW: w, outH: h)
        }
        let hist = Scopes.histogram(frame)
        var image: CGImage?
        if s.scope == .waveform || s.scope == .parade {
            let parade = s.scope == .parade
            image = Self.cgImage(Scopes.waveformImage(Scopes.waveform(frame, columns: parade ? 96 : 160, levels: 100, parade: parade)))
        }
        let result = ScopeResult(kind: s.scope, image: image, histogram: hist)
        Task { @MainActor in model.result = result }
    }

    /// 0xAARRGGBB pixels (straight alpha) as an image.
    static func cgImage(_ img: (width: Int, height: Int, pixels: [UInt32])) -> CGImage? {
        guard img.width > 0, img.height > 0 else { return nil }
        let data = img.pixels.withUnsafeBufferPointer { Data(buffer: $0) }
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        return CGImage(width: img.width, height: img.height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: img.width * 4,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.first.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}

#if os(iOS)
/// The processed live picture, drawn by `PictureFeed` straight into a Metal layer (over the camera preview).
struct LivePictureView: UIViewRepresentable {
    let feed: PictureFeed

    func makeCoordinator() -> PictureFeed { feed }

    func makeUIView(context: Context) -> MetalView {
        let v = MetalView()
        v.setup(device: feed.device)
        feed.attach(v.metalLayer)
        return v
    }

    func updateUIView(_ uiView: MetalView, context: Context) {}

    static func dismantleUIView(_ uiView: MetalView, coordinator: PictureFeed) {
        coordinator.attach(nil as CAMetalLayer?)
    }

    final class MetalView: UIView {
        override class var layerClass: AnyClass { CAMetalLayer.self }
        var metalLayer: CAMetalLayer { layer as! CAMetalLayer }

        func setup(device: MTLDevice?) {
            metalLayer.device = device
            metalLayer.pixelFormat = .bgra8Unorm
            metalLayer.framebufferOnly = false
            metalLayer.isOpaque = true
            backgroundColor = .black
            isUserInteractionEnabled = false
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            let s = traitCollection.displayScale > 0 ? traitCollection.displayScale : 2
            metalLayer.contentsScale = s
            metalLayer.drawableSize = CGSize(width: bounds.width * s, height: bounds.height * s)
        }
    }
}
#endif
