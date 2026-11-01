# 작업 계획 (TASKS)

마감: **1차 2026-11-15(일)** 시연 영상 + GitHub(release APK) / **2차 2026-12-03** 실제 동작 앱.
각 단계는 "완료 기준"을 모두 만족해야 끝난 것으로 본다. 단계가 끝날 때마다 커밋하고 이 파일의 체크박스를 갱신한다.

## M0. 프로젝트 뼈대 (11/1~11/2)

- [ ] Gradle 멀티모듈: `:app`(Android, AAOS), `:scoring`(순수 Kotlin JVM)
- [ ] `:app` 설정: minSdk 28, compile/targetSdk 34, Compose, Hilt, Room, Coroutines
- [ ] `AndroidManifest.xml`: `android.hardware.type.automotive` uses-feature, `distractionOptimized` 메타데이터는 주행 중에도 떠 있어야 하는 화면에만
- [ ] Pleos SDK 의존성: 저장소·좌표를 문서에서 확인. **확인 안 되면 멈추지 말고** 어댑터를 `TODO`로 두고 M1로 진행, 사용자에게 보고
- [ ] `.gitignore`, README(앱 이름, 한 줄 소개, 실행 방법, 테스트 환경, APK 위치 — 공모전 필수 항목)

완료 기준: `./gradlew :scoring:test`와 `./gradlew :app:assembleDebug`가 성공한다.

## M1. 채점 엔진 `:scoring` (11/2~11/5) ← 가장 중요, 테스트 우선

- [ ] 모델: `SignalSample(t, odoM, speedKph, steeringDeg, turnSignal, brakeOn, gear, lat, lng)`, `RouteMarker(id, type, odoM, speedLimitKph?)`, `DriveEvent`, `Penalty`
- [ ] `ScoringConfig`, `GradeRules` (SPEC 3.2, 3.3의 값을 기본값으로)
- [ ] `ManeuverClassifier`: 샘플 스트림 → 직선/커브/차선변경/회전/유턴 구간 (SPEC 3.1)
- [ ] `TripScorer`: 구간 + 내비 지점 → 항목별 점수, 감점 목록(시각, 위치, 이유, 당시 값), 총점
- [ ] 난이도 보정, 저속 악용 방지, 미지원 신호 배점 재분배
- [ ] `GradeEngine`: 주행 기록 목록 → 현재 등급, 다음 등급 조건
- [ ] `CourseMatcher`: 목적지 + 회전 지점 순서 유사도로 같은 코스 판정 (F-01)
- [ ] `IntentMatcher`: STT 문장 → 의도 (SPEC 4)

완료 기준 (단위 테스트로 증명):
- 깜빡이 없이 30° 꺾었다 되돌림 → 차선변경 + "깜빡이 미사용" 감점
- 깜빡이 3초 전 + 200° 저속 → 우회전, 감점 없음 (숙련 등급)
- 직선에서 ±15° 1.5초 → 숙련 등급 직선 안정성 감점, 초보 등급은 감점 없음
- 페달 ON + 감속도가 등급 기준 초과 → 급정지 감점
- 같은 운전이 등급이 오르면 점수가 내려감
- 커브(같은 방향 20° 10초 유지, 깜빡이 없음)는 감점 없음
- "오늘은 아내가 운전해" → SWITCH_DRIVER

## M2. 신호 소스와 주행 기록 (11/5~11/8)

- [ ] `VehicleSignalSource` 인터페이스 (Flow)
- [ ] `ReplaySignalSource` + `ReplayNaviSource`: `assets/scenarios/*.json` 재생 (형식: `docs/REPLAY_FORMAT.md`)
- [ ] 시연용 시나리오 2개: `commute.json`(출근, 약 10분 압축), `demo_short.json`(2분, 시연 영상용)
- [ ] `PleosVehicleSignalSource`(조향·깜빡이·브레이크), `CarPropertySignalSource`(차속·기어)
- [ ] `HybridSignalSource`: 차속·위치·내비는 Replay, 나머지는 실시간(Virtual Console)
- [ ] 설정 화면에 소스 선택: 실차 / 재생 / 하이브리드
- [ ] `TripRecorder`(F-03) + 세션 상태머신 + Room 저장

완료 기준: 에뮬레이터에서 하이브리드 모드로 Virtual Console 깜빡이·조향을 바꾸면 이벤트 로그에 기록되고, P 진입 시 Trip이 저장된다.

## M3. 도착 후 화면 (11/8~11/12) ← 시연 영상의 핵심

- [ ] 결과 화면 F-09: 총점 카운트업, 항목별 별점, 배지, 지난번 대비
- [ ] 하이라이트 리플레이 F-08: 지도 없는 주행선(Canvas), 구간 색, 정상 구간 고속·감점 구간 1배속, 데이터 오버레이(조향 게이지, 깜빡이 타임라인, 브레이크 그래프, 속도)
- [ ] `CarUxRestrictions` 연동: 주행 중 진입 차단, D 전환 시 즉시 정지

완료 기준: `demo_short.json` 재생 후 P 진입 → 리플레이 → 결과 화면이 끊김 없이 이어진다. 2560×1440에서 레이아웃이 깨지지 않는다.

## M4. 출발 전 브리핑과 음성 (11/12~11/14)

- [ ] 출발 전 화면 F-02: 경로 위 미션 지점, 지난 점수/최고 기록/지난 감점 지점
- [ ] `VoiceOutput`: Pleos TTS 구현 + Android TTS 대체 구현, 오디오 포커스, 주행 중 한 문장 제한
- [ ] TTS 트리거: 출발 브리핑, 지난 감점 지점 300m 전(F-06), 도착 디브리핑(F-10)
- [ ] `VoiceInput`: 정차 중 마이크 버튼, 질문 직후 5초 듣기, `IntentMatcher` 연결 (F-14)

완료 기준: 시연 흐름(아래)의 음성 단계가 모두 동작한다. TTS/STT SDK가 막혀 있으면 Android 대체로 동작한다.

## M5. 1차 제출 (11/14~11/15)

- [ ] 시연 영상 흐름 리허설 (아래 9단계)
- [ ] release 서명 APK 빌드 → `apk/팀명_앱이름_release.apk`
- [ ] README 최종 점검
- [ ] (사용자) 화면 녹화, 기획서 작성, 이메일 제출

### 시연 영상 흐름 (약 2분)
0. (준비) 시연 프로필을 숙련 등급으로 시드
1. 목적지 설정 → 경로 위 미션 지점 + 음성 브리핑
2. 기어 D → 화면 꺼짐, 기록 시작
3. Virtual Console: 깜빡이 없이 조향 30° 후 복귀 → 깜빡이 없는 차선변경
4. 직선 구간 조향 ±15° 흔들기 → 직선 안정성 감점
5. 깜빡이 켜고 3초 뒤 조향 200° → 정상 우회전
6. 시나리오 급감속 구간에서 브레이크 100% → 급정지 감점
7. 기어 P → 하이라이트 리플레이 (정상 구간 빠르게, 감점 구간 느리게)
8. 결과 화면 + 음성 디브리핑
9. "왜 감점됐어?" → 음성 응답

## 이후 (2차 PT 전, 12/3까지)

- [ ] F-01 코스 인식 실데이터 연결, F-06 실거리 기반 예고
- [ ] F-11 아카이브, F-12 등급·시즌, F-13 캘리브레이션, F-07 집중 저하
- [ ] 폼팩터 테스트, 성능 점검, 실차 신호 기준값 튜닝
