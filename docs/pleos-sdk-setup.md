# Pleos SDK 연결 정보

2026-11-01 확인. 출처는 항목별로 표시한다.
- **[AAR]** Maven 저장소의 실제 AAR/POM을 `javap`로 확인
- **[문서]** document.pleos.ai 공식 문서
- **[미확인]** 에뮬레이터에서 직접 확인해야 함

## Gradle

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
```

```kotlin
// app/build.gradle.kts
implementation("ai.pleos.playground:Vehicle:2.3.2")      // 문서의 2.3.1은 저장소에 없음
implementation("ai.pleos.playground:NaviHelper:3.0.5")
implementation("ai.pleos.playground:TextToSpeech:3.3.1")
implementation("ai.pleos.playground:SpeechToText:3.3.1")
```

- 저장소의 Vehicle 버전은 `2.0.3`, `2.3.2`뿐이다. 문서와 CLAUDE.md의 `2.3.1`은 받을 수 없다(HTTP 404). [AAR]
- Vehicle, NaviHelper는 Kotlin stdlib 1.9.25에 의존한다. [AAR]

## Vehicle SDK [AAR]

`Vehicle(context)` → `initialize()` → `getXxx()` → 사용 후 `release()`. 비동기 호출이 끝나기 전에 `release()`하면 안 된다. [문서]

| 신호 | 도메인 | 조회 | 구독 / 콜백 | 값 | 권한 |
|---|---|---|---|---|---|
| 조향각 | `getSteeringWheel()` | `getCurrentSteeringWheelAngle` | `registerSteeringWheelAngle` / `onSteeringWheelAngleUpdated` | `Float?` | `pleos.car.permission.CAR_DRIVE` |
| 깜빡이 | `getTurnSignal()` | `getCurrentTurnSignalState` | `registerTurnSignalState` / `onTurnSignalStateUpdated` | `TurnSignalValue`: `OFF`, `LEFT`, `RIGHT` | `pleos.car.permission.CAR_LIGHTS` |
| 브레이크 | `getBrake()` | `getCurrentBrakePedalStatus` | `registerBrakePedalStatus` / `onBrakePedalStatusUpdated` | **`Boolean?` (밟음 여부만)** | `pleos.car.permission.CAR_DRIVE` |
| 기어 | `getGear()` | `getCurrentGearSelectionState` | `registerGearSelection` / `onSelected` | `VehicleGear`: `GEAR_PARK`, `GEAR_DRIVE`, `GEAR_REVERSE`, `GEAR_NEUTRAL` 등 | `pleos.car.permission.CAR_DRIVE` |

지원 여부: `checkSteeringWheelAngleCapabilities`, `checkTurnSignalStateCapabilities`, `checkBrakePedalStatusCapabilities`. Gear에는 capability 메서드가 없다.

에뮬레이터 실측(2026-11-01): 조향각 단위는 도, **부호는 콘솔 값과 반대**(콘솔 +30 → SDK -30). 콘솔에서 오른쪽으로 끌면 값이 커지므로 SDK는 오른쪽이 음수다. 실차 확인 필요.

**[미확인]** 브레이크 `Boolean`이 Virtual Console의 몇 %에서 바뀌는지, 콜백 주기.

## 차속: AAOS [문서]

`CarPropertyManager`의 `VehiclePropertyIds.PERF_VEHICLE_SPEED`(m/s). 권한은 `android.car.permission.CAR_SPEED`이며 위험 권한이라 런타임 요청이 필요하다.
Virtual Console의 ACCEL 페달(`PUT /vehicle/controls?accelerator=30`, 기어 D)로 차속이 오르고 이 값이 `PERF_VEHICLE_SPEED`로 앱에 들어온다(2026-11-01 실측: 라이브 모드 8초 126m). `speed=` 파라미터는 값을 고정하지 않는다(40 지정 후 47→77km/h).

## NaviHelper 3.0.5 [AAR]

입력: `initialize()`, `addListener()`, 조회 메서드 `getTBTInfo()`, `getCurrentLocationInfo()`, `getDestinationInfo()`, `requestReRoute()`, `addWaypoint()`.

| 콜백 | 데이터 | 필드 |
|---|---|---|
| `onTBTInfo` | `List<TBTInfo>` | `type: TurnType`, `distance: Double`, `description` |
| `onCameraAlert` | `List<CameraAlert>` | `id`, `type: CameraType`, `status`, `speedLimit`, `remainDistance`, `sectionAvrSpeed` |
| `onCautionPointInfo` | `List<CautionPoint>` | `id`, `type: CautionPointType`, `remainDistance` |
| `onCurrentLocationInfo` | `CurrentLocationInfo` | `latitude`, `longitude`, `heading`, `address`, `routeId`, `currentRouteIndex` |

- **안내 지점(TBT·카메라·주의 지점)에는 좌표가 없고 남은 거리만 있다.**
- 공개 메서드 중 경로 좌표선을 주는 API는 찾지 못했다.
- `CautionPointType`에 커브 항목은 없다.
- `onCurrentRoadSpeedLimit(int)`은 있으나 일반 도로 제한속도 평가는 SPEC 7의 제외 항목이다.
- `CameraType`: `SPEED_CAMERA_FIX`, `SPEED_CAMERA_MOV`, `SPEED_CAMERA_SECTION_START`, `SECTION_SPEED_ALERT`, `SPEED_CAMERA_SECTION_END` 외.
- `TurnType`: `TURN_LEFT`, `TURN_RIGHT`, `TURN_SHARP_L/R`, `TURN_KEEP_L/R`, `TURN_UTURN`, `TURN_ARRIVE` 외.

권한: `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP` 중 쓰는 API에 필요한 것만 선언한다. [문서]

**[미확인]** `TBTInfo.distance`의 기준(현재 위치로부터인지)과 단위, 출발 전에 전체 경로의 TBT·카메라가 오는지, 에뮬레이터 내비 앱이 이 콜백들을 실제로 보내는지.

## TTS / STT 3.3.1 [문서]

- 모드: `HYBRID`(기본), `SERVER`, `ON_DEVICE`. `ON_DEVICE`만 쓰면 Client ID/Secret 등록과 네트워크가 필요 없다.
- 순서: 생성 → `initialize(listener)` → 리스너 등록 → (`setLocale`) → `speak` / `request`.
- 두 AAR 모두 `android.permission.INTERNET`을 매니페스트에 포함한다. [AAR]

**[미확인]** 에뮬레이터에서 `ON_DEVICE` 한국어 동작 여부.

## 에뮬레이터 [문서]

SDK 업데이트 사이트 "Pleos Connect System Image":
- Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
- Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`

Device Manager → Automotive → Pleos Connect v2.1.0, API 34, Cold boot, RAM 4GB.

Virtual Console 접속: `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/`. 페이지를 열어 둬야 활성화된다(1초마다 하트비트). 닫으면 `PUT /vehicle/...` 호출이 `emulator_inactive`를 반환한다.
API(시연 자동화에 사용 가능): `PUT /vehicle/gear?gear=Drive`, `PUT /vehicle/controls?steering=30&brake=100`, `PUT /vehicle/turn-signal?mode=BlinkRight`. 상태는 `GET /vehicle/state`.

Virtual Console 조작 범위: 조향 문서상 -540~540°, 카탈로그 실측 -470~500°(1° 단위), 깜빡이(Off, 좌·우 Blink, 좌·우 Flash), 브레이크 0~100%, 기어 P/R/N/D, 가속 페달. 위치 카드는 없다.
