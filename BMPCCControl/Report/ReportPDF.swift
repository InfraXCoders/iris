import SwiftUI
import CoreGraphics
import RecceKit

/// Builds an A4 recce report: a summary page (location, sun, notes from the director and location),
/// one page per shot (reference photo with frame lines, settings, markers, notes) and a sorted-notes page.
@MainActor
enum ReportPDF {
    static let pageSize = CGSize(width: 595, height: 842)   // A4 in points

    enum ReportError: LocalizedError {
        case cannotCreate
        var errorDescription: String? { "The PDF report couldn't be created." }
    }

    /// Number of pages `make(for:)` produces.
    static func pageCount(for session: RecceSession) -> Int {
        1 + session.shotCount + (session.notes.isEmpty ? 0 : 1)
    }

    static func make(for session: RecceSession) throws -> URL {
        let url = FileStore.exportsFolder.appendingPathComponent(
            "\(FileStore.safeName(session.projectName))-\(FileStore.safeName(session.locationName))-Recce.pdf")
        try? FileManager.default.removeItem(at: url)

        var box = CGRect(origin: .zero, size: pageSize)
        guard let ctx = CGContext(url as CFURL, mediaBox: &box, [
            kCGPDFContextTitle as String: "\(session.projectName) – Recce",
            kCGPDFContextCreator as String: "BMPCC Control",
        ] as CFDictionary) else { throw ReportError.cannotCreate }

        var pages: [AnyView] = [AnyView(SummaryPage(session: session))]
        for scene in session.sortedScenes {
            for shot in scene.sortedShots {
                pages.append(AnyView(ShotPage(scene: scene, shot: shot, notes: session.sortedNotes.filter { $0.shotId == shot.id })))
            }
        }
        if !session.notes.isEmpty { pages.append(AnyView(NotesPage(session: session))) }

        let total = pages.count
        for (i, page) in pages.enumerated() {
            let view = PageFrame(title: session.projectName, page: i + 1, of: total) { page }
                .environment(\.colorScheme, .light)
            let renderer = ImageRenderer(content: view)
            renderer.proposedSize = ProposedViewSize(pageSize)
            renderer.render { _, draw in
                ctx.beginPDFPage(nil)
                draw(ctx)
                ctx.endPDFPage()
            }
        }
        ctx.closePDF()
        return url
    }
}

// MARK: - Page layout

private struct PageFrame<Content: View>: View {
    let title: String
    let page: Int
    let of: Int
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content
            Spacer(minLength: 0)
            HStack {
                Text("\(title) · Recce report · BMPCC Control")
                Spacer()
                Text("Page \(page) of \(of)")
            }
            .font(.system(size: 8)).foregroundStyle(.gray)
        }
        .padding(36)
        .frame(width: ReportPDF.pageSize.width, height: ReportPDF.pageSize.height, alignment: .topLeading)
        .background(Color.white)
        .foregroundStyle(.black)
        .clipped()
    }
}

private struct Heading: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.system(size: 9, weight: .bold)).foregroundStyle(Theme.accent)
            .padding(.top, 10).padding(.bottom, 3)
    }
}

private struct KV: View {
    let key: String
    let value: String
    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Text(key).foregroundStyle(.gray).frame(width: 110, alignment: .leading)
            Text(value.isEmpty ? "—" : value)
            Spacer(minLength: 0)
        }
        .font(.system(size: 9))
    }
}

// MARK: - Pages

private struct SummaryPage: View {
    let session: RecceSession

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(session.projectName).font(.system(size: 22, weight: .bold))
            Text("Recce · \(session.locationName)").font(.system(size: 13))
            Text(session.date.formatted(date: .complete, time: .shortened)).font(.system(size: 10)).foregroundStyle(.gray)

            Heading(text: "Location")
            KV(key: "Place", value: session.locationName)
            if let lat = session.latitude, let lon = session.longitude {
                KV(key: "Coordinates", value: String(format: "%.5f, %.5f", lat, lon))
                let day = Solar.day(for: session.date, latitude: lat, longitude: lon, timeZone: .current)
                Heading(text: "Sun (\(TimeZone.current.identifier))")
                KV(key: "Sunrise / sunset", value: "\(SunCard.time(day.sunrise)) / \(SunCard.time(day.sunset))")
                KV(key: "Solar noon", value: "\(SunCard.time(day.solarNoon)), \(day.noonElevation.degreesText) high")
                KV(key: "Golden hour", value: "\(SunCard.range(day.goldenMorning))  ·  \(SunCard.range(day.goldenEvening))")
                KV(key: "Blue hour", value: "\(SunCard.range(day.blueMorning))  ·  \(SunCard.range(day.blueEvening))")
            }

            Heading(text: "Director / DoP notes")
            Text(session.directorDopNotes.isEmpty ? "—" : session.directorDopNotes).font(.system(size: 9)).lineLimit(10)
            Heading(text: "Location notes")
            Text(session.generalLocationNotes.isEmpty ? "—" : session.generalLocationNotes).font(.system(size: 9)).lineLimit(10)

