# 시연용 주행 시나리오 형식 (REPLAY_FORMAT)

Virtual Console은 차속과 위치를 조작할 수 없으므로, 시나리오 JSON이 이 값을 시간 순으로 공급한다.
위치: `app/src/main/assets/scenarios/<이름>.json`

```json
{
  "name": "출근 코스 (시연용)",
  "destination": { "name": "회사", "lat": 37.5665, "lng": 126.9780 },
  "playbackSpeed": 1.0,
  "samples": [
    { "t": 0.0, "speedKph": 0,  "lat": 37.5900, "lng": 126.9700, "gear": "P" },
    { "t": 2.0, "speedKph": 0,  "lat": 37.5900, "lng": 126.9700, "gear": "D" },
    { "t": 5.0, "speedKph": 30, "lat": 37.5895, "lng": 126.9705,
      "steeringDeg": 0, "turnSignal": "OFF", "brakePct": 0 }
  ],
  "naviPoints": [
    { "id": "turn-1", "type": "TURN_RIGHT", "lat": 37.5880, "lng": 126.9720 },
    { "id": "cam-1",  "type": "SPEED_CAMERA", "lat": 37.5850, "lng": 126.9750, "speedLimitKph": 50 },
    { "id": "sec-1",  "type": "SECTION_START", "lat": 37.5800, "lng": 126.9760, "speedLimitKph": 80 },
    { "id": "sec-1e", "type": "SECTION_END",   "lat": 37.5750, "lng": 126.9770 }
  ],
  "scriptedEvents": [
    { "t": 40.0, "note": "여기서 Virtual Console로 깜빡이 없이 조향 30° → 복귀" },
    { "t": 70.0, "note": "직선 구간, 조향 ±15° 흔들기" }
  ]
}
```

## 필드 규칙

- `t`: 시작부터 초 단위. 샘플 사이 값은 선형 보간한다.
- `gear`: `P`, `R`, `N`, `D`. 마지막 샘플은 반드시 `P` (도착 → 리플레이 시작).
- `steeringDeg`, `turnSignal`(`OFF`/`LEFT`/`RIGHT`, `HAZARD`는 `OFF`로 읽음), `brakePct`(`> 0`이면 페달 ON으로 변환): **선택**.
  - 재생 모드: 시나리오 값을 사용.
  - 하이브리드 모드: 이 세 값은 무시하고 Virtual Console 실시간 값을 사용.
- `naviPoints.type`: `TURN_LEFT`, `TURN_RIGHT`, `U_TURN`, `SPEED_CAMERA`, `SECTION_START`, `SECTION_END`, `DESTINATION`.
  실제 NaviHelper의 `CautionPointType`/`RoutePointType` 값이 확인되면 매핑 테이블을 `ReplayNaviSource`에 둔다.
- `scriptedEvents`: 시연자 안내. 화면에 띄우지 않고 logcat `DemoCue` 태그로 출력한다. 채점에는 쓰지 않는다.
- 하이브리드 시연의 급정지는 브레이크로 차속이 줄지 않으므로, 샘플에 급감속 구간을 미리 넣고 그 시점에 브레이크를 밟는다.
- 안내 지점은 재생 시 경로 누적거리로 변환되어 채점에 쓰인다.
- `playbackSpeed`: 시연 영상 길이를 맞추기 위한 배속.

## 좌표

시연 경로는 실제 서울 도로 좌표를 대략 따라가되 정확할 필요는 없다. 리플레이 주행선 모양이 "그림"처럼 보이도록 회전이 4~6번 있는 경로가 좋다.
