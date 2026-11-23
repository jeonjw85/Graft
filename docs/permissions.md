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
| macOS | Notifications | Show Android alerts as banners (with inline reply) | Alerts are queued in the app's recent list but nothing appears; the app offers a shortcut to System Settings |
| macOS | Files / Desktop folder (opt-in only) | Watch for new macOS screenshots only when **Send new Mac screenshots automatically** is enabled | Feature stays off by default; Graft never touches Desktop unless opted in |
| Android | Notification access | Forward phone notifications to the Mac | Only the notification feature is disabled; the app says so in its notification screen |
| Android | Camera | Scan the pairing QR code | Pair with the code, fingerprint prefix, and IP instead; the app switches to the manual form automatically |
| Android | Notifications (Android 13+) | Show connection state and incoming notifications | Connection state is only visible inside the app |
| Android | Battery optimization exemption (optional) | Keep the connection alive in the background | The connection may drop when the screen turns off |

## Permissions we do not request

- **Full Disk Access** on macOS: Graft does not read other apps' notifications.
- **Accessibility** on macOS: there is no remote input feature.
- **Location** on Android: nearby device discovery does not rely on location permission.

## What the app does when a permission is missing

Nothing is silently blocked. Each optional capability either falls back or tells the user what to
fix and where:

| Missing | App behaviour |
|---|---|
| Camera | Pairing still works with the typed code, fingerprint, and IP. After the system stops asking, the scan step says the camera is blocked and offers **Open settings**. |
| Notification access (Android) | Alerts tab shows the state and a button to the system notification-access screen; nothing else changes. |
| Battery optimisation (Android) | Settings → Background shows whether Graft is exempt and opens **Battery optimisation settings** when needed. |
| App notifications (Android 13+) | Connection tab warns that the connection notification is hidden, with **Open notification settings**. |
| Notifications (macOS) | Settings > Notifications shows the state and opens the system pane. |
| Local network (macOS) | A failed start shows the error plus **Try again** and **Privacy Settings**. |

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
