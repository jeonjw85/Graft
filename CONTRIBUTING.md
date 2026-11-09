# Contributing

Thanks for your interest in Graft. This document covers the development environment and the contribution workflow.

## Development environment

- **macOS:** Swift 6.x. SwiftPM tests use XCTest, so **Xcode is required**. If `xcode-select` points at the Command Line Tools, run the tests with an explicit toolchain:

  ```bash
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --package-path macos
  ```

- **Android:** JDK 21 and the Gradle wrapper. A system Gradle installation is only needed to generate or refresh the wrapper.
- **Protocol validation:** Node.js 20 or later (`ajv-cli` runs through `npx`).

## Tests

All of the following must pass before you open a pull request.

```bash
./protocol/tools/validate.sh
swift test --package-path macos
cd android && ./gradlew :core:test
./protocol/tools/e2e-pairing.sh
```

The end-to-end pairing test needs Xcode and JDK 21. It starts a headless Mac server, pairs a Kotlin peer over TLS, and checks that a replayed token is rejected.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/).

```text
feat(macos): add pasteboard watcher
fix(android): handle reconnection after Wi-Fi change
docs: clarify pairing flow
test: add file transfer resume fixtures
ci: run core tests on pull requests
chore: bump Gradle wrapper
```

Do not mention model names or AI tools in commit messages.

## Pull requests

- Keep changes focused, and split unrelated refactoring into a separate pull request.
- Include tests for behavior changes.
- Update `README.md` or `docs/` when a change is user-visible.
- Do not include secrets, personally identifying information, or real device names and IP addresses in code or log examples.

## Bug reports

Include reproduction steps and your environment (macOS/Android version, device model) in the issue. For security issues, follow [SECURITY.md](SECURITY.md).
