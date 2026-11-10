# 최초 실행 안내 · 무사즈 튜토리얼

사용자 요청: 마일리지 완료 후 소개·개인정보 동의·닉네임 설정·기능별 스포트라이트를 설치 후 한 번 표시하고 실제 시연 영상을 Downloads에 저장한다. 화면 앱 이름은 안운잘부, 음성·마스코트 이름은 무사즈.

## 구현 결정
- 기존 Kotlin/Compose와 SharedPreferences 사용. 새 인증 라이브러리 없음.
- 소개 → 시제품 데이터 이용 안내·선택되지 않은 필수 동의 → 닉네임(기존 규칙: 공백 제외 1~16 코드포인트, 제어문자 금지) → 5개 기능 안내.
- 닉네임 설정 전까지 일반 앱 조작을 차단. 거절은 종료/뒤로 가기 가능. 동의 전 차량 SDK의 안전 상태 확인과 앱의 주행 기록을 구분해서 설명한다.
- 가독성: 흰색 라운드 카드, 남색 제목, 민트 주요 버튼, 기존 무사즈 그림. 튜토리얼은 검정 반투명 배경에 실제 요소의 경계만 뚫고 흰 테두리·짧은 설명 카드 표시.
- 검색, 내 기록, 마일리지, 배지, 마이페이지 순으로 소개. 설명 중 실제 버튼 오조작을 차단한다. 주차가 해제되면 안내를 잠시 멈춘다.
- 필수 동의/닉네임 진행 상태와 최종 완료를 저장. 튜토리얼 완료 또는 건너뛰기 후 다시 실행해도 표시 안 함. 업데이트는 초기화하지 않음. 데이터 삭제/재설치 시 새 설치로 취급.
- SSO는 이번 구현에서 제외: Pleos Account는 공식 소개에 있지만 현재 공개 SDK 목록·가이드에서 제3자 앱 로그인 토큰/API를 확인하지 못함. 연결 안 된 로그인 버튼을 만들지 않음.
- 개인정보 문구는 현재 시제품 동작을 설명하는 안내. 운영주체·연락처·서비스 제공자의 보유기간 등 운영 정보가 미정이므로 출시용 처리방침 완성이라고 표현하지 않음.

## 검증
동의 없을 때 다음 불가, 빈 닉네임·길이 초과 거부, 정상 완료 후 재실행 미노출, 중간 종료 시 이어서, 튜토리얼 건너뛰기 저장, 주행 시 입력 차단. 기존 사용자 데이터 삭제 없이 별도 테스트 저장소 사용. 영상은 실제 에뮬레이터 녹화, 합성 시연으로 속이지 않음.

## 참고 자료 (2026-11-10 확인)
- [Apple Onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding): 짧은 안내, 기능 근처 설명, 선택적 튜토리얼과 재노출 방지.
- [Material Feature discovery](https://m1.material.io/growth-communications/feature-discovery.html): 타깃 강조와 주변 설명, 큰 화면에서 카드 기준 배치.
- [Pleos SDK 확장 안내](https://developers.pleos.ai/playground/discover/resources): 클라우드 프로필 개인화는 SDK 지원 확대 계획.
- [Pleos Connect](https://pleos.ai/playground/discover/pleos-connect): Pleos Account 소개. 이것만으로 외부 앱 SSO 지원이라고 판단하지 않음.
- [현재 SDK 릴리스](https://document.pleos.ai/en/docs/pleos-only/release-note/sdk-release-note): Vehicle/NaviHelper/TTS/STT/FusedLocation.
