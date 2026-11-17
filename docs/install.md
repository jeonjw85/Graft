# Install Graft

Graft runs on a Mac and an Android phone on the same Wi-Fi network. Nothing leaves that network.

## Requirements

| Side | Minimum |
|---|---|
| macOS | 13 (Ventura) |
| Android | 10 (API 29) |
| Network | Both devices on the same LAN; client isolation disabled on the router |

## macOS

1. Download `Graft-<version>.dmg` from the release page.
2. Open the image and drag **Graft** into **Applications**.
3. The build is **not notarized** (no Apple Developer ID). On first launch macOS refuses it:
   - Right-click **Graft** in Applications and choose **Open**, then **Open** again, or
   - `xattr -dr com.apple.quarantine /Applications/Graft.app`
4. Graft has no Dock icon; it appears in the menu bar. Allow **Local Network** access when asked,
   otherwise pairing and reconnecting cannot work.
5. Allow notifications if you want phone alerts to appear on the Mac.

## Android

1. Download `Graft-<version>.apk` from the release page (or install from Google Play once the
   listing is published).
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

- macOS: quit from the popover, then move the app to the Trash.
- Android: uninstall the app. Received files stay in `Download/Graft`.
