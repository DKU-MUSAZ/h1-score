# 검증 결과 · 0.2.0-prototype

최신 검증일: 2026-11-07. 실제 수행한 검사와 남은 검사를 구분한다.

## 11월 7일 · Virtual Console 입력 시험

기존 프로토타입 제어 모달·주행·결과·1km별 가점 화면에 디버그 에뮬레이터용 시험 진입 경로를 추가했다. 점수 엔진과 `longitudinal-v2` 기준은 변경하지 않는다. `CarPropertyManager`의 실제 차속·원본 timestamp를 수집하되 선택한 제한속도(50/80/100km/h)와 벨트 착용은 시험 가정이다. 내비·참가 시간 조건 없이 기록하며 GPS 회전 감점은 수집하지 않는다. 순위·마일리지·배지·완료 횟수는 증가시키지 않는다.

### 직접 재현 절차

1. Pleos 에뮬레이터 실행 후 `adb forward tcp:8088 tcp:8088`, 브라우저에서 `http://127.0.0.1:8088/` 접속.
2. P단·0km/h에서 **Pleos 연결 → 프로토타입 제어 → 시험 제한속도 선택 → 콘솔 입력 테스트 시작**.
3. 콘솔에서 D를 선택하고 ACCEL을 누른 채 가속한 뒤 BRAKE를 눌러 감속한다. 주행 잠금 중에는 `H1Console` 로그로 수집이 이어지는지 확인한다.
4. 정지 후 P단으로 전환한다. 기존 주행 화면의 급가속·급감속 건수·가점을 확인하고 **시험 종료 · 결과 보기**를 누른다.
5. **1km별 가점 보기**에서 사건별 최대 필터 가속도/최저 1초 평균, 브레이크 정보와 실제 반영된 가점을 확인한다. 짧은 시험도 누적 계산을 표시하지만 유효 주행 500m 미만이면 최종 정수 점수는 미확정으로 유지한다.

기존 점수 확정 조건은 유효거리 500m 이상, 속도·제한속도 평가율 90% 이상, 가감속 자료 확보율 90% 이상 및 실제 수신 8Hz 이상이다. 9km는 순위용 유효 기록 조건이며 콘솔 시험 보상과는 무관하다. 급가감속 건당 감점은 없고 해당 구간의 가감속 가점만 제외한다. 과속·미수신·판정 불가 구간도 가점에 영향을 주므로 정상 주행 대비 가점 차이를 전부 급가감속 탓으로 해석하지 않는다.

```bash
adb logcat -s H1Console:D '*:S'
```

기록 표본 수신 중 약 500ms마다 `samples`, `distance`, `accel`, `decel`, `speedBonus`, `smoothBonus`, `total`, `score`, `rate`, `coverage`를 출력한다. `score=pending`은 점수 확정 조건 미충족이며 `limit=…(test) belt=assumed`는 시험 가정이다. 실제 차속 누락·낮은 수신 빈도를 정상 데이터로 보정하지 않는다.

### 자동 테스트와 검증 범위

2026-11-07 확인: 핵심 테스트 93개 유지, `ConsoleInputTest` 3개 통과. 기존 `TurnScoringTest`·`BeltDisqualificationTest` 각 1개도 통과했다. debug APK 빌드·설치 완료, Android lint 오류 0·경고 14. 최초 콘솔 UI 테스트의 상세 문구 비교는 앞쪽 줄바꿈 때문에 실패했으며, 실제 건수·점수 검증은 통과한 상태에서 문구 비교를 수정한 뒤 3개 모두 재통과했다.

새 `ConsoleInputTest` 3개는 합성 차속·원본 시각을 콘솔 세션에 주입하는 결정적 테스트다. 실제 Virtual Console 전달이나 실차 안전성을 검증한 것으로 해석하지 않는다.

- 10Hz·676.5m 입력에서 급가속 1건/급감속 1건, 기존 점수 엔진 결과 일치, 기존 결과·상세 화면 연결, 순위·마일리지·배지·완료 횟수 불변.
- 누락 표본이 1초 감속 창을 끊고, 500m 미만 시험의 가점은 확인하되 최종 점수를 확정하지 않음.
- 시험 취소 후 일반 모드의 내비 목적지·벨트 조건 복구. 주행 중 결과 종료 금지와 시험 중 제한속도 변경 금지도 포함.

`android/`에서 실행:

```bash
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.musaz.h1score.ConsoleInputTest
```

### 실제 Virtual Console 입력 검증

Pleos Connect v2.1.0 / API 34에서 실제 콘솔의 D·ACCEL·BRAKE·P를 조작하고 기존 결과 화면까지 확인했다. 가속·제동 파형을 앱에 합성해서 넣은 시험이 아니다. 내비 제한속도와 벨트는 위에 명시한 시험 가정이며 실차·실제 내비 평가 검증은 아니다.

