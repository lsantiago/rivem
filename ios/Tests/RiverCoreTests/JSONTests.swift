import XCTest
@testable import RiverCore

final class JSONTests: XCTestCase {
    func testPythonNonFiniteTokens() throws {
        let v = try JSON.parse(#"{"a": [NaN, Infinity, -Infinity, -1.5]}"#)
        let a = try XCTUnwrap(v.objectValue?["a"]?.doubleArray)
        XCTAssertTrue(a[0].isNaN)
        XCTAssertEqual(a[1], .infinity)
        XCTAssertEqual(a[2], -.infinity)
        XCTAssertEqual(a[3], -1.5)
    }

    func testKeyOrderAndIntegersArePreserved() throws {
        let v = try JSON.parse(#"{"z": 1, "a": 2.0, "m": {"y": 15, "b": 1e-05}}"#)
        let o = try XCTUnwrap(v.objectValue)
        XCTAssertEqual(o.keys, ["z", "a", "m"])
        XCTAssertEqual(o["z"], .int(1))
        XCTAssertEqual(o["a"], .double(2))
        XCTAssertEqual(o["m"]?.objectValue?.keys, ["y", "b"])
        XCTAssertEqual(o["m"]?.objectValue?["y"], .int(15))
    }

    func testUnicodeEscapesAndSurrogatePairs() throws {
        let v = try JSON.parse(#"["Río", "🌊", "a\"b\\c\n"]"#)
        XCTAssertEqual(v, .array([.string("Río"), .string("🌊"), .string("a\"b\\c\n")]))
    }

    func testBOMIsSkippedAndLatin1IsAccepted() throws {
        XCTAssertEqual(try JSON.parse(Data([0xEF, 0xBB, 0xBF] + Array("[1]".utf8))), .array([.int(1)]))
        // {"é":1} written as Latin-1 bytes (invalid UTF-8)
        let latin1 = Data([0x7B, 0x22, 0xE9, 0x22, 0x3A, 0x31, 0x7D])
        XCTAssertEqual(try JSON.parse(latin1).objectValue?.keys, ["é"])
    }

    func testRejectsMalformedInput() {
        XCTAssertThrowsError(try JSON.parse("{\"a\": }"))
        XCTAssertThrowsError(try JSON.parse("[1, 2"))
        XCTAssertThrowsError(try JSON.parse("[1] x"))
    }

    func testWriterEscapesNonASCIILikePython() throws {
        let text = String(decoding: try JSONWriter(indent: nil).data(.object(JSONObject([("Río 🌊", .int(1))]))), as: UTF8.self)
        XCTAssertEqual(text, #"{"Río 🌊":1}"#)
        XCTAssertTrue(text.unicodeScalars.allSatisfy(\.isASCII))
    }

    func testNonFinitePolicy() throws {
        let v = JSONValue.array([.double(.nan), .double(-.infinity)])
        XCTAssertEqual(String(decoding: try JSONWriter(indent: nil, nonFinite: .tokens).data(v), as: UTF8.self), "[NaN,-Infinity]")
        XCTAssertThrowsError(try JSONWriter(indent: nil, nonFinite: .fail).data(v))
    }

    func testDoublesRoundTripExactly() throws {
        let values = [0.01033821008905579, 5.830041409110808e-05, -0.3484186538996769, 1e16, 123456789.125, 0.1]
        let back = try JSON.parse(JSONWriter(indent: 2).data(.doubles(values))).doubleArray
        XCTAssertEqual(back, values)
    }

    func testIndentedOutputShape() throws {
        let o = JSONObject([("a", .int(1)), ("b", .array([])), ("c", .object(JSONObject()))])
        let text = String(decoding: try JSONWriter(indent: 4).data(.object(o)), as: UTF8.self)
        XCTAssertEqual(text, "{\n    \"a\": 1,\n    \"b\": [],\n    \"c\": {}\n}\n")
    }
}
