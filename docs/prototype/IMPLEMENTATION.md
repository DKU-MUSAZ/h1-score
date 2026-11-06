# 구현 범위와 기술 연결

2026-11-05 · 0.2.0-prototype

## 구조와 버전

`core`는 순수 Kotlin으로 참가 상태·점수·순위·지급 한도·배지·표정 구간을 계산한다. `app`은 Compose 화면, 앱 수명 동안 유지되는 모델, Pleos/AAOS 어댑터, 로컬 저장을 담당한다. 사용자 동의로 시작한 기록은 foreground service로 내비게이션 앱 사용 중에도 유지하도록 구성했다.

| 구성 | 버전 / 역할 |
| --- | --- |
| Android Studio | Rabbit 2 / 2026.2.2 |
| Gradle / AGP | 8.11.1 / 8.9.2 |
| Kotlin / Compose BOM | 2.1.20 / 2025.04.01 |
| JDK | 21.0.7, 앱 바이트코드 JVM 17 |
| Android | compile 35 / target 34 / min 28 |
| Pleos 에뮬레이터 | Connect 2.1.0, API 34, ARM64, 1920×1080 |
| Vehicle | ai.pleos.playground:Vehicle:2.3.2 |
| NaviHelper | ai.pleos.playground:NaviHelper:3.0.5 |

SDK는 공식 Maven 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받았다. 사용 권한 선언과 실제 기기의 Capability를 확인하며, 없는 데이터를 실제 값처럼 합성하지 않는다.

## 데이터별 상태

| 연결 | 앱에서 하는 일 | 이번 검증 |
| --- | --- | --- |
| NaviHelper getDestinationInfo | 목적지 ID·남은 거리, 10km 시작 | 연결 및 0m 초기 응답. 실제 경로의 거리 변화는 미검증 |
| onCurrentRoadSpeedLimit | 제한속도 준수 지표 | 초기 0km/h 콜백. 0은 무효 처리 |
| 도착·목적지 변경·안내 취소 | 정산 시점, 세션 취소 | 어댑터 구현. 실제 경로 이벤트 시연은 남음 |
| AAOS PERF_VEHICLE_SPEED | m/s 속도와 가속 | Car service에서 0.00m/s 수신 |
| AAOS GEAR_SELECTION + UX | 결과·설정·배지의 주차 게이트 | P 기어, UX 제한 false 응답 |
| Vehicle Seat | 참가 전 앞좌석 왼쪽 벨트 확인 | READ, 버클 열림. 좌핸들 시연 가정 |
| Vehicle Brake | 상태 조회·연결 확인 | READ, 해제. 점수 감점에 사용하지 않음 |
| Vehicle TurnSignal | 상태 조회·연결 확인 | READ, OFF. 사용 횟수로 가점하지 않음 |
| Vehicle Odometer | 거리 보조 정보 | READ, 0.0 원시값. 평가 거리는 속도 적분 |
| FusedLocation / TTS / STT | 후속 후보 | 이번 버전에 연동하지 않음 |
| 참가자 매칭·공유 | 같은 목적지 그룹 | 별도 서버 필요. 5인 가상 데이터 |

SDK 연결과 실제 주행 전 과정 검증은 구분한다. 연결 화면과 수신 로그가 이번 구현의 증거이며, 실차·도로 검증이나 시장 심사 통과를 의미하지 않는다.

## 참가·평가

- 출발 전에 정차 상태에서 동의·예약한다. 남은 경로가 10km 이하가 되는 첫 시점부터 기록한다. 주행 중 수락 팝업을 누르도록 하지 않는다.
- 처음부터 10km 미만이면 바로 개인 기록을 시작한다. 순위/M 대상이 아니다.
- 10km 경계를 여러 번 통과해도 시작은 한 번이다. 목적지가 달라지거나 안내가 취소되면 기존 세션을 종료한다.
- 도착만으로 결과를 공개하지 않는다. P단·속도·차량 화면 제한을 함께 확인한다.
- 속도/제한속도 누락, 2.5초를 넘는 샘플 간격, 비정상 값은 제외한다. 신선도는 속도 2.5초, 목적지 10초, 제한속도 120초다.
- 개인 점수는 유효 거리 500m 이상 및 커버리지 90% 이상에서 표시한다. 순위는 9km 이상 및 90% 이상이 필요하다.

제한속도 SDK가 변경 시에만 알리는 환경에서는 120초 만료가 정상 데이터를 지나치게 제외할 수 있다. 실제 도로 링크와 갱신 의미를 확인해 수정해야 한다.

## 주행습관 점수 v0.1

구간 거리는 이전/현재 속도의 평균 × 시간이다. 제한속도 초과량을 15km/h로 정규화한 값(0~1)에 구간 거리를 곱해 준수 지표의 감점을 계산한다. 가속도가 2.5m/s²를 넘는 구간의 거리 비율로 부드러운 가속 지표를 계산한다. 최종 점수는 `준수 70% + 가속 30%`를 반올림한다.

15km/h는 허용 과속 기준이 아니라 시제품 감점 곡선의 분모다. 2.5m/s²도 검증 전 가설이다. 위험 회피 제동을 구별할 수 없어 급제동은 자동 감점하지 않는다. 전체 안전 운전이나 사고 확률을 진단하는 점수가 아니다.

## M·배지

완료 보상 0M. 유효 기록이고 85점 이상이면 정해진 순위 슬롯을 지급한다. 3인 60/20/0, 4인 80/40/20/0, 5인 100/60/30/0/0M. 동점자는 차지한 슬롯 합계를 인원으로 나눈 정수 몫을 받는다. 도착 시간으로 동점을 가르지 않는다.

동일 세션 중복 지급, 동일 목적지 하루 2회 지급, 전체 하루 3회 지급을 로컬 원장에서 차단한다. 실제 서비스의 서버 원장·위변조 방지 기능을 대신하지 않는다.

현재 배지는 첫 유효 평가, 해당 목적지 첫 유효 방문, 85점 이상 TOP 3다. 소유 ID로 중복 발급을 막고 실제/시연 모드를 분리한다. 연속 일수/5회 배지는 준비 중이다. 방패를 든 무사즈는 신규 `BadgeCelebration`에서만 사용한다.

## 저장·제약

잔액·정산 ID·일일 한도·소유 배지 ID는 기기 SharedPreferences에 저장한다. 원시 샘플, 현재 세션, 최근 보고서, 아직 열어보지 않은 배지 안내는 프로세스 메모리에 있다. OS가 프로세스를 종료하면 진행 기록은 복원하지 않는다. 이미 지급한 배지/M은 중복 지급하지 않는다.

외부 서버로 위치·주행 데이터를 보내는 기능은 없다. 사용자 매칭·순위 공유, 쿠폰 교환, 서버 정산, 음성 수락은 후속 구현이다.

## 공식 근거

- [공모전 개발 환경](https://appdevchallenge.auto-hmg.io/guide/development)
- [공모전 심사·시상](https://appdevchallenge.auto-hmg.io/guide/judging-awards): 차량특화성·창의성·기술 활용성·사업성
- [프로젝트 및 SDK 사용 안내](https://document.pleos.ai/docs/pleos-only/create-my-project)
- [Vehicle SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)
- [Vehicle 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)
- [NaviHelper SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
- [NaviHelper 이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners)
- [에뮬레이터 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [운전자 주의 분산 설계](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements)