            Heading(text: "Scenes")
            ForEach(session.sortedScenes.prefix(20)) { scene in
                KV(key: "Scene \(scene.sceneNumber)",
                   value: "\(scene.heading) · \(scene.lighting.label) · \(scene.shots.count) shot(s)")
            }
            if session.scenes.count > 20 { Text("+ \(session.scenes.count - 20) more scenes").font(.system(size: 8)).foregroundStyle(.gray) }
            if session.scenes.isEmpty { Text("No scenes yet.").font(.system(size: 9)).foregroundStyle(.gray) }
        }
    }
}

private struct ShotPage: View {
    let scene: RecceScene
    let shot: RecceShot
    let notes: [RecceNote]

    var body: some View {
        let refs = shot.references.sorted { $0.date < $1.date }
        VStack(alignment: .leading, spacing: 2) {
            Text("Scene \(scene.sceneNumber) · Shot \(shot.shotNumber)").font(.system(size: 16, weight: .bold))
            Text("\(scene.heading) · \(shot.shotType.longName) · \(shot.movement.label)").font(.system(size: 10)).foregroundStyle(.gray)

            if let ref = refs.first, let img = ImageLoader.load(ref.fileURL) {
                Image(platformImage: img).resizable().scaledToFit()
                    .overlay {
                        GeometryReader { geo in
                            if let x = ref.frameX, let y = ref.frameY, let w = ref.frameWidth, let h = ref.frameHeight {
                                Rectangle().stroke(Theme.frameLine, lineWidth: 1.5)
                                    .frame(width: geo.size.width * w, height: geo.size.height * h)
                                    .position(x: geo.size.width * (x + w / 2), y: geo.size.height * (y + h / 2))
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: 300)
                    .padding(.vertical, 8)
                if refs.count > 1 {
                    Text("+ \(refs.count - 1) more reference photo(s) in the app").font(.system(size: 8)).foregroundStyle(.gray)
                }
            } else {
                RoundedRectangle(cornerRadius: 4).stroke(.gray.opacity(0.4))
                    .frame(height: 80)
                    .overlay(Text("No reference photo").font(.system(size: 9)).foregroundStyle(.gray))
                    .padding(.vertical, 8)
            }

            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Heading(text: "Camera & lens")
                    KV(key: "Camera", value: shot.cameraModel)
                    KV(key: "Lens", value: shot.lensModel)
                    KV(key: "Focal length", value: shot.focalLength)
                    KV(key: "Aperture", value: shot.aperture)
                    KV(key: "Aspect ratio", value: shot.aspectRatio)
                    if let r = shot.reference {
                        KV(key: "Field of view", value: "\(r.deliveredFov.horizontal.degreesText) × \(r.deliveredFov.vertical.degreesText)")
                    }
                    Heading(text: "Exposure")
                    KV(key: "Frame rate", value: shot.fps)
                    KV(key: "Shutter", value: shot.shutter)
                    KV(key: "ISO", value: shot.iso)
                    KV(key: "ND", value: shot.nd)
                    KV(key: "White balance", value: shot.whiteBalance)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Heading(text: "Position")
                    KV(key: "Camera", value: shot.cameraPosition)
                    KV(key: "Subject", value: shot.subjectPosition)
                    KV(key: "Height", value: shot.cameraHeight)
                    KV(key: "Distance", value: shot.estimatedDistance.map { String(format: "%.1f m", $0) } ?? "")
                    KV(key: "Subject moves", value: shot.subjectMovement)
                    if !shot.markers.isEmpty {
                        Heading(text: "Markers")
                        ForEach(shot.markers.prefix(8)) { m in
                            KV(key: m.markerType.label, value: "\(Int(m.x * 100))% across, \(Int(m.y * 100))% down")
                        }
                    }
                }
            }

            Heading(text: "Notes")
            Text(shot.notes.isEmpty ? "—" : shot.notes).font(.system(size: 9)).lineLimit(6)
            ForEach(notes.prefix(6)) { n in
                Text("• \(n.text)").font(.system(size: 9)).lineLimit(3)
            }
        }
    }
}

private struct NotesPage: View {
    let session: RecceSession

    var body: some View {
        let summary = NotesSorter.summarize(session.sortedNotes.map(\.text))
        VStack(alignment: .leading, spacing: 2) {
            Text("Recce notes, auto-sorted").font(.system(size: 16, weight: .bold))
            Text("Sorted by keywords (English, Hindi and Hinglish). Notes can appear in more than one group.")
                .font(.system(size: 9)).foregroundStyle(.gray)
            group("Composition", summary.composition)
            group("Lighting", summary.lighting)
            group("Movement", summary.movement)
            group("All notes", summary.general)
        }
    }

    @ViewBuilder private func group(_ title: String, _ items: [String]) -> some View {
        Heading(text: title)
        ForEach(Array(items.prefix(12).enumerated()), id: \.offset) { _, t in
            Text("• \(t)").font(.system(size: 9)).lineLimit(3)
        }
        if items.count > 12 { Text("+ \(items.count - 12) more").font(.system(size: 8)).foregroundStyle(.gray) }
    }
}
