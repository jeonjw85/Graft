# 기여 안내

Graft에 관심을 가져 주셔서 감사합니다. 이 문서는 개발 환경과 기여 절차를 설명합니다.

## 개발 환경

- **macOS:** Swift 6.x. SwiftPM 테스트는 XCTest를 사용하므로 **Xcode가 필요합니다**. `xcode-select`가 Command Line Tools를 가리키고 있다면 다음처럼 실행하세요.
  ```bash
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --package-path macos
  ```
- **Android:** JDK 21과 Gradle wrapper를 사용합니다. 시스템에 설치된 Gradle로 wrapper를 생성할 수 있습니다.
- **프로토콜 검증:** Node.js 20 이상 (스키마 검증에 `ajv-cli`를 `npx`로 실행)

## 테스트

변경 사항을 올리기 전에 다음 명령이 모두 통과해야 합니다.

```bash
./protocol/tools/validate.sh
swift test --package-path macos
cd android && ./gradlew :core:test
```

## 커밋 메시지

Conventional Commits 형식을 사용합니다.

```text
feat(macos): add pasteboard watcher
fix(android): handle reconnection after Wi-Fi change
docs: clarify pairing flow
test: add file transfer resume fixtures
ci: run core tests on pull requests
chore: bump Gradle wrapper
```

커밋 메시지에 모델명이나 AI 도구 표기를 넣지 않습니다.

## 풀 리퀘스트

- 변경 범위를 작게 유지하고, 관련 없는 리팩터링은 분리합니다.
- 동작 변경에는 테스트를 함께 포함합니다.
- 사용자에게 보이는 변경이면 `README.md`나 `docs/`를 갱신합니다.
- 비밀정보, 개인 식별 정보, 실제 기기 이름·IP를 코드나 로그 예시에 넣지 않습니다.

## 버그 신고

재현 절차와 환경(맥/안드로이드 버전, 기기 모델)을 포함해 이슈로 남겨 주세요. 보안 문제는 [SECURITY.md](SECURITY.md)를 따라 주세요.
