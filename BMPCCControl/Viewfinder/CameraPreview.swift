import SwiftUI
import AVFoundation

#if os(iOS)
import UIKit

/// Shows the camera picture filling the whole screen (the cinema frame is a mask over it; the viewfinder knows
/// how much of the picture is cropped and places the frame lines exactly).
struct CameraPreview: UIViewRepresentable {
    let controller: CameraController

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.backgroundColor = .black
        view.previewLayer.session = controller.session
        view.previewLayer.videoGravity = .resizeAspectFill
        controller.attach(previewLayer: view.previewLayer)
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}

    final class PreviewUIView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
#else
import AppKit

struct CameraPreview: NSViewRepresentable {
    let controller: CameraController

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        let layer = AVCaptureVideoPreviewLayer(session: controller.session)
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = NSColor.black.cgColor
        view.layer = layer
        view.wantsLayer = true
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}
#endif