- 첫 10Hz 요청 시험: 실제 약 6.5Hz, 급가속 2건·급감속 1건. 검출은 되었지만 기존 8Hz 품질 조건 때문에 가감속 가점과 최종 점수가 보류되는 것을 확인했다.
- 콘솔 시험의 구독 요청만 20Hz로 높이고 지원 범위로 제한했다. 일반 모드 요청은 10Hz로 복구하며, 원본 timestamp·누락 처리·기존 8Hz 조건은 유지했다.
- 개선 후 실제 수신 **9.97Hz**, 가감속 자료 확보율 **96.0%**, 주행 약 **590.8m**, **급가속 2건·급감속 1건**을 확인했다. 주행 잠금 중 기록을 유지하고 P단에서 시험 종료 후 기존 결과 화면에 **62점**과 **2 / 1건**, **+0M**이 표시됐다.
- 실제 누적 계산: **60 + 속도 가점 1.4632 + 가감속 가점 0.7191 = 약 62.1824점 → 표시 62점**. 일부 과속·자료 부족 구간도 있어 가점 차이 전체를 급가감속 사건만의 손실로 해석하지 않는다.
- 로컬 검증 증거: `artifacts/screenshots/console-input-result.png`, `artifacts/console-input-live-result.txt` (생성 결과물이므로 Git에 포함하지 않음). 에뮬레이터 로그 시계는 호스트 날짜와 다르다.

화면 자동 클릭이 실패해 사용자가 시작 버튼을 눌렀고, 이후 콘솔 가속·제동·P단 조작과 키보드를 통한 시험 종료 및 결과 확인을 수행했다. 마지막 코드 정리는 빈 기록의 Logcat `score`도 화면과 같이 `pending`으로 표시하는 수정이며, 점수 엔진·검출 결과에는 영향을 주지 않는다.

## 11월 7일 · 급감속 1초 평균 단일 기준 (`longitudinal-v2`)

- 급가속은 기존 중앙값 필터·+2.5m/s²·연속 1초 조건을 유지했다. 급감속만 원본 속도의 직전 1초 평균 ≤−3m/s²로 변경했다. 이전 두 감속 지속 조건은 제거했다.
- Kotlin 핵심 테스트 **93개 통과**, 실패·오류·스킵 0. 새 `RollingDecelerationTest` 15개는 정확한 1초 경계와 보간, 원본 시각, 누락·역순·중복 후 새 창 확보, 짧고 강한 감속, 연속 창 병합, 10km 경계를 검증한다.
- 가점 검증: 확정 창 안의 정속·완만한 증가 구간, 병합 사이 정상 구간, 창 왼쪽 경계의 일부 표본 구간은 가점을 유지한다. 제외 구간 경계와 제한속도 경계가 같은 표본 안에 있는 경우도 거리 적분을 확인했다.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` 성공. lint 오류 0·경고 14.
- 최신 APK를 기존 Pleos API 34 에뮬레이터에 업데이트하고 `TurnScoringTest` **1개 통과**를 확인했다. 가감속·회전 기록이 기존 결과 화면과 상세 내역까지 전달되는 합성 입력 테스트다.
- 이번 변경 전후 `android/app/src/main/` 전체 파일 해시가 동일하다. 화면·리소스·앱 연결 코드 변경 없이 핵심 판정과 부분 구간 가점 배분만 수정했다. 시작 60점·거리별 가점·회전 감점 산식은 유지했다.

실행한 검사:

```bash
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s emulator-5554 shell am instrument --user current -w \
  -e class ai.musaz.h1score.TurnScoringTest \
  ai.musaz.h1score.test/androidx.test.runner.AndroidJUnitRunner
