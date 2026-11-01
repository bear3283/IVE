# CLAUDE.md — 운전점수 게임 앱 IVECORE (팀 Bear)

현대자동차그룹 차량용 앱 개발 공모전(Pleos App Market) 출품작.
운전을 마치고 시동을 끄기 전, 오늘의 주행을 하이라이트로 돌려보는 **차량 전용 안전운전 게임 앱**이다.
폰 앱이 추정할 수 없는 실차 신호(조향각, 방향지시등, 브레이크 페달, 기어)로 평가한다.

- 전체 기능 명세: `docs/SPEC.md`
- 작업 순서와 완료 기준: `docs/TASKS.md`
- 시연용 주행 시나리오 형식: `docs/REPLAY_FORMAT.md`
- 설계와 결정 사항: `docs/DESIGN.md` (SPEC과 다르면 DESIGN 1절이 우선)

## 마감

- **2026-11-15(일)**: 1차 접수. 기획서 + 시연 영상(에뮬레이터 화면 녹화) + GitHub(소스 + release APK)
- **2026-12-03~04**: 2차 PT. 실제 동작하는 앱 필수
- APK 파일명: `Bear_IVECORE_release.apk`, 저장소의 `apk/` 폴더에 업로드

## 플랫폼과 하드 제약 (반드시 지킬 것)

- Pleos Connect = Android Automotive OS(AAOS) 기반 인포테인먼트. **Android 앱(Kotlin)** 이다.
- `minSdk 28` 필수(차량 보안 요구사항), `compileSdk/targetSdk 34`, Gradle 8.0+.
- 에뮬레이터: Pleos Connect Emulator v2.1.0 (API 34 이미지, Automotive 폼팩터, **Cold boot**, RAM 4GB).
- **차량 하드웨어 직접 접근 금지.** 차량 데이터는 Pleos Connect SDK 또는 AAOS 표준 Car API로만 받는다.
- 차속은 **AAOS `CarPropertyManager`** 로, 기어는 Vehicle SDK `Gear`로 받는다. Pleos Vehicle SDK의 `VehicleSpeedListener`는 삭제됨. SDK 상세: `docs/pleos-sdk-setup.md`
- **주행 중 UI 금지:** `CarUxRestrictions`를 따른다. 주행 중(D단)에는 점수·미션·애니메이션을 띄우지 않는다. 리플레이는 P단에서만, D단 전환 시 즉시 정지.
- 음성은 내비 안내보다 후순위. 오디오 포커스(`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`)를 요청하고 실패하면 대기.
- 네트워크 없이 기록·채점이 모두 기기에서 끝나야 한다.
- LLM SDK는 지원 종료. AI 대화형 기능을 만들지 말 것.

### SDK 사용 가능 상태 (2026-10-19 문서 기준)

| SDK | 상태 | 비고 |
|---|---|---|
| Vehicle SDK (상태 조회) v2.3.2 (저장소 기준, 문서의 2.3.1은 없음) | 사용 가능 | 도메인별 `checkXXXCapabilities`로 지원 여부 확인 |
| NaviHelper v3.0.5 | 사용 가능 | 좌표 생성자 순서 (latitude, longitude) 로 바뀜 |
| TextToSpeech / SpeechToText v3.3.1 | **전제로 사용** | Client ID/Secret 필요. 막히면 Android 기본 TTS·SpeechRecognizer로 대체 |
| FusedLocation v1.1.2 | 준비 중 | 그 전까지 NaviHelper 현재 위치 정보로 대체 |
| ADAS, Vehicle Control | 준비 중 | 사용하지 않음 |

## 아키텍처

모든 차량·내비·음성 의존성은 **인터페이스 뒤에 숨긴다.** 실차/에뮬레이터 구현과 시연용 재생(Replay) 구현을 갈아끼울 수 있어야 한다.
Virtual Console에는 위치·내비 조작이 없고 차속은 가속 페달로만 움직여 시나리오와 맞추기 어려우므로, 시연은 Replay 소스가 차속·위치·내비 이벤트를 공급하고
깜빡이·브레이크·조향·기어는 Virtual Console로 실시간 조작하는 **하이브리드 모드**로 한다.

```
:scoring   순수 Kotlin(JVM) 모듈. Android 의존성 금지. 동작 분류, 채점, 등급, 코스 매칭.
           → 단위 테스트로 대부분의 로직을 검증한다.
:app       Android(AAOS) 앱. Compose UI, Hilt, Room, SDK 어댑터.
  data/signal   VehicleSignalSource (interface)
                ├ PleosVehicleSignalSource   (Vehicle SDK: 조향, 깜빡이, 브레이크)
                ├ CarPropertySignalSource    (AAOS: 차속, 기어)
                ├ ReplaySignalSource         (JSON 시나리오 재생)
                └ HybridSignalSource         (위 셋 조합, 시연 모드)
  data/navi     NaviSource (interface) ├ PleosNaviSource ├ ReplayNaviSource
  data/voice    VoiceOutput / VoiceInput (interface) ├ Pleos 구현 ├ Android 기본 구현
  data/db       Room: Trip, TripEvent, TrackPoint, Course, DriverProfile
  domain        TripRecorder(F-03), 세션 상태머신(출발 전/주행 중/도착 후)
  ui            briefing / result / replay / archive / settings
```

## 코드 규칙

- Kotlin, Coroutines + Flow, Jetpack Compose, Hilt, Room. 새 라이브러리는 필요할 때만, 이유를 커밋 메시지에 적는다.
- `:scoring`의 모든 임계값(각도, 시간, 배점)은 하드코딩하지 말고 `ScoringConfig` / `GradeRules` 데이터 클래스로 주입한다. 등급·캘리브레이션이 이 값을 바꾼다.
- UI 문자열은 한국어, `strings.xml`에 둔다. 코드 식별자는 영어.
- 터치 타깃은 크게(최소 76dp 권장), 고대비, 텍스트 최소화. Pleos Connect UI 디자인 가이드를 따른다.
- 화면 크기는 Devbox 기준 2560×1440 가로. 폼팩터 테스트로 다른 해상도도 확인.
- 작업 단위마다 커밋. 커밋 메시지는 한국어 한 줄 + 필요 시 본문.

## 명령어

```bash
./gradlew :scoring:test            # 채점 로직 단위 테스트 (가장 자주 실행)
./gradlew :app:testDebugUnitTest   # 시나리오·세션·기록 형식 단위 테스트
./gradlew :app:assembleDebug
./gradlew :app:installDebug        # 실행 중인 Pleos 에뮬레이터에 설치
./gradlew :app:assembleRelease     # 제출용 APK
```

## 모르는 것 (추측하지 말고 확인하거나 사용자에게 물을 것)

- `docs/pleos-sdk-setup.md`의 [미확인] 항목(조향 단위·부호, 콜백 주기, 내비 콜백 동작 등). 에뮬레이터에서 실측한다.
- 필요한 권한의 심사 통과 여부. 승인되지 않은 권한 선언은 앱 마켓 심사 탈락 사유이므로 쓰는 것만 선언한다.

## 참고 문서

- 개발자 가이드: https://document.pleos.ai/en/docs/pleos-only/getting-started
- 에뮬레이터 설치: https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- Virtual Console: https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide
- Vehicle SDK 튜토리얼: https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk
- SDK 릴리즈 노트: https://document.pleos.ai/en/docs/pleos-only/release-note/sdk-release-note
- 주행 안전 설계: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- AAOS VehiclePropertyIds: https://developer.android.com/reference/android/car/VehiclePropertyIds
