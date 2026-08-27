"""패치 가중치를 어떻게 가져갈지 재는 측정 (decision.md D15 의 미결).

D15 는 이중 감쇠의 반감기 2와 12를 "측정으로 정한 값이 아니다" 라고 남겼다.
D49 는 실제 데이터에서 감쇠가 전체의 10% 만 깎는다는 것을 확인했다.
이 스크립트는 그 이유와, 반감기를 바꾸면 무엇이 달라지는지를 잰다.

DB 가 아니라 골든 파일(tests/baseline/*.json)을 읽는다. 파서 출력 전체라 D35 제외 이전
값이고, 비밀번호 없이 돌릴 수 있다. 스크림은 제외한다 — 세이브에 시점 정보가 없어
(D8) 패치를 배정할 수 없기 때문이다.

    python tools/patch_weight_analysis.py
"""

import glob
import io
import json
import math
import os
from collections import defaultdict

BASELINE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "tests", "baseline", "slot_*.json")

TEAM_SIZE = 4


# --------------------------------------------------------------- 자료 만들기

def load_careers():
    """골든 파일 하나 = 커리어 하나. 경기에 패치를 배정하고 변경 횟수를 센다."""
    careers = []
    for path in sorted(glob.glob(BASELINE)):
        data = json.load(io.open(path, encoding="utf-8"))

        # 패치를 시간순으로 세우고 1부터 순번을 매긴다 (스키마의 patch.seq 와 같다).
        patches = sorted(data["patches"], key=lambda p: (p["season"], p["day"]))
        timeline = []
        for seq, patch in enumerate(patches, start=1):
            touched = [c["name"] for c in patch["changes"]] + list(patch["new_champs"])
            timeline.append({
                "seq": seq,
                "season": patch["season"],
                "day": patch["day"],
                "touched": touched,
            })

        # 챔피언별로 그를 건드린 패치 순번들 (PatchAssigner 와 같은 정의 — is_new 도 센다)
        change_seqs = defaultdict(list)
        for patch in timeline:
            for name in patch["touched"]:
                change_seqs[name].append(patch["seq"])

        games = []
        for game in data["game_stats"]:
            blue, red = game["blue_pick"], game["red_pick"]
            if len(blue) != TEAM_SIZE or len(red) != TEAM_SIZE:
                continue  # D35
            seq = patch_seq_at(timeline, game["season"], game["day"])
            winners = blue if game["win_team"] == 0 else red
            losers = red if game["win_team"] == 0 else blue
            games.append({
                "season": game["season"],
                "day": game["day"],
                "patch_seq": seq,
                "winners": list(winners),
                "losers": list(losers),
            })

        careers.append({
            "name": os.path.basename(path),
            "timeline": timeline,
            "change_seqs": change_seqs,
            "games": games,
            "last_seq": timeline[-1]["seq"] if timeline else 0,
        })
    return careers


def patch_seq_at(timeline, season, day):
    """그 시점에 적용 중인 패치의 순번. 첫 패치 이전이면 0."""
    found = 0
    for patch in timeline:
        if (patch["season"], patch["day"]) <= (season, day):
            found = patch["seq"]
        else:
            break
    return found


def change_count_at(career, champion, seq):
    return sum(1 for s in career["change_seqs"].get(champion, []) if s <= seq)


def decay(self_changes, elapsed, self_half, meta_half):
    return 0.5 ** (self_changes / self_half) * 0.5 ** (elapsed / meta_half)


# ------------------------------------------------------------------ 측정 1

def measure_time_span(careers):
    print("=" * 78)
    print("측정 1 — 경기가 시간축에서 어디에 있나")
    print("=" * 78)
    print("게임은 시즌마다 경기 기록을 버린다(D6). 세이브 한 벌에 남는 것은 마지막")
    print("시즌뿐이다. 그러면 '패치별 통계' 라는 말이 성립하는지부터 봐야 한다.")
    print()
    for career in careers:
        games = career["games"]
        seasons = sorted({g["season"] for g in games})
        seqs = sorted({g["patch_seq"] for g in games})
        span = max(seqs) - min(seqs) if seqs else 0
        print(f"  {career['name']}")
        print(f"    공식 4v4 경기 {len(games)}건 · 시즌 {seasons}")
        print(f"    패치 총 {career['last_seq']}개 · 경기가 걸친 패치 순번 {seqs} (폭 {span})")
        by_seq = defaultdict(int)
        for g in games:
            by_seq[g["patch_seq"]] += 1
        print(f"    패치 구간별 경기 수: "
              + ", ".join(f"seq{k}={v}" for k, v in sorted(by_seq.items())))
        print()


