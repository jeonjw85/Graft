## 변경 요약

<!-- 무엇을, 왜 바꿨는지 적어 주세요. -->

## 테스트

<!-- 어떤 명령을 실행했는지, 수동으로 무엇을 확인했는지 적어 주세요. -->

```bash
./protocol/tools/validate.sh
swift test --package-path macos
cd android && ./gradlew :core:test
```

## 체크리스트

- [ ] 관련 테스트가 통과한다
- [ ] 사용자에게 보이는 변경이면 문서를 갱신했다
- [ ] 비밀정보, 실제 기기 이름, IP 주소를 포함하지 않았다
- [ ] 와이어 프로토콜을 바꿨다면 `docs/protocol.md`와 `protocol/fixtures/`를 함께 갱신했다
