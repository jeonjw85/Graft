import XCTest
@testable import GraftMacKit
import GraftProtocol

private final class FakeCenter: NotificationCenterPort {
    var added: [(String, String, String)] = []
    func add(title: String, subtitle: String, body: String) { added.append((title, subtitle, body)) }
}

private final class FakeAuthorization: NotificationAuthorizationPort {
    var calls = 0
    var answer: Bool

    init(answer: Bool) { self.answer = answer }

    func request(completion: @escaping @Sendable (Bool) -> Void) {
        calls += 1
        completion(answer)
    }
}

final class NotificationAuthorizationTests: XCTestCase {
    func testAuthorizationIsRequestedOnlyOnce() {
        let authorization = FakeAuthorization(answer: true)
        let presenter = NotificationPresenter(
            port: FakeCenter(),
            authorization: authorization
        )

        presenter.requestAuthorizationIfNeeded()
        presenter.requestAuthorizationIfNeeded()

        XCTAssertEqual(authorization.calls, 1, "a second prompt would never appear anyway")
    }

    func testAGrantedAnswerIsRecorded() {
        let presenter = NotificationPresenter(
            port: FakeCenter(),
            authorization: FakeAuthorization(answer: true)
        )

        presenter.requestAuthorizationIfNeeded()

        XCTAssertEqual(presenter.notificationsAllowed, true)
    }

    func testADenialIsRecordedAndDoesNotBlockPresentation() {
        let center = FakeCenter()
        let presenter = NotificationPresenter(
            port: center,
            authorization: FakeAuthorization(answer: false)
        )

        presenter.requestAuthorizationIfNeeded()
        let presented = presenter.present(
            Envelope(
                type: "notification.posted",
                payload: .object([
                    "app": .string("Messages"),
                    "title": .string("Alex"),
                    "text": .string("On my way"),
                    "ts": .number(1)
                ])
            )
        )

        XCTAssertEqual(presenter.notificationsAllowed, false)
        XCTAssertTrue(presented)
        XCTAssertEqual(center.added.count, 1, "a denial must not swallow incoming notifications")
    }

    func testTheAnswerIsReportedToTheObserver() {
        let presenter = NotificationPresenter(
            port: FakeCenter(),
            authorization: FakeAuthorization(answer: false)
        )
        var reported: Bool?
        presenter.onAuthorizationChange = { reported = $0 }

        presenter.requestAuthorizationIfNeeded()

        XCTAssertEqual(reported, false)
    }

    func testAPresenterWithoutAnAuthorizationPortStillWorks() {
        let center = FakeCenter()
        let presenter = NotificationPresenter(port: center)

        presenter.requestAuthorizationIfNeeded()

        XCTAssertNil(presenter.notificationsAllowed)
        XCTAssertEqual(center.added.count, 0)
    }
}
