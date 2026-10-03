import XCTest
@testable import RecceKit

final class NotesSorterTests: XCTestCase {
    func testEveryNoteSortsLikeAndroid() {
        for g in Fixtures.list(Fixtures.android, "notes") {
            let text = g.s("text")
            let s = NotesSorter.sort(text)
            XCTAssertEqual(s.language, g.s("language"), text)
            XCTAssertEqual(s.composition, g.optS("composition"), "composition: \(text)")
            XCTAssertEqual(s.lighting, g.optS("lighting"), "lighting: \(text)")
            XCTAssertEqual(s.focalLength, g.optS("focal"), "focal: \(text)")
            XCTAssertEqual(s.general, g.optS("general"), text)
        }
    }

    func testSummaryMatchesAndroid() {
        let texts = Fixtures.list(Fixtures.android, "notes").map { $0.s("text") }
        let g = Fixtures.android["summary"] as! [String: [String]]
        let s = NotesSorter.summarize(texts)
        XCTAssertEqual(s.composition, g["composition"])
        XCTAssertEqual(s.lighting, g["lighting"])
        XCTAssertEqual(s.movement, g["movement"])
        XCTAssertEqual(s.general, g["general"])
    }

    func testEmptySummaryMatchesAndroid() {
        let g = Fixtures.android["emptySummary"] as! [String: [String]]
        let s = NotesSorter.summarize([])
        XCTAssertEqual(s.composition, g["composition"])
        XCTAssertEqual(s.movement, g["movement"])
        XCTAssertEqual(s.lighting, [NotesSorter.unknown])
    }

    func testLanguages() {
        XCTAssertEqual(NotesSorter.detectLanguage("Frame it wide"), "English")
        XCTAssertEqual(NotesSorter.detectLanguage("yahan camera rakho"), "Hinglish")
        XCTAssertEqual(NotesSorter.detectLanguage("कैमरा यहाँ"), "Hindi")
    }
}
