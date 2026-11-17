# Google Play checklist

The APK and AAB are signed with the release key in `~/.config/graft/release.jks` (alias `graft`).
Keep that file and its password backed up: losing it means losing the ability to update the app.

## Before uploading

- [ ] Google Play developer account (one-time fee) and app created for `kr.jjw.graft`.
- [ ] Enrol in **Play App Signing**. The local keystore becomes the *upload key*; Google holds the
      app signing key.
- [ ] Upload `Graft-<version>.aab` from `scripts/release.sh`.
- [ ] Store listing: title, short and full description, screenshots (1080×1920 or larger),
      1024×500 feature graphic, 512×512 app icon. **The repository has no store assets yet.**
- [ ] Privacy policy URL: publish `release/graft-privacy.html` at `https://jjw.kr/policy/graft`
      (same layout as the other app policies) and paste that URL. The Korean page is the one Play
      links to; `docs/privacy.md` keeps the English source in this repository.
- [ ] Data safety form: "No data collected", "No data shared". All traffic is on the local network.
- [ ] Content rating questionnaire.

## Sensitive-permission declarations

Play requires a declaration for these; each can delay or block review:

| Permission | Why Graft needs it | Risk |
|---|---|---|
| `CAMERA` | Only to scan the pairing QR code | Low; the typed-code path exists, so the declaration should say the camera is optional |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Forward notifications the user explicitly allows | **Highest risk.** Play restricts notification access to apps whose core function needs it. Expect a review question; explain that forwarding is user-initiated per app, the filter runs on-device, and no data leaves the LAN. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the LAN link alive | Low; declare the connected-device use |
| `POST_NOTIFICATIONS` | Show connection state | Low |

If Play rejects the notification listener, the app still ships the other features; keep the listener
declaration separate from the file-transfer feature so a rejection does not block the release.

## Store copy

Short description (80 characters):

> Send files and clipboard text between your Mac and phone, on your own Wi-Fi.

Full description:

> Graft connects your Mac and Android phone directly over your local network. There is no account, no
> cloud, and no relay.
>
> - Pair once by QR code, or by a six-digit code with the Mac's fingerprint.
> - Send files both ways with resume and integrity checks.
> - Share clipboard text in both directions.
> - See your phone's notifications on the Mac, for the apps you allow.
>
> Everything stays on your Wi-Fi. Graft does not collect analytics or send data to any server.

## Release steps

1. `scripts/release.sh 0.1.0`
2. Upload the AAB in the Play Console, fill the declarations above, and roll out to internal testing.
3. Publish the GitHub release with the DMG, APK, AAB, and `SHA256SUMS`.
