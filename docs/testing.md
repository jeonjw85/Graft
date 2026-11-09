# 테스트 안내

> 상태: 초안. 각 마일스톤에서 확장됩니다.

## 자동 테스트

```bash
./protocol/tools/validate.sh          # 프로토콜 픽스처와 JSON Schema
swift test --package-path macos       # Swift 프로토콜 코어
cd android && ./gradlew :core:test    # Kotlin 프로토콜 코어
```

두 코어 테스트는 같은 픽스처(`protocol/fixtures/`)를 읽습니다. 한쪽에서만 통과하면 와이어 호환성이 깨진 것입니다.

## 수동 테스트 매트릭스

| 항목 | 값 |
|---|---|
| macOS | 13, 14, 15 이상 |
| Android | 10, 13, 14, 15 이상 |
| 네트워크 | 같은 Wi-Fi, AP 격리 환경, Wi-Fi 전환 |
| 화면 | 잠금, 꺼짐, 배터리 절약 모드 |
| 입력 | 한글 파일명, 이모지, 1GB 이상 파일 |

## 릴리스 전 확인

- [ ] 자동 테스트 전체 통과
- [ ] 새 기기에서 페어링부터 파일 전송까지 처음부터 재현
- [ ] 권한 거부 상태에서 앱이 죽지 않고 안내를 표시
- [ ] 비행기 모드 전환 후 자동 재연결
