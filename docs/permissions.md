# Permissions

Graft requests only the permissions its features need. This document explains why each one is required and what breaks if it is denied.

> Status: draft. It will be updated alongside the app implementation.

| Platform | Permission | Why it is needed | If denied |
|---|---|---|---|
| macOS | Local network | Device discovery (Bonjour) and connections | Devices are not discovered; manual IP entry still works |
| Android | Notification access | Forward phone notifications to the Mac | Only the notification feature is disabled |
| Android | Camera | Scan the pairing QR code | Pair with the six-digit code instead |
| Android | Notifications (Android 13+) | Show connection state and incoming notifications | Connection state is only visible inside the app |
| Android | Battery optimization exemption (optional) | Keep the connection alive in the background | The connection may drop when the screen turns off |

## Permissions we do not request

- **Full Disk Access** on macOS: Graft does not read other apps' notifications.
- **Accessibility** on macOS: there is no remote input feature.
- **Location** on Android: nearby device discovery does not rely on location permission.

## Vendor-specific notes

Some vendors (Xiaomi, Samsung, OnePlus, and others) aggressively terminate background services. If the connection drops often:

- Set Graft to "Unrestricted" under battery usage optimization.
- Lock Graft in the recent apps list.
