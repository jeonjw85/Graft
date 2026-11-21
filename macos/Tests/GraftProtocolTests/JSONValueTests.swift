import XCTest
@testable import GraftProtocol

final class JSONValueTests: XCTestCase {
    func testDecodesEveryPrimitiveKind() throws {
        let json = #"{"n":null,"b":true,"d":1.5,"s":"x","a":[1,2],"o":{"k":"v"}}"#
        let value = try JSONDecoder().decode(JSONValue.self, from: Data(json.utf8))

        XCTAssertEqual(
            value,
            .object([
                "n": .null,
                "b": .bool(true),
                "d": .number(1.5),
                "s": .string("x"),
                "a": .array([.number(1), .number(2)]),
                "o": .object(["k": .string("v")])
            ])
        )
    }

    func testRoundTripsNestedValue() throws {
        let value = JSONValue.object([
            "nested": .array([.null, .bool(false), .string("한글 👋🏽")]),
            "count": .number(3)
        ])
        let data = try JSONEncoder().encode(value)
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: data), value)
    }
}


final class JSONValueNumberTests: XCTestCase {
    func testWholeNumbersInsideTheRangeConvert() {
        XCTAssertEqual(JSONValue.number(0).uint64Value, 0)
        XCTAssertEqual(JSONValue.number(4_294_967_295).uint32Value, UInt32.max)
        XCTAssertNil(JSONValue.number(4_294_967_296).uint32Value)
    }

    func testNegativeFractionalAndHugeNumbersAreRejected() {
        XCTAssertNil(JSONValue.number(-1).uint64Value)
        XCTAssertNil(JSONValue.number(1.5).uint64Value)
        XCTAssertNil(JSONValue.number(1e30).uint64Value)
        XCTAssertNil(JSONValue.number(.infinity).uint64Value)
        XCTAssertNil(JSONValue.number(.nan).uint64Value)
    }

    func testNonNumbersAreRejected() {
        XCTAssertNil(JSONValue.string("12").uint64Value)
        XCTAssertNil(JSONValue.null.uint64Value)
    }
}
