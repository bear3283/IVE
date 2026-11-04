#!/usr/bin/env python3
"""시연 시나리오 JSON 생성기. 실행: python3 tools/gen_scenarios.py"""
import json, math, os

OUT = os.path.join(os.path.dirname(__file__), "..", "app/src/main/assets/scenarios")
DT = 0.5
M_PER_LAT = 111_320.0


class Route:
    def __init__(self, name, dest, lat, lng, heading):
        self.name, self.dest = name, dest
        self.lat, self.lng, self.heading = lat, lng, heading
        self.t, self.v = 0.0, 0.0
        self.samples, self.points, self.cues = [], [], []
        self.signal = "OFF"

    def _emit(self, steer=0.0, brake=0, gear=None):
        s = {"t": round(self.t, 2), "speedKph": round(self.v, 1),
             "lat": round(self.lat, 6), "lng": round(self.lng, 6),
             "steeringDeg": round(steer, 1), "turnSignal": self.signal, "brakePct": brake}
        if gear:
            s["gear"] = gear
        self.samples.append(s)

    def _advance(self, dt):
        d = self.v / 3.6 * dt
        h = math.radians(self.heading)
        self.lat += d * math.cos(h) / M_PER_LAT
        self.lng += d * math.sin(h) / (M_PER_LAT * math.cos(math.radians(self.lat)))
        self.t += dt

    def park(self, sec, gear="P"):
        self.v = 0.0
        n = round(sec / DT)
        for i in range(n):
            self._emit(gear=gear if i == 0 else None)
            self._advance(DT)

    def drive(self, sec, to=None, steer=None, turn=0.0, brake=0):
        """sec 동안 차속을 to까지 선형 변화. steer(f)는 진행률 f의 조향각, turn은 방향 변화(도)."""
        to = self.v if to is None else to
        start, n = self.v, max(1, round(sec / DT))
        for i in range(n):
            f = i / n
            self.v = start + (to - start) * f
            self._emit(steer(f) if steer else 0.0, brake)
            self.heading += turn / n
            self._advance(DT)
        self.v = to

    def cue(self, note):
        self.cues.append({"t": round(self.t, 1), "note": note})

    def mark(self, id, type, limit=None):
        p = {"id": id, "type": type, "lat": round(self.lat, 6), "lng": round(self.lng, 6)}
        if limit:
            p["speedLimitKph"] = limit
        self.points.append(p)

    def lane_change(self, sec=3.0, peak=30.0):
        self.drive(sec, steer=lambda f: peak * math.sin(math.pi * f))

    def turn(self, id, side, peak=200.0, signal_sec=4.0, target=15.0, then=45.0, use_signal=True, cue=None):
        name = "TURN_RIGHT" if side > 0 else "TURN_LEFT"
        # 감속 중간부터 깜빡이를 켜서 법 기준(30m 전)을 지킨다.
        self.drive(3, to=(self.v + target) / 2)
        if use_signal:
            self.signal = "RIGHT" if side > 0 else "LEFT"
        if cue:
            self.cue(cue)
        self.drive(3, to=target)
        self.drive(signal_sec)
        self.mark(id, name)
        self.drive(4, steer=lambda f: side * peak * math.sin(math.pi * f), turn=side * 90)
        self.signal = "OFF"
        self.drive(7, to=then)

    def wobble(self, peak=15.0, cycles=3, half=1.0):
        for _ in range(cycles):
            self.drive(half, steer=lambda f: peak)
            self.drive(half, steer=lambda f: -peak)

    def finish(self):
        self.mark("dest", "DESTINATION")
        self.park(4)

    def save(self, fname, speed=1.0):
        doc = {"name": self.name, "destination": self.dest, "playbackSpeed": speed,
               "samples": self.samples, "naviPoints": self.points, "scriptedEvents": self.cues}
        with open(os.path.join(OUT, fname), "w", encoding="utf8") as f:
            json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))


def demo_short():
    r = Route("시연 코스 (2분)", {"name": "회사", "lat": 37.5665, "lng": 126.9780}, 37.5900, 126.9700, 150.0)
    r.park(2)
    r.park(2, gear="D")
    r.drive(8, to=50)
    r.drive(8)
    r.cue("깜빡이 없이 조향 +30° 후 3초 안에 0°로 복귀")
    r.lane_change()
    r.drive(12)
    r.cue("조향 ±15° 좌우로 1초씩 3번 흔들기")
    r.wobble()
    r.drive(8)
    r.turn("turn-1", +1, cue="깜빡이 오른쪽 → 7초 뒤 조향 +200° → 0° 복귀 → 깜빡이 끄기")
    r.drive(10)
    r.mark("cam-1", "SPEED_CAMERA", 60)
    r.drive(10)
    r.cue("지금 브레이크 100%")
    r.drive(2.5, to=5, brake=100)
    r.drive(4, to=0, brake=30)
    r.cue("기어 P")
    r.finish()
    r.save("demo_short.json")


def commute():
    r = Route("출근 코스 (시연용)", {"name": "회사", "lat": 37.5665, "lng": 126.9780}, 37.5900, 126.9650, 120.0)
    r.park(2)
    r.park(2, gear="D")
    r.drive(10, to=45)
    r.drive(40)
    r.turn("turn-1", +1)
    r.drive(50)
    r.signal = "LEFT"; r.drive(4); r.lane_change(peak=-25.0); r.signal = "OFF"
    r.drive(30)
    r.turn("turn-2", -1)
    r.mark("cam-1", "SPEED_CAMERA", 50)
    r.drive(60)
    r.turn("turn-3", +1, signal_sec=1.5)
    r.drive(10, to=70)
    r.mark("sec-1", "SECTION_START", 80)
    r.drive(120)
    r.mark("sec-1e", "SECTION_END")
    r.lane_change(peak=20.0)
    r.drive(8, to=45)
    r.drive(40)
    r.turn("turn-4", -1)
    r.drive(40)
    r.turn("turn-5", +1)
    r.drive(30)
    r.drive(10, to=0, brake=30)
    r.finish()
    r.save("commute.json", speed=1.0)


os.makedirs(OUT, exist_ok=True)
demo_short()
commute()
