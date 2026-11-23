import XCTest
@testable import GraftMacKit
@testable import GraftProtocol

final class NotificationPresenterTests: XCTestCase {
    private final class FakeCenter: NotificationCenterPort {
        var entries: [(identifier: String, title: String, subtitle: String, body: String, replyable: Bool)] = []
        var removed: [String] = []
        func add(title: String, subtitle: String, body: String) {
            entries.append(("", title, subtitle, body, false))
        }
        func add(identifier: String, title: String, subtitle: String, body: String, replyable: Bool) {
            entries.append((identifier, title, subtitle, body, replyable))
        }
        func remove(identifier: String) {
            removed.append(identifier)
        }
    }

    private func frame(
        app: String = "Messenger",
        title: String = "New message",
        text: String,
        ts: Int = 1,
        key: String = "k",
        replyable: Bool = false
    ) -> Envelope {
        Envelope(type: "notification.posted", payload: .object([
            "pkg": .string("com.example.messenger"),
            "app": .string(app),
            "title": .string(title),
            "text": .string(text),
            "ts": .number(Double(ts)),
            "key": .string(key),
            "priority": .string("normal"),
            "replyable": .bool(replyable)
        ]))
    }

    func testAppNameBecomesTheTitleAndTextTheBody() {
        let center = FakeCenter()
        let presenter = NotificationPresenter(port: center)

        XCTAssertTrue(presenter.present(frame(title: "김철수", text: "내일 회의는 세 시입니다")))

        XCTAssertEqual(center.entries.count, 1)
        XCTAssertEqual(center.entries[0].title, "Messenger")
        XCTAssertEqual(center.entries[0].subtitle, "김철수")
        XCTAssertEqual(center.entries[0].body, "내일 회의는 세 시입니다")
        XCTAssertEqual(presenter.recent.first?.app, "Messenger")
    }

    func testHiddenPreviewsAlsoHideTheSubtitle() {
        let center = FakeCenter()
        let presenter = NotificationPresenter(port: center)
        presenter.showPreviews = false

        XCTAssertTrue(presenter.present(frame(title: "김철수", text: "비밀번호 1234")))

        XCTAssertEqual(center.entries[0].title, "Messenger")
        XCTAssertEqual(center.entries[0].subtitle, "")
        XCTAssertEqual(center.entries[0].body, "New notification")
    }

    func testMalformedTimestampIsIgnored() {
        let center = FakeCenter()
        let presenter = NotificationPresenter(port: center)

        for value in [-1.0, 1.5, 1e30] {
            let frame = Envelope(type: "notification.posted", payload: .object([
                "pkg": .string("com.example"),
                "app": .string("App"),
                "title": .string("Title"),
                "text": .string("Text"),
                "ts": .number(value),
            ]))
            XCTAssertTrue(presenter.present(frame))
        }

        XCTAssertEqual(presenter.recent.map(\.postedAt), [0, 0, 0])
    }

    func testTwoKiBLimitIsApplied() {
        let center = FakeCenter()
        let presenter = NotificationPresenter(port: center)
        let huge = String(repeating: "가", count: 5000)

        presenter.present(frame(text: huge))

        let bytes = center.entries[0].body.utf8.count
        XCTAssertLessThanOrEqual(bytes, NotificationPresenter.maxBodyBytes)
        XCTAssertFalse(center.entries[0].body.contains("\u{FFFD}"))
    }

    func testRecentListIsCappedAtFifty() {
        let presenter = NotificationPresenter(port: FakeCenter())
        for index in 0..<60 {
            presenter.present(frame(text: "m\(index)", ts: index))
        }

        XCTAssertEqual(presenter.recent.count, NotificationPresenter.maxRecent)
        XCTAssertEqual(presenter.recent.first?.body, "m59", "newest first")
    }

    func testOtherMessageTypesAreIgnored() {
        let presenter = NotificationPresenter(port: FakeCenter())
        XCTAssertFalse(presenter.present(Envelope(type: "hello", payload: .object([:]))))
        XCTAssertTrue(presenter.recent.isEmpty)
    }

    func testMissingFieldsAreIgnoredRatherThanCrashing() {
        let presenter = NotificationPresenter(port: FakeCenter())
        XCTAssertFalse(presenter.present(Envelope(type: "notification.posted", payload: .object([:]))) )
    }

    func testReplyableAndRemovalUseNotificationKey() {
        let center = FakeCenter()
        let presenter = NotificationPresenter(port: center)

        XCTAssertTrue(presenter.present(frame(text: "답장해줘", key: "kakao:42", replyable: true)))
        XCTAssertEqual(center.entries.first?.identifier, "kakao:42")
        XCTAssertEqual(center.entries.first?.replyable, true)

        let removal = Envelope(type: "notification.removed", payload: .object(["key": .string("kakao:42")]))
        XCTAssertTrue(presenter.handleRemoval(removal))
        XCTAssertEqual(center.removed, ["kakao:42"])
    }
}
