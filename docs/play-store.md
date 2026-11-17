# Google Play checklist

The APK and AAB are signed with the release key in `~/.config/graft/release.jks` (alias `graft`).
Keep that file and its password backed up: losing it means losing the ability to update the app.

## Before uploading

- [ ] Google Play developer account (one-time fee) and app created for `kr.jjw.graft`.
- [ ] Enrol in **Play App Signing**. The local keystore becomes the *upload key*; Google holds the
      app signing key.
- [x] Upload `dist/Graft-0.1.2.aab` (versionCode 3, versionName 0.1.2). v0.1.1 lacked an app icon in
      the bundle, which Play rejects; v0.1.2 adds adaptive and legacy launcher icons.
- [x] Store listing assets in `release/store/`:
      - `icon-512.png` — 512×512, 32-bit PNG, no transparency
      - `feature-1024x500.png` — 1024×500, no transparency, content inside the safe area
      - `01-files.png`, `02-connection.png`, `03-alerts.png` — 1080×2400 captures of the real app
      - `icon-1024.png` — unused master for the app bundle
- [ ] Store listing text: title (`Graft — Mac & Android Link`), short and full description
- [x] Privacy policy URL: https://jjw.kr/policy/graft (published 2026-11-17). The Korean page is
      the one Play links to; `docs/privacy.md` keeps the English source in this repository.
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

## Closed testing plan (new personal account)

A personal developer account created after 2023-11-13 needs a closed test with **12 testers opted in
continuously for 14 days** before production access can be requested. The clock starts when a tester
opts in, so finish the setup first.

### Order

1. **Internal testing** (immediate, optional): upload the AAB, install it on one device, confirm the
   update path with the release key.
2. **App setup**: store listing, data safety, content rating, target audience, ads, app access,
   privacy policy. Production access stays disabled until these are complete.
3. **Closed testing**: create the track, add 14–15 tester emails (buffer for drop-outs), publish the
   build, and send the opt-in link.
4. **Wait 14 continuous days.** A tester who opts out restarts their own clock; the count is taken on
   the day you apply.
5. **Apply for production access** from the Dashboard and answer the three sections (closed test,
   app value, production readiness). Review usually takes a week or less.

### Answers to prepare

| Console question | Answer |
|---|---|
| App access | All functionality is available without special access (no login) |
| Ads | No ads |
| Target audience | 18+; not directed at children |
| Data safety | No data collected, no data shared. Files, clipboard text, and notifications move only between the user's own devices on their LAN |
| Privacy policy | https://jjw.kr/policy/graft |
| Category | Tools |
| Camera permission | Optional: scans the pairing QR code; typed code works without it |
| Notification access | User selects apps one by one; the filter runs on the phone; nothing leaves the LAN. Expect a review question |
| Foreground service | `connectedDevice`: keeps the LAN link alive |

### Tester message (Korean)

```
Graft 테스트에 초대합니다. Mac과 안드로이드 폰 사이에서 파일·클립보드·알림을 같은 Wi-Fi 안에서만
주고받는 앱입니다. 계정도 서버도 없습니다.

1. 아래 링크를 열고 Google 계정으로 참여를 눌러 주세요.
   <opt-in 링크>
2. Play 스토어에서 Graft를 설치합니다.
3. 테스트할 것:
   - Mac 앱에서 Pair a phone → 폰으로 QR 스캔 또는 6자리 코드 입력
   - 폰에서 사진 한 장을 Mac으로 전송, Mac에서 폰으로 파일 전송
   - 폰에서 글자를 복사해 Send clipboard, Mac에서 복사해 폰에서 붙여넣기
   - 알림 허용 목록에 앱 하나를 추가하고 그 앱의 알림이 Mac에 뜨는지
4. 문제가 있으면 스크린샷과 함께 <연락처>로 알려 주세요.

중요: 14일 동안 테스트에서 나가지 말아 주세요. 중간에 나가면 참여 기간이 처음부터 다시 계산됩니다.
```

### What to watch during the test

- Install and update succeed from Play (not just the sideloaded APK).
- Pairing works on a phone that has never seen the Mac.
- A dropped Wi-Fi reconnects within a few seconds.
- Review feedback on the notification-access declaration; be ready to justify why the app needs it.

## Release steps

1. `scripts/release.sh 0.1.0`
2. Upload the AAB in the Play Console, fill the declarations above, and roll out to internal testing.
3. Publish the GitHub release with the DMG, APK, AAB, and `SHA256SUMS`.
