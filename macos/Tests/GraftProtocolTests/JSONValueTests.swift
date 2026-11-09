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
