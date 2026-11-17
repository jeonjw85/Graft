# Privacy policy

Last updated: 2026-11-17

The published policy linked from Google Play is the Korean page at
<https://jjw.kr/policy/graft>. This file is the English source for the same content.

Graft connects a Mac and an Android phone on the same local network. It has no accounts, no servers,
and no analytics.

## What Graft handles

| Data | Where it goes | Why |
|---|---|---|
| Files you send | Directly to the other paired device over your LAN | The transfer feature |
| Clipboard text | Directly to the other paired device over your LAN | The clipboard feature. Only while the phone app is open, or when you press Send clipboard |
| Notifications from apps you allow | Directly to your Mac over your LAN | The notification forwarding you enabled |
| Device certificate fingerprint | Stored on both devices | Pinning, so the connection is authenticated |

Nothing is sent to the developer or any third party. Graft does not collect usage data, crash
reports, identifiers, or advertising data.

## What is stored on the device

- The paired device's fingerprint and name.
- The app's own key pair (Android Keystore, or the macOS Keychain).
- Recent transfer and notification entries, in memory, until the app quits.
- Your notification allow list (Android only).

Uninstalling the app removes this data. Files you received stay where you saved them.

## Permissions

| Permission | Purpose | If you decline |
|---|---|---|
| Camera (Android) | Scan the pairing QR code | Pair with the typed code instead |
| Notification access (Android) | Forward the notifications you allow | Only forwarding is unavailable |
| Notifications | Show connection state and forwarded alerts | The app works; status is only visible inside it |
| Local Network (macOS) | Find and connect to your phone | Pairing and transfers cannot work |
| Battery optimisation exemption (Android, optional) | Keep the connection alive in the background | Android may stop the link when idle |

## Children

Graft is a utility, not directed at children, and collects no personal data.

## Changes

Changes to this policy appear in this file with a new date.

## Contact

Open an issue at <https://github.com/jeonjw85/Graft/issues>.