# ------------------------------------------------------------------ 측정 2

def measure_current_decay(careers, self_half=2.0, meta_half=12.0):
    print("=" * 78)
    print(f"측정 2 — 지금 감쇠(자기 {self_half} · 메타 {meta_half})가 실제로 하는 일")
    print("=" * 78)
    elapsed_hist = defaultdict(int)
    self_hist = defaultdict(int)
    raw = weighted = 0.0
    weights = []
    for career in careers:
        ref_seq = career["last_seq"]
        for game in career["games"]:
            elapsed = abs(ref_seq - game["patch_seq"])
            for champion in game["winners"] + game["losers"]:
                sc = abs(change_count_at(career, champion, ref_seq)
                         - change_count_at(career, champion, game["patch_seq"]))
                w = decay(sc, elapsed, self_half, meta_half)
                elapsed_hist[elapsed] += 1
                self_hist[sc] += 1
                raw += 1
                weighted += w
                weights.append(w)
    print("  경과 패치 분포 (출전 기준):")
    for k in sorted(elapsed_hist):
        print(f"    {k}패치 전: {elapsed_hist[k]:>6}건  → 메타 가중치 {0.5 ** (k / meta_half):.3f}")
    print("  자기 변경 분포:")
    for k in sorted(self_hist):
        print(f"    {k}회 변경: {self_hist[k]:>6}건  → 자기 가중치 {0.5 ** (k / self_half):.3f}")
    print(f"  원시 출전 {int(raw)} · 가중 합 {weighted:.1f} · 남은 비율 {weighted / raw:.3f}")
    print(f"  가중치 최소 {min(weights):.3f} · 최대 {max(weights):.3f}")
    print()
    return elapsed_hist


# ------------------------------------------------------------------ 측정 3

def champion_rates(careers, self_half, meta_half, k0=24.0):
    """반감기 설정 하나로 챔피언별 축소추정 승률을 낸다."""
    wg = defaultdict(float)
    ww = defaultdict(float)
    for career in careers:
        ref_seq = career["last_seq"]
        for game in career["games"]:
            elapsed = abs(ref_seq - game["patch_seq"])
            for won, side in ((True, game["winners"]), (False, game["losers"])):
                for champion in side:
                    sc = abs(change_count_at(career, champion, ref_seq)
                             - change_count_at(career, champion, game["patch_seq"]))
                    w = decay(sc, elapsed, self_half, meta_half)
                    wg[champion] += w
                    if won:
                        ww[champion] += w
    return {c: (ww[c] + k0 * 0.5) / (wg[c] + k0) for c in wg}, wg


def spearman(a, b):
    keys = sorted(set(a) & set(b))
    ra = {k: i for i, k in enumerate(sorted(keys, key=lambda k: a[k]))}
    rb = {k: i for i, k in enumerate(sorted(keys, key=lambda k: b[k]))}
    n = len(keys)
    if n < 2:
        return float("nan")
    d2 = sum((ra[k] - rb[k]) ** 2 for k in keys)
    return 1 - 6 * d2 / (n * (n * n - 1))


def measure_half_life_grid(careers):
    print("=" * 78)
    print("측정 3 — 반감기를 바꾸면 티어 순위가 실제로 달라지나")
    print("=" * 78)
    print("감쇠를 아예 끈 것(무한대)과 비교한다. 순위 상관이 1.000 이면 그 설정은")
    print("아무 일도 하지 않는 것이다 — 값을 정할 근거도, 정할 필요도 없다.")
    print()
    base, base_wg = champion_rates(careers, 1e9, 1e9)
    print(f"  {'자기반감기':>10} {'메타반감기':>10} {'남은비율':>9} {'순위상관':>9} {'최대변동%p':>11}")
    for self_half, meta_half in [(1e9, 1e9), (2, 12), (2, 6), (2, 3), (1, 3),
                                 (1, 1), (0.5, 1)]:
        rates, wg = champion_rates(careers, self_half, meta_half)
        kept = sum(wg.values()) / sum(base_wg.values())
        rho = spearman(base, rates)
        shift = max(abs(rates[c] - base[c]) for c in rates) * 100
        label_s = "없음" if self_half > 1e8 else f"{self_half:g}"
        label_m = "없음" if meta_half > 1e8 else f"{meta_half:g}"
        print(f"  {label_s:>10} {label_m:>10} {kept:>9.3f} {rho:>9.3f} {shift:>11.2f}")
    print()


