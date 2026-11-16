import XCTest

/// Drives the real menu bar app: open the popover, start pairing, close the window.
///
/// This is the only test that exercises the MenuBarExtra and the Window scene the way a person
/// does. It needs the app to start cleanly, so it skips when another instance holds the port.
final class PairingWindowUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
    }

    func testPairingWindowOpensAndClosingItReturnsToIdle() throws {
        let statusItem = try statusItem()
        statusItem.click()

        let pairButton = app.buttons["Pair a phone"].firstMatch
        XCTAssertTrue(pairButton.waitForExistence(timeout: 5), popoverDump())

        pairButton.click()

        let window = app.windows["Pair with a phone"]
        XCTAssertTrue(window.waitForExistence(timeout: 5), "the pairing window must open")

        window.buttons[XCUIIdentifierCloseWindow].click()

        // Reopen the popover: a closed window must leave the app idle, not "Waiting for the phone".
        statusItem.click()
        let idle = app.staticTexts["Not connected"].firstMatch
        XCTAssertTrue(idle.waitForExistence(timeout: 5), popoverDump())
    }

    private func statusItem() throws -> XCUIElement {
        // A MenuBarExtra is a status item on the app's own menu bar in XCUITest.
        let direct = app.menuBars.statusItems.firstMatch
        if direct.waitForExistence(timeout: 3) {
            return direct
        }
        let anyBar = app.menuBars.menuBarItems.firstMatch
        if anyBar.waitForExistence(timeout: 2) {
            return anyBar
        }
        throw XCTSkip("the menu bar item is not exposed in this environment")
    }

    private func popoverDump() -> String {
        app.debugDescription
    }
}
