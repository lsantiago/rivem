import XCTest
@testable import RiverCore

/// Reading and writing the RIVeR desktop 3.8.3 project format (docs/desktop-format.md).
final class ProjectFormatTests: XCTestCase {
    private let fixture = "desktop-projects/oblique-pisco"

    func testOpensDesktopProjectWithWindowsPaths() throws {
        let project = try RiverProject.open(try TestData.projectCopy(fixture))
        let s = project.settings
        XCTAssertEqual(s.footage, .oblique)
        XCTAssertEqual(s.unitSystem, .si)
        XCTAssertEqual(s.video?.fps, 30)
        XCTAssertEqual(s.video?.width, 1920)
        XCTAssertEqual(s.videoRange, VideoRange(start: 0, end: 600, step: 2, factor: 1))
        XCTAssertEqual(try XCTUnwrap(s.frameInterval), 2.0 / 30.0, accuracy: 1e-12)
        XCTAssertEqual(s.stage, .crossSections)
        XCTAssertEqual(s.riverName, "Río Pisco")
        XCTAssertEqual(s.controlPoints?.pixels.count, 4)
        XCTAssertEqual(s.controlPoints?.distances.first, 22.154)
        XCTAssertEqual(project.compatibilityIssues(), [])
    }

    func testCrossSectionsKeepOrderNamesAndNaN() throws {
        let project = try RiverProject.open(try TestData.projectCopy(fixture))
        let xs = try XCTUnwrap(try project.readCrossSections())
        XCTAssertEqual(xs.sections.map(\.name), ["CS_default_1", "Sección aguas abajo"])
        XCTAssertNotNil(xs.summary)
        let first = xs.sections[0]
        XCTAssertEqual(first.numStations, 15)
        XCTAssertEqual(first.json["num_stations"], .int(15))
        XCTAssertEqual(first.alpha, 0.85)
        XCTAssertEqual(first.missingKeys, [])
        XCTAssertTrue(try XCTUnwrap(first.json["depth"]?.doubleArray)[2].isNaN)
        // Both the absolute Windows path and the bare file name resolve to the CSV inside the project.
        XCTAssertNotNil(project.bathymetryURL(for: first))
        XCTAssertNotNil(project.bathymetryURL(for: xs.sections[1]))
    }

    /// Golden check with RIVeR's own `pisco` example: the stored matrix sends the section's pixel end points to the
    /// stored real-world coordinates (rounded to cm in the example).
    func testDesktopMatrixMapsSectionPixelsToWorld() throws {
        let project = try RiverProject.open(try TestData.projectCopy(fixture))
        let m = try XCTUnwrap(try project.readMatrix())
        let section = try XCTUnwrap(try project.readCrossSections()?.sections.first)
        for (px, w) in [(section.pixelLeft, section.worldLeft), (section.pixelRight, section.worldRight)] {
            let p = m.apply(try XCTUnwrap(px))
            let expected = try XCTUnwrap(w)
            XCTAssertEqual(p.x, expected.x, accuracy: 0.005)
            XCTAssertEqual(p.y, expected.y, accuracy: 0.005)
        }
        XCTAssertEqual(try XCTUnwrap(section.worldLeft).distance(to: try XCTUnwrap(section.worldRight)),
                       try XCTUnwrap(section.realWorldLength), accuracy: 1e-9)
    }

    func testRoundTripKeepsUnknownKeysAndValues() throws {
        var project = try RiverProject.open(try TestData.projectCopy(fixture))
        let original = project.settings
        let sections = try XCTUnwrap(try project.readCrossSections())
        try project.save()
        try project.writeCrossSections(sections)

        let reopened = try RiverProject.open(project.folder)
        XCTAssertEqual(reopened.settings.json["future_desktop_key"], original.json["future_desktop_key"])
        XCTAssertEqual(reopened.settings.json["processing"], original.json["processing"])
        XCTAssertEqual(reopened.settings.riverName, "Río Pisco")
        XCTAssertEqual(reopened.settings.xsectionsPath, RiverProject.xsectionsFile)
        XCTAssertEqual(try reopened.readCrossSections(), sections)
        XCTAssertEqual(reopened.compatibilityIssues(), [])
        // Files are pure ASCII, so desktop reads them identically as UTF-8 or Latin-1.
        for name in [RiverProject.settingsFile, RiverProject.xsectionsFile] {
            let bytes = try Data(contentsOf: project.folder.appendingPathComponent(name))
            XCTAssertTrue(bytes.allSatisfy { $0 < 0x80 }, name)
        }
    }

