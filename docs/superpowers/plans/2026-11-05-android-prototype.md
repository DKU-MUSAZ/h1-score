# 안운잘부 Android 프로토타입 실행 계획

사용자가 승인한 주제 구체화 문서와 참가 흐름을 구현한다. 앱 안운잘부 / 팀 무사즈 / MUSAZ 4개 포즈를 유지한다.

## 구현 목표

정차 중 목적지 확인 → 참가 예약 → 내비 경로의 마지막 10km 자동 기록 → 도착 및 P 상태 확인 → 점수 근거와 그룹 결과 → 마일리지·배지.

## 설계 방향

- 가로형 차량 화면. 잉크 네이비, 따뜻한 백색, 민트 강조색. 큰 숫자와 읽기 쉬운 한글. 장식적인 그라데이션 대신 넓은 여백과 명확한 정보 순서.
- 홈에는 목적지와 참가 가능 여부, 참가 화면에는 측정·보상 조건. 결과에는 점수 근거와 정산 상태. 한 화면의 주 행동은 하나.
- MUSAZ는 정차 화면의 안내·성취를 담당한다. 주행 중에는 캐릭터·순위·보상 애니메이션을 숨긴다.
- 기존 PNG는 화면에서 클리핑하여 네 포즈를 사용한다. 실제 3D 모델로 표시하지 않는다.
- 실제 SDK 입력과 시연 입력을 별도 모드로 구분한다. 시연 전용 제어판은 정차 중에만 연다. 실차에서 시연 모드를 켜지 않는다.

## 구조

Kotlin / Jetpack Compose / AGP 8.9.2 / Gradle 8.11.1 / compileSdk 35 / minSdk 28.

- `core`: Android와 독립된 참가 상태, 점수·거리 적합성, 동점 정산 규칙.
- `app`: Compose 화면, 상태 보존, 로컬 시연 데이터, Pleos/AAOS 연결 어댑터.
- Vehicle 2.3.2 및 NaviHelper 3.0.5: 공식 Maven에서 수신한 실제 의존성.
- AAOS: 속도와 기어, 주의 분산 제한. Pleos 상태 API: Brake, TurnSignal, Odometer, Seat 지원 여부 확인 후 읽기.
- 네트워크 매칭·현금성 정산은 이번 로컬 프로토타입 범위 밖. 결과의 다른 참가자와 마일리지는 명시한 시연 데이터.

## 작업 목록

- [x] Android Studio, SDK, 에뮬레이터 설치 및 버전 기록
- [x] 참가/10km 경계/짧은 이동/주차 게이트/동점·중복 지급 테스트 작성 후 실행
- [x] core 구현과 테스트 통과 — 최종 20개
- [x] SDK 의존성·퍼미션·실제 콜백을 확인하고 입력 어댑터 구현
- [x] 목적지·참가·예약·주행·결과·혜택·배지·연결 확인 화면 구현
- [x] APK 빌드, 단위 테스트, 린트 — 빌드 성공, 린트 오류 0 / 경고 11
- [x] 에뮬레이터 기본 전체 흐름 검수 — 11월 6일 참가→92점/80M→신규 배지 및 53점/0M 울먹임 확인. 추가 조합 검수는 QA.md에 명시
- [x] 실행 안내·제약·공식 출처 문서, APK 제공 — 초기 캡처는 이전 화면임을 명시

## 검증할 핵심

10km를 넘나들어도 시작은 한 번. 미동의 기록 금지. 목적지 변경은 세션 취소. 데이터 부족은 0점/100점 대신 미평가. 속도 0은 주차와 구분. 긴급 제동을 자동 감점하지 않음. 같은 정산 ID는 한 번만 지급. 주행 중 순위·보상 조작 차단.

## 공식 근거 (2026-11-05 확인)

- https://appdevchallenge.auto-hmg.io/guide/development
- https://appdevchallenge.auto-hmg.io/guide/judging-awards
- https://document.pleos.ai/docs/pleos-only/create-my-project
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission

## 결정 기록

- 기존 사용자 승인에 따라 별도 재승인 없이 구현한다.
- 공개 문서상 Vehicle State와 NaviHelper는 SDK 신청 없이 사용 가능. 다른 서비스의 Coming Soon/접근 조건을 성공으로 표시하지 않는다.
- SDK 개수보다 고객 흐름에 필요한 실제 데이터와 그 활용을 우선한다.
- 차량특화성·창의성·기술 활용성·사업성은 공식 평가 항목. 기업 인재상은 별도 평가 항목으로 주장하지 않는다.
- 사용자 추가 요청: 새 배지를 받을 때만 방패를 든 MUSAZ를 사용한다. 홈/일반 결과/컬렉션에는 사용하지 않는다.
- 표정 세 종(응원/축하/울먹임)을 추가하고 새 홈에 적용했다. 75/90점 경계는 시연 표현 기준이다.
- 자세한 현재 상태는 `docs/prototype/QA.md`, 실행 순서는 `docs/prototype/START-HERE.md`를 참조한다.
