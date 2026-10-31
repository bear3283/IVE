# Claude Code 시작 방법

## 1. 준비 (직접)

1. 이 폴더(`drive-score-kit`)의 내용을 새 프로젝트 폴더에 복사하고 `git init`.
2. Android Studio 최신 버전 설치 → Pleos Connect Emulator v2.1.0 설치 (CLAUDE.md의 에뮬레이터 링크).
3. Pleos Playground 가입 → 프로젝트 생성 → SDK 탭에서 TTS/STT 상태 확인, Client ID/Secret 메모.
4. Pleos SDK의 Gradle 의존성 안내(샘플 프로젝트의 `build.gradle`/`settings.gradle`)를 찾으면 `docs/pleos-sdk-setup.md`로 저장해 두세요. Claude Code가 그 파일을 보고 연결합니다.
5. 프로젝트 폴더에서 `claude` 실행.

## 2. 첫 프롬프트 (그대로 붙여넣기)

```
CLAUDE.md와 docs/ 폴더의 SPEC.md, TASKS.md, REPLAY_FORMAT.md를 모두 읽어줘.
그다음 TASKS.md의 M0와 M1을 진행해줘.

- M1(채점 엔진)은 테스트를 먼저 쓰고 구현해. TASKS.md의 M1 완료 기준 7개는 반드시 테스트로 만들어.
- Pleos SDK 의존성 좌표를 모르면 추측하지 말고, 어댑터는 TODO로 두고 진행한 뒤 나에게 무엇이 필요한지 알려줘.
- 단계가 끝날 때마다 커밋하고 TASKS.md 체크박스를 갱신해.
- 시작 전에 계획을 짧게 보여주고, 애매한 점이 있으면 먼저 물어봐.
```

## 3. 이후 단계 프롬프트

- M2: `TASKS.md의 M2를 진행해줘. 시나리오 JSON 2개(commute, demo_short)도 만들어줘.`
- M3: `M3를 진행해줘. 리플레이 화면이 시연 영상의 핵심이니 연출(배속 전환, 데이터 오버레이)에 신경 써줘.`
- M4: `M4를 진행해줘. TTS/STT SDK가 안 되면 Android 기본 구현으로 대체해.`
- M5: `release APK를 빌드하고 apk/ 폴더에 넣어줘. README도 공모전 필수 항목대로 점검해줘.`

## 팁

- 채점 기준이 이상하면 코드가 아니라 `ScoringConfig` 값만 바꾸면 되도록 설계되어 있습니다.
- 에뮬레이터 실행은 직접 하시고, 설치는 `./gradlew :app:installDebug`를 Claude Code에 맡기면 됩니다.
- 막히면 `/plan` 모드로 계획부터 다시 세우게 하세요.