    func testNewMatrixInvalidatesSectionsLikeDesktop() throws {
        var project = try RiverProject.open(try TestData.projectCopy(fixture))
        let m = Matrix3(rows: [[0.01, 0, 1], [0, -0.01, 2], [0, 0, 1]])
        try project.setMatrix(m)
        XCTAssertEqual(try project.readMatrix(), m)
        XCTAssertNil(project.settings.xsectionsPath)
        XCTAssertFalse(FileManager.default.fileExists(atPath: project.folder.appendingPathComponent(RiverProject.xsectionsFile).path))
        let reopened = try RiverProject.open(project.folder)
        XCTAssertEqual(reopened.settings.transformationMatrixPath, RiverProject.matrixFile)
        XCTAssertEqual(reopened.settings.json["transformation"]?.objectValue?["roi"], project.settings.json["transformation"]?.objectValue?["roi"])
    }

    func testCreateProjectLikeDesktop() throws {
        let root = try TestData.tempDirectory()
        let video = VideoInfo(filepath: "rio.mov", name: "rio.mov", duration: 12.5, fps: 29.97, width: 3840, height: 2160)
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = utc
        let when = try XCTUnwrap(cal.date(from: DateComponents(year: 2026, month: 11, day: 17, hour: 9, minute: 5)))

        let p = try RiverProject.create(in: root, name: "rio", footage: .uav, video: video, date: when, timeZone: utc)
        XCTAssertEqual(p.folder.lastPathComponent, "20261117T0905")
        XCTAssertEqual(p.folder.deletingLastPathComponent().lastPathComponent, "rio")
        let reopened = try RiverProject.open(p.folder)
        XCTAssertEqual(reopened.settings.creationDate, "20261117T0905")
        XCTAssertEqual(reopened.settings.video?.json["fps"], .int(30)) // desktop rounds fps
        XCTAssertEqual(reopened.settings.video?.json["time_between_frames"], .double(0.03))
        XCTAssertEqual(reopened.settings.stage, .created)
        XCTAssertEqual(reopened.compatibilityIssues(), [])

        // Same minute again: a new folder, never an overwrite.
        let second = try RiverProject.create(in: root, name: "rio", footage: .uav, video: video, date: when, timeZone: utc)
        XCTAssertEqual(second.folder.lastPathComponent, "20261117T0905-2")
        XCTAssertEqual(RiverWorkspace.projectFolders(in: root).count, 2)
    }

    func testFrameNamingAndListing() throws {
        XCTAssertEqual(RiverProject.frameFileName(88), "0000000088.jpg")
        XCTAssertEqual(VideoRange(start: 1, end: 10, step: 2).frameIndices, [2, 4, 6, 8])
        XCTAssertEqual(VideoRange(start: 0, end: 7, step: 3).frameIndices, [0, 3, 6])

        let project = try RiverProject.open(try TestData.projectCopy(fixture))
        let frames = project.folder.appendingPathComponent("frames")
        for name in ["0000000004.jpg", "0000000000.jpg", "background.jpg", "0000000002.jpg", "notes.txt"] {
            try Data([0xFF]).write(to: frames.appendingPathComponent(name))
        }
        XCTAssertEqual(project.frameFiles.map(\.lastPathComponent), ["0000000000.jpg", "0000000002.jpg", "0000000004.jpg"])
    }

    func testMissingPiecesAreReported() throws {
        let folder = try TestData.projectCopy(fixture)
        try FileManager.default.removeItem(at: folder.appendingPathComponent("frames"))
        try FileManager.default.removeItem(at: folder.appendingPathComponent("xsections.json"))
        let issues = try RiverProject.open(folder).compatibilityIssues().map(\.message)
        XCTAssertTrue(issues.contains("falta la carpeta frames/"), "\(issues)")
        XCTAssertTrue(issues.contains { $0.contains("xsections.json") }, "\(issues)")
        XCTAssertThrowsError(try RiverProject.open(folder.deletingLastPathComponent())) { error in
            XCTAssertEqual(error as? ProjectError, .noSettingsFile)
        }
    }

