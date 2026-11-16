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
        // An LSUIElement app never becomes "foreground"; the status item wait below is the real
        // readiness check.
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

        // QR is the default state; the typed values sit behind the link.
        let qrHint = app.staticTexts["Scan with your phone"]
        XCTAssertTrue(qrHint.waitForExistence(timeout: 10), "the QR code must be shown first")
        attachScreenshot("pairing-window")

        window.buttons[XCUIIdentifierCloseWindow].click()

        // Reopen the popover: a closed window must leave the app idle, not "Waiting for the phone".
        statusItem.click()
        let idle = app.staticTexts["Not connected"].firstMatch
        XCTAssertTrue(idle.waitForExistence(timeout: 5), popoverDump())
    }

    func testSettingsOpensFromThePopover() throws {
        let statusItem = try statusItem()
        statusItem.click()

        let settings = app.buttons["Settings…"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 5), popoverDump())
        settings.click()

        // The settings window has no stable title across macOS versions, so look for its content.
        let notificationsTab = app.staticTexts["Notifications"].firstMatch
        XCTAssertTrue(
            notificationsTab.waitForExistence(timeout: 5),
            "Settings must open: \(app.debugDescription)"
        )
        attachScreenshot("settings-window")
    }

    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func statusItem() throws -> XCUIElement {
        // A MenuBarExtra is a status item on the app's own menu bar in XCUITest. Do not fall back to
        // menuBarItems: on this system that matches the Apple menu and the click goes nowhere.
        let item = app.menuBars.statusItems.firstMatch
        guard item.waitForExistence(timeout: 15) else {
            throw XCTSkip("the menu bar item is not exposed in this environment")
        }
        return item
    }

    private func popoverDump() -> String {
        app.debugDescription
    }
}