```

실차에서 원시 속도의 이상치·실제 수신 주기·오검출률을 확인한 결과는 아니다. 아래 78개 핵심 테스트·3개 앱 테스트는 직전 v1 구현의 검증 이력이다.

## 11월 7일 · 원본 시각 기반 가감속 변경 (`longitudinal-v1` 이력)

소스 기준은 팀원 브랜치 `feat/driving-score-gps-turn-penalties@a7b3808`이며, `main@fd97232`의 Android 회차·내비·도착 변경을 함께 반영했다. 기존 화면·색상·카드·마스코트 구성을 유지하고 결과와 기존 상세 내역에 사건 수·자료 확보율·실제 수신 Hz를 추가했다.

- 핵심 Kotlin 테스트 **78개 통과**, 실패·오류·스킵 0. 이 중 가감속 검출 관련 21개.
- Pleos API 34 에뮬레이터 테스트 **3개 통과**: 안전벨트 실격, GPS 회전·가감속 자료의 점수/결과 연결, 지도 검색·참가 동의·압축 주행·결과 상세 확인.
- debug APK 빌드·설치·실행 완료. Android lint 오류 0, 경고 14. 기존 API/의존성·화면 방향·백업 설정·KTX 권고 등이 남아 있다.
- 검증 항목: 임계 가속도·지속 시간 경계, 불규칙 표본 간격, 2초 병합, 미확정 짧은 구간, 중앙값 필터, 원본 timestamp, 중복·역순·누락 입력, 브레이크 중복 방지, 제한속도 없는 사건 검출, 저주파 자료 보류, 병합 사이 정상 구간 가점, 10km 이후 사건 제외.
- 300ms 간격의 두 표본만으로 짧은 급감속을 확정하지 않는지 확인했다. 지속적인 250~300ms 간격 입력은 자료 확보율이 100%여도 가감속 가점과 점수 확정을 보류한다. 정상 10Hz·10km 입력은 100점을 유지한다.
- 첫 UI 테스트에서는 신규 설치 권한 창이 첫 화면 검사를 가렸다. 앱 화면을 바꾸지 않고 테스트 시작 전에 필요한 권한을 설정하도록 수정했다.
- 최종 설치본의 GitHub 기존 H1-score 홈 화면을 Android Studio Running Devices에서 직접 확인했다. 이전 별도 `ai.drivescore.demo` 실험 앱은 사용자 요청으로 에뮬레이터에서 제거했다.

검증 명령:

```bash
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.musaz.h1score.BeltDisqualificationTest,ai.musaz.h1score.TurnScoringTest,ai.musaz.h1score.MapChallengeTest
```

증거는 `android/core/build/test-results/test/TEST-*.xml`, `android/app/build/outputs/androidTest-results/connected/debug/TEST-*.xml`, `android/app/build/reports/lint-results-debug.xml`에서 확인할 수 있다. 실제 내비 경로 검증용 `PleosRouteSmokeTest`는 이번 실행 대상에 포함하지 않았다.

단위 테스트 입력과 에뮬레이터 주행 재생은 합성 데이터다. 실차의 10Hz 속도 수신, 제한속도 갱신, GPS 정확도, 노면·차종별 오탐률은 별도로 검증해야 한다. 아래 11월 5~6일의 화면·로그는 이전 버전의 검증 이력이다.

## 이전 자동 검증 · 11월 5~6일

실행: `./gradlew :core:test :app:assembleDebug :app:lintDebug :app:installDebug`

| 검사 | 결과 | 증거 |
| --- | --- | --- |
| Kotlin core 단위 테스트 | 20개, 실패 0 / 오류 0 / 스킵 0 | `artifacts/verification/TEST-*.xml` |
| Android debug APK | 빌드 성공 | `artifacts/verification/build-final.log` |
| Android lint | 오류 0, 경고 11 | `artifacts/verification/lint-results-debug.txt` |
| Pleos 에뮬레이터 설치 | 1대 설치 성공 | build.log, package-installed.txt |
| 설치 버전 | versionCode 2 / 0.2.0-prototype | package-installed.txt |
| 방패 자산 사용 범위 | 신규 BadgeCelebration의 호출 한 곳 | H1App.kt 정적 확인 |

경고에는 최신 target/API·의존성 권고, 고정 가로 방향, 백업 XML 권고, SharedPreferences KTX 스타일, stopService의 ImplicitSamInstance 경고가 있다. 마지막 것은 Intent를 명시한 Android 서비스 종료 호출이므로 listener 인스턴스를 제거하는 코드가 아니다. 경고 0이라고 보고하지 않는다. 컴파일러에는 ArrowForward 아이콘의 AutoMirrored 교체 권고가 남아 있다.

## 테스트한 규칙

- 동의 전 수집 금지, 주행 중 동의 거부, 10km 경계에서 한 번만 시작
- 짧은 이동은 개인 기록, 도착 후 P단 전 결과 잠금, 목적지 변경 시 취소
- 데이터 부족을 만점으로 처리하지 않음, 누락 구간의 커버리지 반영
- 개인 점수 최소 거리, 정지 시간으로 준수 지표를 부풀리지 않음
- 급제동 가속 감점 제외, 동점 슬롯 분배, 기준 미달/소수 그룹 0M
- 무효 기록 순위 제외, 동일 정산 중복 차단, 목적지·일별 지급 한도
- 점수 없음은 중립, 표정 경계 75/90, 배지 중복·무효 기록·TOP3 자격
- 3.2km(192샘플)와 10km(624샘플)에서 시연 표정 세 구간 재현

짧은 주행의 응원 시나리오가 울먹임 점수를 만들던 문제는 실패 테스트로 재현한 뒤 샘플 구성을 수정하고 통과시켰다. 중립 자산이 응원 자산으로 연결되던 UI 분기도 별도로 수정했다.

## 직접 확인한 화면·연결

- Android Studio에서 H1 Pleos 실행 및 앱 홈 표시.
- 새 투명 배경 응원 무사즈, 도로 모티프, 홈 문구가 실제 에뮬레이터에 표시됨.
- Running Devices를 본창 안에서 100%로 확대하고 키보드 포커스가 앱 참가 버튼까지 이동하는 것을 확인.
- Vehicle READ Capability 및 Brake/TurnSignal/Odometer/Seat 응답, AAOS 속도/P단/UX 상태 수신.
- NaviHelper 연결 및 초기 목적지 0m·제한속도 0km/h 콜백. 유효한 실제 경로·제한속도를 받아 점수를 완성한 검증은 아님.

수신 로그는 최종 설치 전에 수집했다. 같은 어댑터 코드의 연결 증거이며 최종 버전의 실주행 증거로 확대하지 않는다. 에뮬레이터 로그 시계와 Mac의 시간대가 다를 수 있다.

## 화면 검수와 남은 실차 입력 검증

11월 6일 잠금 해제 후 키보드로 시연 흐름을 진행했다. 마우스 자동화가 불안정해 Tab/Return을 사용했고 실제 에뮬레이터 화면을 확인했다.

- [x] 참가 예약 → 압축 주행 → 도착 후 결과: 기본 92점, 공동 1위, 80M, 신규 배지 3개 확인
- [x] 결과 마일리지 합계의 잘림을 수정하고 53점 결과에서 +0M 및 내 마일리지 버튼이 모두 보이는 것을 확인
- [ ] 수정 후 새 배지 배너가 함께 있는 결과 화면의 최종 재확인
- [x] 92점 축하 및 53점 울먹임을 실제 결과 화면에서 확인
- [ ] 중간 점수의 응원 결과 화면 직접 확인 (표정 경계와 시연 입력 단위 테스트는 통과)
- [x] 새 배지 열기에서 금색 방패 무사즈와 첫 무사 도착 표시. 결과 기본 화면에는 축하 표정 사용
- [ ] 모든 배지 확인 후 동일 주행 재시연의 UI 중복 방지 확인 (핵심 규칙 단위 테스트는 통과)
- [ ] Virtual Console로 차량 상태를 바꿔 실제 입력의 기록/주차 게이트 검증
- [ ] 실제 내비 목적지·경유지·경로 재탐색·제한속도 갱신 의미 검증

`artifacts/screenshots/badge-earned.png`는 11월 6일 실제 에뮬레이터의 배지 획득 화면이다. 결과 마일리지 잘림 수정 전 캡처이며, 배지 모달 자체는 같은 구현이다.

획득 배지 3개가 컬렉션에 반영되는 것을 확인했다. 컬렉션 마지막 준비 중 항목이 잘리는 문제는 세로 스크롤을 추가해 수정했다. 이 마지막 스크롤 수정은 빌드·린트로 검증했으며 UI를 다시 조작해 확인하지는 않았다. 최종 설치본에서 사용자가 직접 처음부터 실행할 수 있도록 START-HERE.md에 버튼 순서를 제공한다.

저장된 `screenshots/early-home-before-expression-update.png`는 표정 추가 전 초기 실행 캡처다. 최종 화면 캡처가 아니므로 발표용 최신 이미지로 사용하지 않는다. 화면 캡처를 합성해 실행 증거로 넣지 않았다.

## 알려진 제약

1. 참가자 매칭·공유·정산 서버는 없다. 다른 참가자 4명과 M은 시연 데이터다.
2. 기기 로컬 잔액·배지는 보존하지만 현재 세션·최근 보고서·미확인 배지 안내는 프로세스 종료 후 복원하지 않는다.
3. 제한속도 콜백의 실제 갱신 간격과 현재 2.5초 신선도 조건을 추가 검증해야 한다.
4. 짧은 개인 기록에는 현재 배지 발급도 하지 않는다. 원래 기획의 짧은 이동 배지는 후속 조건 설계가 필요하다.
5. 앱 분류·운전자 주의 분산·백그라운드 수집 권한과 시장 심사는 미완료다.
6. 에뮬레이터에서 H1-score 외의 `com.android.systemui` HVAC 예외와 `com.android.car` 오디오 정책 오류가 관찰됐다. 앱 로그와 구분하며, 플랫폼 안정성 검증이 남아 있다. 이 실행에서 H1-score의 FATAL EXCEPTION은 관찰하지 못했지만 최종 설치 버전의 모든 화면이 검증됐다는 의미는 아니다.

## 다음 구현 우선순위

최종 UI 검수 → 유효 내비/속도 시나리오 재현 → 기록 복원 → 서버의 실제 3대 매칭·정산 → 경로 공정성/점수 검증 → 제휴 예산·앱 심사.
