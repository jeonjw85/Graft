# Permissions

Graft requests only the permissions its features need. This document explains why each one is required and what breaks if it is denied.

> Status: onboarding screens implement these prompts. Denials are handled as described below; the app never crashes on a denied permission.

## Notification allow list

Forwarding starts empty. Open the app's notification screen, grant notification access in system
settings, then tick the apps you want. Only ticked apps are forwarded, and the choice is stored on
the phone only.

| Platform | Permission | Why it is needed | If denied |
|---|---|---|---|
| macOS | Local network | Device discovery (Bonjour) and connections | Devices are not discovered; manual IP entry still works |
| macOS | Notifications | Show Android alerts as banners | Alerts are queued in the app's recent list but nothing appears; the app offers a shortcut to System Settings |
| Android | Notification access | Forward phone notifications to the Mac | Only the notification feature is disabled; the app says so in its notification screen |
| Android | Camera | Scan the pairing QR code | Pair with the code, fingerprint prefix, and IP instead; the app switches to the manual form automatically |
| Android | Notifications (Android 13+) | Show connection state and incoming notifications | Connection state is only visible inside the app |
| Android | Battery optimization exemption (optional) | Keep the connection alive in the background | The connection may drop when the screen turns off |

## Permissions we do not request

- **Full Disk Access** on macOS: Graft does not read other apps' notifications.
- **Accessibility** on macOS: there is no remote input feature.
- **Location** on Android: nearby device discovery does not rely on location permission.

## When the camera is denied

The pairing screen shows the manual form instead of the preview. The three values come from the Mac's
pairing window, and the phone still pins the Mac's full certificate fingerprint, so a denied camera
does not weaken pairing.

## Notification permission on macOS

Graft asks once, at launch. A denial does not block pairing, transfers, or clipboard sharing; only the
banners stay silent. The notification settings in the app then show a button that opens the relevant
System Settings pane.

## Vendor-specific notes

Some vendors (Xiaomi, Samsung, OnePlus, and others) aggressively terminate background services. If the connection drops often:

- Set Graft to "Unrestricted" under battery usage optimization.
- Lock Graft in the recent apps list.