# ------------------------------------------------------------------ 측정 4

def measure_patch_effect(careers, min_games=8):
    print("=" * 78)
    print("측정 4 — 패치가 그 챔피언의 승률을 실제로 바꾸는가 (자기 변경 축의 근거)")
    print("=" * 78)
    print("경기 기간 안에 일어난 패치만 볼 수 있다. 그 패치가 건드린 챔피언의 전/후")
    print("승률 변화를, 안 건드린 챔피언(대조군)의 변화와 비교한다.")
    print("대조군과 차이가 없으면 자기 변경 감쇠를 세게 줄 근거가 없다.")
    print()
    touched_deltas = []
    control_deltas = []
    for career in careers:
        seqs = sorted({g["patch_seq"] for g in career["games"]})
        for boundary in seqs[1:]:
            before = [g for g in career["games"] if g["patch_seq"] < boundary]
            after = [g for g in career["games"] if g["patch_seq"] >= boundary]
            touched = set(next(
                (p["touched"] for p in career["timeline"] if p["seq"] == boundary), []))
            for champion in all_champions(career):
                b = win_rate(before, champion, min_games)
                a = win_rate(after, champion, min_games)
                if b is None or a is None:
                    continue
                (touched_deltas if champion in touched else control_deltas).append(a - b)
    report_deltas("패치가 건드린 챔피언", touched_deltas)
    report_deltas("대조군(안 건드린 챔피언)", control_deltas)
    print()


def all_champions(career):
    names = set()
    for game in career["games"]:
        names.update(game["winners"])
        names.update(game["losers"])
    return names


def win_rate(games, champion, min_games):
    played = won = 0
    for game in games:
        if champion in game["winners"]:
            played += 1
            won += 1
        elif champion in game["losers"]:
            played += 1
    return won / played if played >= min_games else None


def report_deltas(label, deltas):
    if not deltas:
        print(f"  {label}: 표본 없음")
        return
    mean_abs = sum(abs(d) for d in deltas) / len(deltas)
    sd = math.sqrt(sum(d * d for d in deltas) / len(deltas))
    print(f"  {label}: {len(deltas)}개 · 평균 |변화| {mean_abs * 100:.2f}%p"
          f" · 제곱평균 {sd * 100:.2f}%p")


# ------------------------------------------------------------------ 측정 5

def measure_synergy_samples(careers):
    print("=" * 78)
    print("측정 5 — 시너지 쌍도 같은 구조인가 (아직 구현 전이라 여기서 미리 센다)")
    print("=" * 78)
    pair_games = defaultdict(int)
    pair_by_seq = defaultdict(lambda: defaultdict(int))
    for career in careers:
        for game in career["games"]:
            for side in (game["winners"], game["losers"]):
                for i in range(len(side)):
                    for j in range(i + 1, len(side)):
                        key = tuple(sorted((side[i], side[j])))
                        pair_games[key] += 1
                        pair_by_seq[key][game["patch_seq"]] += 1
    total = len(pair_games)
    ten = sum(1 for v in pair_games.values() if v >= 10)
    print(f"  같은 팀 2인 조합: 관측 {total}가지 · 10경기 이상 {ten}가지")
    per_patch_ten = 0
    for key, by_seq in pair_by_seq.items():
        if any(v >= 10 for v in by_seq.values()):
            per_patch_ten += 1
    print(f"  한 패치 구간 안에서만 10경기 이상인 조합: {per_patch_ten}가지")
    print("  → 이 숫자가 0 에 가까우면 시너지는 패치별로 자를 수 없다.")
    print()


# ------------------------------------------------------------------ 측정 6

