# Android 개발 환경

1. Android Studio에서 저장소의 `android/` 폴더를 엽니다. Gradle JDK는 검증에 사용한 JDK 21을 선택하고, Android SDK Platform 35를 설치합니다.
2. Android Studio가 생성한 `android/local.properties`에 카카오맵 설정을 추가합니다. 파일이 없다면 [설정 예시](../../android/local.properties.example)를 복사하고 `sdk.dir`을 본인의 설치 경로로 수정합니다.
3. Kakao Developers에서 팀이 등록한 Android 앱의 **네이티브 앱 키**를 받아 `KAKAO_NATIVE_APP_KEY`에 입력합니다. 앱의 Android 설정에 패키지명 `ai.musaz.h1score`와 각 개발자가 실제로 쓰는 서명 키의 키 해시를 등록합니다. 디버그와 릴리스 서명 키가 다르면 해시도 다릅니다.
4. Gradle Sync 후 Pleos Connect Emulator를 대상으로 앱을 다시 빌드합니다. 키가 비어 있으면 시연 지도가 표시됩니다.

기존 파일에 아래 항목을 추가합니다. 실제 값은 팀 앱 관리자에게 요청합니다.

```properties
KAKAO_NATIVE_APP_KEY=
```

`local.properties`는 머신별 로컬 설정으로 Git에서 제외합니다. 네이티브 앱 키는 앱 빌드에 포함되므로 카카오 콘솔의 Android 패키지명과 키 해시 등록도 함께 관리합니다.

- [Kakao Developers 앱 관리](https://developers.kakao.com/console/app)
- [카카오맵 Android SDK 시작하기](https://apis.map.kakao.com/android_v2/docs/getting-started/quickstart/)
- [Pleos Connect Virtual Console](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
- [팀 Notion · 카카오맵 키](https://app.notion.com/p/41031a203ed3807a880de56724c6e9f3)
