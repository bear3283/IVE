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

앱에 뜨는 패키지는 `com.bear.ivecore`이다.

release 빌드는 저장소 루트의 `keystore.properties`(커밋 금지)로 서명한다. 항목은 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

```bash
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk apk/Bear_IVECORE_release.apk
```

## 시연 방법

Virtual Console은 `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/`을 열어 둔다.

1. 시작 전 콘솔을 P, 조향 0, 브레이크 0, 깜빡이 끔으로 맞춘다.
2. 설정 화면에서 "설정" 제목을 길게 눌러 시연 설정을 연 뒤 신호 소스 "하이브리드", 시나리오 "시연 2분"을 고른다. 차속·위치·내비 안내는 시나리오가, 나머지는 콘솔이 준다.
3. 기어 D로 출발한다. 18초에 조향 +30° 후 복귀, 33초에 ±15° 흔들기, 50초 깜빡이 오른쪽, 57초 조향 200°, 88초 브레이크 100%, 94.5초 기어 P.
4. P로 바꾸면 리플레이, 결과 화면, 음성 디브리핑이 이어진다.

두 번째 주행부터 단골 코스 브리핑과 지난 감점 구간 예고가 나온다.

## 채점

100점에서 사건별 점수를 뺀다(깜빡이 미사용 8, 30m 미만 4, 급제동 6 등). 긴 주행은 거리 계수로 줄이고 항목마다 상한을 둔다. 등급은 점수 기준을 바꾸지 않고 추가 과제만 정한다.

## 평가하지 않는 것

신호 준수, 보행자 보호, 차간 거리는 평가하지 않는다. 앞차 정보가 없어 정당한 회피 제동도 급제동으로 잡힐 수 있다. 채점 수치는 실차 튜닝 전 초기값이다(근거: `docs/DESIGN.md` 3.4). 100m 미만 주행은 저장하지 않는다. 기록은 기기에만 저장한다.

## 테스트 환경

Pleos Connect Emulator v2.1.0 (API 34, Automotive, Cold boot, RAM 4GB), 2560×1440 가로.

## APK 위치

`apk/Bear_IVECORE_release.apk`

## 문서

- 기능 명세: `docs/SPEC.md`
- 설계: `docs/DESIGN.md`
- 작업 계획: `docs/TASKS.md`
- SDK 연결 정보: `docs/pleos-sdk-setup.md`
