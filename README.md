# IVECORE

드라이브의 "이브"와 스코어의 "코어"를 합친 이름이다. 팀: Bear (개인)

운전을 마치고 시동을 끄기 전, 오늘의 주행을 하이라이트로 돌려보는 차량 전용 안전운전 게임 앱.
현대자동차그룹 차량용 앱 개발 공모전(Pleos App Market) 출품작.

## 실행 방법

요구 사항: Android Studio, JDK 17 이상(Android Studio 내장 JBR 가능), Android SDK 34, Pleos Connect Emulator v2.1.0.

```bash
./gradlew :scoring:test          # 채점 로직 단위 테스트
./gradlew :app:assembleDebug
./gradlew :app:installDebug      # 실행 중인 에뮬레이터에 설치
```

## 테스트 환경

Pleos Connect Emulator v2.1.0 (API 34, Automotive, Cold boot, RAM 4GB), 2560×1440 가로.

## APK 위치

`apk/Bear_IVECORE_release.apk`. 1차 제출 시 추가한다.

## 문서

- 기능 명세: `docs/SPEC.md`
- 설계: `docs/DESIGN.md`
- 작업 계획: `docs/TASKS.md`
- SDK 연결 정보: `docs/pleos-sdk-setup.md`
