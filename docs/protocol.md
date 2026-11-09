# Graft 프로토콜 v1

이 문서는 맥 앱과 안드로이드 앱이 같은 로컬 네트워크에서 주고받는 메시지를 정의합니다. 두 앱의 구현보다 이 문서가 우선합니다.

- 프로토콜 버전: `1`
- 전송: TLS 1.3 상호 인증 위의 WebSocket (기본 포트 47800)
- 검색: Bonjour/mDNS `_graft._tcp`
- 스키마: [`protocol/schema/messages.schema.json`](../protocol/schema/messages.schema.json)
- 공용 픽스처: [`protocol/fixtures/`](../protocol/fixtures/)

## 1. 엔벨로프

제어 메시지는 UTF-8 JSON 텍스트 프레임 하나에 담깁니다.

```json
{
  "v": 1,
  "id": "6f1c2f7e-6f1a-4e2e-9d6e-2a1f5c3b8d40",
  "type": "file.offer",
  "ts": 1762752000000,
  "payload": {
    "tid": 7,
    "name": "보고서.pdf",
    "size": 1073741824,
    "mime": "application/pdf"
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `v` | 정수 | 예 | 프로토콜 메이저 버전. 현재 값은 `1` |
| `id` | 문자열 | 예 | 메시지 UUID v4. 응답의 `re`가 이 값을 가리킴 |
| `re` | 문자열 | 아니오 | 응답이 참조하는 요청의 `id`. 응답에만 존재하며 null이면 필드를 생략 |
| `type` | 문자열 | 예 | 메시지 타입 (2장) |
| `ts` | 정수 | 예 | 송신 시각, Unix epoch 밀리초 |
| `payload` | 객체 | 예 | 타입별 데이터. 항상 JSON 객체 |

### 1.1 상한

| 항목 | 값 |
|---|---|
| 제어 프레임 최대 크기 | 512 KiB (524288 바이트) |
| 클립보드 텍스트 최대 크기 | 256 KiB |
| 알림 텍스트 최대 길이 | 2 KiB로 절단 후 전송 |

제어 프레임이 512 KiB를 넘으면 수신 측은 연결을 닫고 `oversized`를 기록합니다. 클립보드 텍스트가 256 KiB를 넘으면 송신 측은 해당 업데이트를 보내지 않습니다.

### 1.2 버전 협상

`v`가 `1`이 아니면 수신 측은 `error{code:"proto_unsupported"}`를 보내고 연결을 닫습니다. `payload`에 모르는 필드가 있어도 무시합니다. 모르는 `type`은 로그만 남기고 무시합니다.

### 1.3 payload 규칙

`payload`는 항상 객체입니다. 객체가 아닌 payload를 받으면 `error{code:"bad_payload"}`로 응답하고 해당 메시지만 버립니다. 연결은 유지합니다.

## 2. 메시지 타입

| type | 방향 | payload 필드 |
|---|---|---|
| `hello` | 양방향 | `deviceId`, `name`, `platform`, `appVersion`, `proto`, `caps` |
| `hello.ok` | 양방향 | `deviceId`, `name`, `proto`, `caps` |
| `ping` | 양방향 | 없음 |
| `pong` | 양방향 | 없음 |
| `pair.request` | 안드로이드 → 맥 | `token`, `deviceId`, `name`, `platform` |
| `pair.ok` | 맥 → 안드로이드 | `deviceId`, `name`, `platform` |
| `clipboard.update` | 양방향 | `kind`, `body`, `ts` |
| `notification.posted` | 안드로이드 → 맥 | `pkg`, `app`, `title`, `text`, `ts`, `key`, `priority` |
| `file.offer` | 양방향 | `tid`, `name`, `size`, `mime` |
| `file.accept` | 수신 → 송신 | `tid`, `offset` |
| `file.reject` | 수신 → 송신 | `tid`, `reason` |
| `file.complete` | 송신 → 수신 | `tid`, `sha256` |
| `file.cancel` | 양방향 | `tid` |
| `error` | 양방향 | `code`, `message` |

### 2.1 필드 상세

**`hello` / `hello.ok`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `deviceId` | 문자열(1–64) | 기기별 고유 식별자 |
| `name` | 문자열(1–64) | 사용자에게 보이는 기기 이름 |
| `platform` | `"macos"` \| `"android"` | 플랫폼 |
| `appVersion` | 문자열(1–32) | 앱 버전 (`hello`에만) |
| `proto` | 정수 ≥1 | 송신 측이 지원하는 최고 프로토콜 버전 |
| `caps` | 문자열 배열(≤32) | 지원 기능 목록 |

`caps` 값: `file.transfer`, `clipboard.text`, `notification.post`.

**`pair.request`** — 아직 신뢰되지 않은 연결에서 보내는 유일한 메시지입니다. 그 외 메시지는 `error{code:"unpaired"}` 후 연결을 닫습니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `token` | 문자열(base64url, 16–128) | QR에 포함된 1회용 토큰 |
| `deviceId` | 문자열(1–64) | 안드로이드 기기 식별자 |
| `name` | 문자열(1–64) | 기기 이름 |
| `platform` | `"android"` | 플랫폼 |

**`clipboard.update`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `kind` | `"text"` | v1은 텍스트만 지원 |
| `body` | 문자열 | 클립보드 내용. 최대 256 KiB |
| `ts` | 정수 | 복사가 발생한 시각 |

**`notification.posted`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `pkg` | 문자열 | 안드로이드 패키지 이름 |
| `app` | 문자열 | 앱 표시 이름 |
| `title` | 문자열 | 알림 제목 |
| `text` | 문자열 | 알림 본문 (2 KiB로 절단) |
| `ts` | 정수 | 알림 게시 시각 |
| `key` | 문자열 | 중복 제거용 알림 키 |
| `priority` | `"low"` \| `"normal"` \| `"high"` | 우선순위 |

**파일 전송** — `tid`는 uint32 범위(0–4294967295)의 전송 식별자입니다.

- `file.offer`: `name`(1–255), `size`(≥0), `mime`
- `file.accept`: `offset`(≥0)부터 전송을 요청
- `file.reject`: `reason` 자유 문자열 (`declined`, `disk_full`, `too_large` 등)
- `file.complete`: `sha256`은 64자 소문자 16진수
- `file.cancel`: 진행 중인 전송을 중단

## 3. 이진 프레임

파일 데이터는 WebSocket 바이너리 프레임으로 전송합니다. 헤더는 16바이트, 빅엔디언입니다.

| 오프셋 | 크기 | 의미 |
|---|---|---|
| 0 | 2 | 매직 `0x47 0x46` (`GF`) |
| 2 | 1 | 헤더 버전 (`1`) |
| 3 | 1 | 예약 (`0`) |
| 4 | 4 | `transferId` (uint32) |
| 8 | 8 | 파일 내 오프셋 (uint64) |
| 16 | N | 데이터 (최대 64 KiB) |

매직이나 버전이 다르면 프레임을 버리고 `error{code:"bad_payload"}`를 보냅니다.

## 4. 에러 코드

| 코드 | 의미 | 연결 |
|---|---|---|
| `proto_unsupported` | 지원하지 않는 프로토콜 버전 | 닫음 |
| `unpaired` | 페어링되지 않은 연결에서 허용되지 않는 메시지 | 닫음 |
| `unknown_device` | 저장된 피어 목록에 없는 인증서 | 닫음 |
| `pin_mismatch` | 인증서 지문 불일치 | 닫음 |
| `bad_payload` | payload 형식 오류 | 유지 |
| `disk_full` | 수신 측 저장 공간 부족 | 유지 |
| `hash_mismatch` | 파일 해시 불일치 | 유지 |
| `transfer_not_found` | 알 수 없는 `tid` | 유지 |
| `too_large` | 허용 크기 초과 | 유지 |

## 5. 연결 수명주기

1. TLS 핸드셰이크에서 양쪽이 인증서를 제시하고, 상대 인증서의 SPKI SHA-256이 저장된 값과 일치하는지 확인합니다.
2. 미페어링 상태에서는 `pair.request`만 허용합니다.
3. 페어링 완료 후 양쪽이 `hello`를 보내고 `hello.ok`로 응답합니다. `caps` 교집합이 이 세션에서 사용할 기능입니다.
4. 20초마다 `ping`을 보내고 `pong`을 기대합니다. 60초 동안 응답이 없으면 연결이 끊긴 것으로 판단합니다.
5. 재연결은 1→2→4→8→16→32→60초 백오프에 ±20% 지터를 더해 시도합니다.

## 6. 시간 제한

| 항목 | 값 |
|---|---|
| `hello` 응답 대기 | 5초 |
| heartbeat 간격 | 20초 |
| 무응답 연결 종료 | 60초 |
| 파일 전송 무진행 중단 | 30초 |
| 페어링 토큰 유효 기간 | 120초 |
| 페어링 시도 제한 | 분당 5회 |

## 7. 호환성

- v1 안에서 필드 추가는 허용합니다. 수신 측은 모르는 필드를 무시해야 합니다.
- 필드 제거·의미 변경·타입 변경은 프로토콜 메이저 버전을 올립니다.
- 새 메시지 타입은 `caps`로 협상한 뒤에만 보냅니다.