def measure_two_stage(careers, k0=24.0, k1=15.0, min_sample=10):
    """2단 축소(D15b)가 실제로 다른 답을 주는지 잰다."""
    print("=" * 78)
    print("측정 6 — 패치별 추정(2단 축소)이 전체 누적과 실제로 다른가")
    print("=" * 78)
    print("D24 는 패치 선택을 '필터가 아니라 추정 시점의 선택' 으로 정했다. 그 2단 축소를")
    print("지금 만들 값어치가 있는지 보려면, θ_patch 가 θ_all 과 얼마나 다른지 재야 한다.")
    print("거의 같다면 화면에 패치 선택을 붙여도 숫자가 안 바뀐다.")
    print()
    cells = 0
    enough = 0
    deltas = []
    for career in careers:
        seqs = sorted({g["patch_seq"] for g in career["games"]})
        # θ_all — 그 커리어 전체 (감쇠 없이, 비교를 단순하게)
        overall = {}
        for champion in all_champions(career):
            played = won = 0
            for game in career["games"]:
                if champion in game["winners"]:
                    played += 1
                    won += 1
                elif champion in game["losers"]:
                    played += 1
            if played:
                overall[champion] = (won + k0 * 0.5) / (played + k0)
        for seq in seqs:
            window = [g for g in career["games"] if g["patch_seq"] == seq]
            for champion, theta_all in overall.items():
                played = won = 0
                for game in window:
                    if champion in game["winners"]:
                        played += 1
                        won += 1
                    elif champion in game["losers"]:
                        played += 1
                if played == 0:
                    continue
                cells += 1
                if played >= min_sample:
                    enough += 1
                theta_patch = (won + k1 * theta_all) / (played + k1)
                deltas.append(abs(theta_patch - theta_all))
    deltas.sort()
    print(f"  챔피언×패치구간 칸 {cells}개 · 그중 {min_sample}경기 이상 {enough}개"
          f" ({enough / cells * 100:.0f}%)")
    describe("  패치 구간", deltas)

    # 대조군 — 같은 크기의 무작위 구간으로 나눈다. 패치 경계에 아무 의미가 없다면
    # 두 값이 같아야 한다 (D9 의 "동전던지기여도 나올 개수" 와 같은 계산).
    null = null_split_deltas(careers, k0, k1, seed=20260828)
    describe("  무작위 구간(대조)", null)
    ratio = median(deltas) / median(null) if median(null) else float("nan")
    print(f"  → 패치/무작위 비율 {ratio:.2f}."
          f" 1.0 에 가까우면 패치 경계가 아무 정보도 담고 있지 않다는 뜻이다.")
    print()


def median(values):
    return values[len(values) // 2] if values else 0.0


def describe(label, deltas):
    if not deltas:
        print(f"{label}: 표본 없음")
        return
    print(f"{label}: |θ − θ_all| 중앙값 {median(deltas) * 100:.2f}%p"
          f" · 90분위 {deltas[int(len(deltas) * 0.9)] * 100:.2f}%p"
          f" · 최대 {deltas[-1] * 100:.2f}%p")


def null_split_deltas(careers, k0, k1, seed):
    """패치 경계 대신 같은 크기의 무작위 구간으로 나눠 같은 통계를 낸다."""
    import random
    rng = random.Random(seed)
    deltas = []
    for career in careers:
        sizes = [sum(1 for g in career["games"] if g["patch_seq"] == seq)
                 for seq in sorted({g["patch_seq"] for g in career["games"]})]
        shuffled = list(career["games"])
        rng.shuffle(shuffled)
        windows = []
        start = 0
        for size in sizes:
            windows.append(shuffled[start:start + size])
            start += size

        overall = {}
        for champion in all_champions(career):
            played = won = 0
            for game in career["games"]:
                if champion in game["winners"]:
                    played += 1
                    won += 1
                elif champion in game["losers"]:
                    played += 1
            if played:
                overall[champion] = (won + k0 * 0.5) / (played + k0)

        for window in windows:
            for champion, theta_all in overall.items():
                played = won = 0
                for game in window:
                    if champion in game["winners"]:
                        played += 1
                        won += 1
                    elif champion in game["losers"]:
                        played += 1
                if played == 0:
                    continue
                deltas.append(abs((won + k1 * theta_all) / (played + k1) - theta_all))
    deltas.sort()
    return deltas


if __name__ == "__main__":
    careers = load_careers()
    measure_time_span(careers)
    measure_current_decay(careers)
    measure_half_life_grid(careers)
    measure_patch_effect(careers)
    measure_synergy_samples(careers)
    measure_two_stage(careers)