    func testBasenameAcceptsBothSeparators() {
        XCTAssertEqual(RiverProject.basename(#"C:\Users\x\RIVeR\p\xsections.json"#), "xsections.json")
        XCTAssertEqual(RiverProject.basename("/Users/x/p/pisco_section.csv"), "pisco_section.csv")
        XCTAssertEqual(RiverProject.basename("pisco_section.csv"), "pisco_section.csv")
    }

    func testSafeSectionNames() {
        XCTAssertEqual(CrossSections.safeName("Sección 1"), "Seccion_1")
        XCTAssertEqual(CrossSections.safeName("summary"), "CS_summary")
        XCTAssertEqual(CrossSections.safeName("río/aguas#abajo"), "rioaguasabajo")
    }
}

final class ResultFilesTests: XCTestCase {
    /// RIVeR's `pisco` example: 11×58 grid, 29 frame pairs, NaN where no vector.
    func testReadsAndRewritesDesktopPIVResults() throws {
        let piv = try PIVResults.read(TestData.url("river-examples/pisco/piv_results.json"))
        XCTAssertEqual(piv.rows, 11)
        XCTAssertEqual(piv.columns, 58)
        XCTAssertEqual(piv.pairCount, 29)
        XCTAssertEqual(piv.x.count, 638)
        XCTAssertEqual(piv.gradient.count, 29)
        XCTAssertTrue(piv.uMedian.contains { $0.isNaN })
        XCTAssertEqual(piv.x[0], 48)

        let out = try TestData.tempDirectory().appendingPathComponent("piv_results.json")
        try piv.write(out)
        let back = try PIVResults.read(out)
        XCTAssertEqual(back.rows, piv.rows)
        XCTAssertEqual(back.x, piv.x)
        XCTAssertTrue(zip(back.u.joined(), piv.u.joined()).allSatisfy { $0 == $1 || ($0.isNaN && $1.isNaN) })
    }

    func testReadsDesktopBathymetry() throws {
        let b = try Bathymetry.read(TestData.url("river-examples/pisco/pisco_section.csv"))
        XCTAssertEqual(b.stations.count, 8)
        XCTAssertEqual(b.stations.first, 0)
        XCTAssertEqual(b.elevations.first, 1.765)
        XCTAssertEqual(try XCTUnwrap(b.stations.last), 22.656012, accuracy: 1e-9)
        XCTAssertEqual(b.minElevation, 0)
    }

    func testBathymetryVariants() throws {
        let semicolon = try Bathymetry.parse(csv: "x;y\n10;1,5\n0;2,0\n")
        XCTAssertEqual(semicolon.stations, [0, 10]) // reversed to increasing stations
        XCTAssertEqual(semicolon.elevations, [2.0, 1.5])
        let feet = try Bathymetry.parse(csv: "0,10\n10,0\n", feet: true)
        XCTAssertEqual(feet.stations[1], 3.048, accuracy: 1e-12)
        XCTAssertTrue(feet.csv.hasPrefix("station,stage\n"))
        XCTAssertThrowsError(try Bathymetry.parse(csv: "station,stage\n0,abc\n1,2\n"))
    }

    /// RIVeR's UAV example keeps the similarity form desktop writes: [[a, b, tx], [b, -a, ty], [0, 0, 1]].
    func testReadsDesktopUAVMatrix() throws {
        let rows = try XCTUnwrap(JSON.read(TestData.url("river-examples/yanuncay/transformation.json")).arrayValue?.compactMap(\.doubleArray))
        let m = Matrix3(rows: rows)
        XCTAssertEqual(m[0, 0], -m[1, 1], accuracy: 1e-15)
        XCTAssertEqual(m[0, 1], m[1, 0], accuracy: 1e-15)
        XCTAssertEqual(m.rows[2], [0, 0, 1])
    }
}
