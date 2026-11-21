# Install Graft

Graft runs on a Mac and an Android phone on the same Wi-Fi network. Nothing leaves that network.

## Requirements

| Side | Minimum |
|---|---|
| macOS | 13 (Ventura) |
| Android | 10 (API 29) |
| Network | Both devices on the same LAN; client isolation disabled on the router |

## macOS

1. Download `Graft-<version>.dmg` from
   [the releases page](https://github.com/jeonjw85/Graft/releases), and check it against
   `SHA256SUMS` (`shasum -a 256 -c SHA256SUMS`).
2. Open the image and drag **Graft** into **Applications**.
3. The build is **not notarized** (no Apple Developer ID), so macOS blocks the first launch.
   - **macOS 15 and later:** double-click Graft, choose **Done** in the dialog, then open
     **System Settings → Privacy & Security**, scroll down, click **Open Anyway**, authenticate,
     and confirm **Open**. Right-click → Open no longer overrides this.
   - **macOS 13–14:** right-click **Graft** in Applications and choose **Open**, then **Open** again.
   - Terminal alternative: `xattr -dr com.apple.quarantine /Applications/Graft.app`
4. Graft has no Dock icon; it appears in the menu bar. Allow **Local Network** access when asked,
   otherwise pairing and reconnecting cannot work.
5. Allow notifications if you want phone alerts to appear on the Mac.

## Android

1. Download `Graft-<version>.apk` from
   [the releases page](https://github.com/jeonjw85/Graft/releases), or install from Google Play.
   The two channels are signed with different keys: switching between them requires uninstalling
   first, which also clears the pairing.
2. Allow installing unknown apps for the browser or file manager you downloaded with.
3. Open Graft and follow onboarding: notification access is optional, camera access enables QR
   scanning but typed values work without it.
4. For a reliable background connection, open **Connection → Background** and allow Graft to run
   without battery optimisation.

## Pair

1. On the Mac, click the menu bar icon, then **Pair a phone**.
2. Optionally choose **No camera? Use a code instead** to show the six-digit code, fingerprint
   prefix, and address.
3. On the phone, scan the QR code, or type the three values.
4. The phone pins the Mac's certificate; later launches connect without pairing again.

## Uninstall

- Android: uninstall the app. Received files stay in `Download/Graft`.
- macOS: quit from the popover and move the app to the Trash, then delete
  `~/Library/Application Support/Graft` and the **Graft** item in Keychain Access. The app has no
  uninstaller, and moving it to the Trash leaves the peer pin and the device identity behind.
