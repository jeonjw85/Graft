# Testing

> Status: draft. It grows with each milestone.

## Automated tests

```bash
./protocol/tools/validate.sh          # protocol fixtures and JSON Schema
swift test --package-path macos       # Swift protocol core
cd android && ./gradlew :core:test    # Kotlin protocol core
```

Both core test suites read the same fixtures in `protocol/fixtures/`. If only one side passes, wire compatibility is broken.

## Manual test matrix

| Area | Values |
|---|---|
| macOS | 13, 14, 15 and later |
| Android | 10, 13, 14, 15 and later |
| Network | Same Wi-Fi, AP isolation, Wi-Fi handoff |
| Screen | Locked, off, battery saver |
| Input | Korean filenames, emoji, files over 1 GB |

## Before a release

- [ ] All automated tests pass
- [ ] A fresh device completes pairing through file transfer from scratch
- [ ] The app survives denied permissions and explains how to fix them
- [ ] Reconnects automatically after airplane mode
