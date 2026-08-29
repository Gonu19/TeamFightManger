"""패치 가중치를 어떻게 가져갈지 재는 측정 (decisions/D15_*.md 의 미결).

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

        raw_changes = {}
        for seq, patch in enumerate(patches, start=1):
            raw_changes[seq] = list(patch["changes"])

        careers.append({
            "name": os.path.basename(path),
            "raw_changes": raw_changes,
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


# ------------------------------------------------------------------ 측정 7
#
# 측정 4 를 다시 만든 것이다. 처음 것에는 결함이 둘 있었다.
#   1. |승률 변화| 를 쟀다 — 상향/하향은 부호가 있는 효과인데 절댓값이 그걸 뭉갠다
#   2. 챔피언 전체를 평균했다 — 한 패치가 건드리는 것은 40명 중 6~10명이라
#      진짜 효과가 있어도 4~6배로 희석된다
# 여기서는 방향을 살리고, 건드린 챔피언만 본다.

BUFF_WHEN_POSITIVE = ("attack", "magic", "defence", "max_hp", "attack_speed", "move_speed")
NERF_WHEN_POSITIVE = ("skill_cool",)


def patch_direction(change):
    """+1 상향 · -1 하향 · 0 판단 불가(변경 없음 또는 서로 상충)."""
    score = 0
    for stat in BUFF_WHEN_POSITIVE:
        if change.get(stat, 0):
            score += 1 if change[stat] > 0 else -1
    for stat in NERF_WHEN_POSITIVE:
        if change.get(stat, 0):
            score += -1 if change[stat] > 0 else 1
    return 0 if score == 0 else (1 if score > 0 else -1)


def window_record(games, seq, champion):
    played = won = 0
    for game in games:
        if game["patch_seq"] != seq:
            continue
        if champion in game["winners"]:
            played += 1
            won += 1
        elif champion in game["losers"]:
            played += 1
    return played, won


def measure_patch_effect_signed(careers, min_games=5):
    print("=" * 78)
    print(f"측정 7 — 상향/하향의 방향을 살려서 다시 잰다 (최소 {min_games}경기)")
    print("=" * 78)
    print("인접한 두 패치 구간만 비교한다. 패치 하나의 효과를 고립시키기 위해서다.")
    print("상향은 오르고 하향은 내려야 하므로, 방향을 곱한 값이 양수여야 한다.")
    print()

    buckets = {"상향": [], "하향": [], "대조(안 건드림)": []}
    before_levels = {"상향": [], "하향": []}

    for career in careers:
        seqs = sorted({g["patch_seq"] for g in career["games"]})
        champions = all_champions(career)
        for i in range(1, len(seqs)):
            prev_seq, seq = seqs[i - 1], seqs[i]
            patch = next((p for p in career["timeline"] if p["seq"] == seq), None)
            if patch is None:
                continue
            directions = {}
            source = next((p for p in career["timeline"] if p["seq"] == seq), None)
            for change in career["raw_changes"].get(seq, []):
                d = patch_direction(change)
                if d:
                    directions[change["name"]] = d

            for champion in champions:
                pb, wb = window_record(career["games"], prev_seq, champion)
                pa, wa = window_record(career["games"], seq, champion)
                if pb < min_games or pa < min_games:
                    continue
                delta = wa / pa - wb / pb
                d = directions.get(champion)
                if d is None:
                    buckets["대조(안 건드림)"].append(delta)
                else:
                    label = "상향" if d > 0 else "하향"
                    buckets[label].append(delta * d)
                    before_levels[label].append(wb / pb)

    print(f"  {'구분':<16}{'표본':>5}{'평균 효과':>11}{'표준오차':>10}{'t':>7}")
    for label, values in buckets.items():
        if not values:
            print(f"  {label:<16}{0:>5}")
            continue
        n = len(values)
        mean = sum(values) / n
        var = sum((v - mean) ** 2 for v in values) / (n - 1) if n > 1 else 0.0
        se = math.sqrt(var / n) if n > 1 else float("nan")
        t = mean / se if se else float("nan")
        print(f"  {label:<16}{n:>5}{mean * 100:>10.2f}%p{se * 100:>9.2f}%p{t:>7.2f}")
    print()
    print("  선택 편향 확인 — 게임이 센 챔피언을 골라 하향하는가?")
    for label, values in before_levels.items():
        if values:
            print(f"    {label} 직전 승률 평균 {sum(values) / len(values) * 100:.1f}%"
                  f" ({len(values)}건)")
    print("    → 하향 직전 승률이 50% 보다 뚜렷이 높으면, 하향 후 하락은 패치 효과가")
    print("       아니라 평균 회귀일 수 있다.")
    print()


# ------------------------------------------------------------------ 측정 8
#
# 측정 7 에서 상향 그룹이 +11.85%p 올랐다. 그런데 그 그룹의 직전 승률이 36.9% 였다.
# 적은 표본에서 낮게 관측된 값은 다음 구간에 그냥 올라간다 — 평균 회귀다.
# 패치 효과인지 평균 회귀인지 가르려면, 안 건드린 챔피언이 같은 직전 승률에서
# 얼마나 움직이는지를 기준선으로 삼아야 한다.


def measure_patch_effect_net_of_regression(careers, min_games=5):
    print("=" * 78)
    print("측정 8 — 평균 회귀를 걷어내고 남는 패치 효과")
    print("=" * 78)
    print("대조군(안 건드린 챔피언)에서 '직전 승률 → 다음 구간 변화' 의 기울기를 구한다.")
    print("그게 평균 회귀의 크기다. 건드린 챔피언의 실제 변화에서 그 예측을 빼면")
    print("남는 것이 패치 자체의 효과다.")
    print()

    touched = []   # (direction, before_rate, delta)
    control = []   # (before_rate, delta)

    for career in careers:
        seqs = sorted({g["patch_seq"] for g in career["games"]})
        champions = all_champions(career)
        for i in range(1, len(seqs)):
            prev_seq, seq = seqs[i - 1], seqs[i]
            directions = {}
            for change in career["raw_changes"].get(seq, []):
                d = patch_direction(change)
                if d:
                    directions[change["name"]] = d
            for champion in champions:
                pb, wb = window_record(career["games"], prev_seq, champion)
                pa, wa = window_record(career["games"], seq, champion)
                if pb < min_games or pa < min_games:
                    continue
                before = wb / pb
                delta = wa / pa - before
                if champion in directions:
                    touched.append((directions[champion], before, delta))
                else:
                    control.append((before, delta))

    if len(control) < 3 or not touched:
        print("  표본이 부족하다")
        print()
        return

    # 대조군에서 회귀 기울기를 구한다: delta = a + b*(before - 0.5)
    xs = [b - 0.5 for b, _ in control]
    ys = [d for _, d in control]
    n = len(xs)
    mx = sum(xs) / n
    my = sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    slope = sxy / sxx if sxx else 0.0
    intercept = my - slope * mx
    resid_var = sum((y - (intercept + slope * x)) ** 2 for x, y in zip(xs, ys)) / (n - 2)

    print(f"  대조군 {n}건 — 회귀 기울기 {slope:+.3f}"
          f" (직전 승률이 50% 보다 10%p 낮으면 다음 구간에 {-slope * 10:+.1f}%p 오른다)")
    print(f"  잔차 표준편차 {math.sqrt(resid_var) * 100:.2f}%p")
    print()

    print(f"  {'구분':<10}{'표본':>5}{'실제 변화':>11}{'회귀 예측':>11}{'순효과':>10}{'표준오차':>10}{'t':>7}")
    for label, want in (("상향", 1), ("하향", -1)):
        group = [(b, d) for dirn, b, d in touched if dirn == want]
        if not group:
            continue
        residuals = [d - (intercept + slope * (b - 0.5)) for b, d in group]
        k = len(residuals)
        observed = sum(d for _, d in group) / k
        predicted = sum(intercept + slope * (b - 0.5) for b, _ in group) / k
        mean = sum(residuals) / k
        var = sum((r - mean) ** 2 for r in residuals) / (k - 1) if k > 1 else 0.0
        se = math.sqrt(var / k) if k > 1 else float("nan")
        t = mean / se if se else float("nan")
        print(f"  {label:<10}{k:>5}{observed * 100:>10.2f}%p{predicted * 100:>10.2f}%p"
              f"{mean * 100:>9.2f}%p{se * 100:>9.2f}%p{t:>7.2f}")

    signed = [(d - (intercept + slope * (b - 0.5))) * dirn for dirn, b, d in touched]
    k = len(signed)
    mean = sum(signed) / k
    var = sum((v - mean) ** 2 for v in signed) / (k - 1) if k > 1 else 0.0
    se = math.sqrt(var / k) if k > 1 else float("nan")
    print(f"  {'방향 통합':<10}{k:>5}{'':>11}{'':>11}{mean * 100:>9.2f}%p{se * 100:>9.2f}%p"
          f"{mean / se if se else float('nan'):>7.2f}")
    print()
    detectable = 2 * se * 100
    print(f"  검출력 — 이 표본으로는 {detectable:.1f}%p 보다 작은 효과는 잡을 수 없다(t=2 기준).")
    print()


# ------------------------------------------------------------------ 측정 9
#
# 유저는 현재 패치 기준으로 플레이한다. 앱이 "A 가 좋다" 고 하면 그건 지금 좋다는 뜻이어야
# 한다. 그런데 지금 구현은 커리어의 모든 경기를 합쳐서 답한다.
#
# 측정 1~8 은 분산(패치 구간 추정치가 노이즈인가)을 쟀다. 여기서는 편향을 잰다 —
# 통합 추정치가 "현재 패치" 에 대해 얼마나 다른 답을 주는가.
#
# 핵심은 자기 변경 축이다. 다른 챔피언이 바뀐 것(메타)은 상대 풀을 통한 2차 효과지만,
# 그 챔피언 자신이 바뀐 것은 옛 데이터를 직접 무효로 만든다.


def measure_self_change_axis(careers, k0=24.0):
    print("=" * 78)
    print("측정 9 — 자기 변경 축: 지금 바뀐 챔피언의 옛 데이터를 얼마나 쓸 것인가")
    print("=" * 78)
    print("기준 시점(마지막 패치)에서 각 챔피언이 마지막으로 바뀐 뒤 몇 패치가 지났는지,")
    print("그리고 그 챔피언의 경기 중 몇 %가 '바뀌기 전' 인지 센다.")
    print()

    for career in careers:
        ref_seq = career["last_seq"]
        champions = sorted(all_champions(career))
        rows = []
        for champion in champions:
            seqs = career["change_seqs"].get(champion, [])
            last_change = max(seqs) if seqs else None
            played = stale = 0
            for game in career["games"]:
                if champion in game["winners"] or champion in game["losers"]:
                    played += 1
                    if last_change is not None and game["patch_seq"] < last_change:
                        stale += 1
            if played:
                rows.append((champion, last_change, played, stale))

        touched = [r for r in rows if r[3] > 0]
        print(f"  {career['name']}  (기준 패치 {ref_seq})")
        print(f"    출전한 챔피언 {len(rows)}명 중 **경기 기간 안에 바뀐** 챔피언 {len(touched)}명")
        if touched:
            worst = sorted(touched, key=lambda r: -r[3] / r[2])[:8]
            print(f"    {'챔피언':<18}{'마지막변경':>10}{'출전':>6}{'변경전':>7}{'낡은비율':>9}")
            for champion, last_change, played, stale in worst:
                print(f"    {champion:<18}{('seq' + str(last_change)):>10}{played:>6}"
                      f"{stale:>7}{stale / played * 100:>8.0f}%")
        print()


def measure_self_half_life_on_touched(careers, k0=24.0):
    print("=" * 78)
    print("측정 10 — 자기 반감기를 바꾸면 '바뀐 챔피언' 의 추정이 얼마나 움직이나")
    print("=" * 78)
    print("측정 3 은 40명 전체를 평균해서 효과가 희석됐다. 여기서는 실제로 바뀐 챔피언만 본다.")
    print("하드 컷은 '마지막 변경 이전 경기를 아예 버린다' 는 뜻이다.")
    print()

    def rates(self_half, hard_cut=False):
        wg = defaultdict(float)
        ww = defaultdict(float)
        for career in careers:
            ref_seq = career["last_seq"]
            for game in career["games"]:
                for won, side in ((True, game["winners"]), (False, game["losers"])):
                    for champion in side:
                        sc = abs(change_count_at(career, champion, ref_seq)
                                 - change_count_at(career, champion, game["patch_seq"]))
                        if hard_cut:
                            w = 0.0 if sc > 0 else 1.0
                        else:
                            w = 0.5 ** (sc / self_half) if self_half else (0.0 if sc else 1.0)
                        wg[champion] += w
                        if won:
                            ww[champion] += w
        return ({c: (ww[c] + k0 * 0.5) / (wg[c] + k0) for c in wg}, wg)

    # 경기 기간 안에 실제로 바뀐 챔피언만 추린다
    changed = set()
    for career in careers:
        ref_seq = career["last_seq"]
        for game in career["games"]:
            for champion in game["winners"] + game["losers"]:
                if change_count_at(career, champion, ref_seq) !=                         change_count_at(career, champion, game["patch_seq"]):
                    changed.add(champion)

    base, base_wg = rates(1e9)
    print(f"  경기 기간 안에 바뀐 챔피언 {len(changed)}명 (전체 {len(base)}명)")
    print()
    print(f"  {'자기반감기':>12}{'바뀐쪽 표본유지':>15}{'평균 이동':>11}{'최대 이동':>11}{'순위 변동':>10}")
    for label, half, hard in [("2 (현재)", 2.0, False), ("1", 1.0, False),
                              ("0.5", 0.5, False), ("하드 컷", None, True)]:
        r, wg = rates(half if half else 1.0, hard_cut=hard)
        kept = (sum(wg[c] for c in changed)
                / sum(base_wg[c] for c in changed)) if changed else float("nan")
        moves = [abs(r[c] - base[c]) for c in changed]
        avg = sum(moves) / len(moves) if moves else 0.0
        rank_base = {c: i for i, c in enumerate(sorted(base, key=lambda x: base[x]))}
        rank_new = {c: i for i, c in enumerate(sorted(r, key=lambda x: r[x]))}
        moved = max(abs(rank_base[c] - rank_new[c]) for c in base)
        print(f"  {label:>12}{kept:>14.3f}{avg * 100:>10.2f}%p"
              f"{max(moves) * 100 if moves else 0:>10.2f}%p{moved:>9}칸")
    print()
    print("  '순위 변동' 은 40명 중 한 챔피언이 최대 몇 칸 움직였는지다.")
    print()


def measure_career_divergence(careers):
    print("=" * 78)
    print("측정 11 — 커리어마다 패치 역사가 다르다. GLOBAL 은 같은 챔피언을 합쳐도 되나")
    print("=" * 78)
    print("패치는 커리어마다 고유 생성된다. 19패치를 거치면 같은 이름의 챔피언이라도")
    print("커리어별로 스탯이 달라진다. GLOBAL 스코프는 그것을 한 줄로 합친다.")
    print()
    totals = {}
    for career in careers:
        acc = defaultdict(lambda: defaultdict(int))
        for seq, changes in career["raw_changes"].items():
            for change in changes:
                for stat, value in change.items():
                    if stat != "name" and value:
                        acc[change["name"]][stat] += value
        totals[career["name"]] = acc

    names = sorted(set().union(*[set(a) for a in totals.values()]))
    print(f"  패치를 한 번이라도 받은 챔피언 {len(names)}명")
    print()
    print(f"  {'챔피언':<18}" + "".join(f"{n[5:13]:>12}" for n in totals))
    shown = 0
    for name in names:
        cells = []
        for career_name in totals:
            acc = totals[career_name].get(name, {})
            if not acc:
                cells.append("-")
            else:
                stat, value = max(acc.items(), key=lambda kv: abs(kv[1]))
                cells.append(f"{stat[:6]}{value:+d}")
        # 커리어 사이에 부호가 갈리는 챔피언만 보여준다 — 그게 문제의 핵심이다
        signs = set()
        for career_name in totals:
            acc = totals[career_name].get(name, {})
            for stat, value in acc.items():
                if stat != "skill_cool" and value:
                    signs.add(value > 0)
                elif stat == "skill_cool" and value:
                    signs.add(value < 0)
        if len(signs) > 1 and shown < 12:
            print(f"  {name:<18}" + "".join(f"{c:>12}" for c in cells))
            shown += 1
    print()
    print("  위는 커리어 사이에 상향/하향 방향이 갈린 챔피언이다.")
    print("  같은 이름인데 한쪽에서는 세지고 다른 쪽에서는 약해졌다는 뜻이다.")
    print()


# ------------------------------------------------------------------ 측정 12
#
# 한 챔피언이 하향되면 그에게 카운터당하던 챔피언들의 승률도 따라 움직인다. 효과가
# 바뀐 챔피언에 갇히지 않는다 — D15 가 메타 축을 둔 이유가 그것이다.
# 그러니 자기 변경 축이 아니라 **모든 챔피언에게 똑같이 걸리는 메타 축**을 조정해야 한다.
#
# 그리고 기준 스코프는 현재 플레이하는 슬롯 하나다. 커리어마다 밸런스가 다르므로
# (측정 11) 합치면 안 된다. 그래서 커리어별로 따로 잰다.


def measure_meta_axis_per_career(careers, k0=24.0, min_games=10):
    print("=" * 78)
    print("측정 12 — 메타 반감기만 따로. 커리어별(= 실제 기준 스코프)로 본다")
    print("=" * 78)
    print("자기 변경 축은 끄고 메타 축만 움직인다. 모든 챔피언에게 똑같이 걸리므로")
    print("나비효과(안 바뀐 챔피언의 승률 변화)까지 담는다.")
    print()

    for career in careers:
        ref_seq = career["last_seq"]
        seqs = sorted({g["patch_seq"] for g in career["games"]})
        print(f"  {career['name']}  경기 {len(career['games'])}건 · "
              f"패치 구간 {seqs} · 기준 seq{ref_seq}")

        def rates(meta_half):
            wg = defaultdict(float)
            ww = defaultdict(float)
            w2 = defaultdict(float)
            for game in career["games"]:
                elapsed = abs(ref_seq - game["patch_seq"])
                w = 1.0 if meta_half is None else 0.5 ** (elapsed / meta_half)
                for won, side in ((True, game["winners"]), (False, game["losers"])):
                    for champion in side:
                        wg[champion] += w
                        w2[champion] += w * w
                        if won:
                            ww[champion] += w
            adjusted = {c: (ww[c] + k0 * 0.5) / (wg[c] + k0) for c in wg}
            ess = {c: (wg[c] ** 2 / w2[c] if w2[c] else 0.0) for c in wg}
            return adjusted, wg, ess

        base, base_wg, base_ess = rates(None)
        eligible = [c for c in base_ess if base_ess[c] >= min_games]
        print(f"    {'메타반감기':>10}{'표본유지':>9}{'ess>=10':>9}"
              f"{'평균 이동':>11}{'최대 이동':>11}{'순위 변동':>10}")
        for label, half in [("없음", None), ("12 (현재)", 12.0), ("6", 6.0),
                            ("3", 3.0), ("2", 2.0), ("1", 1.0)]:
            r, wg, ess = rates(half)
            kept = sum(wg.values()) / sum(base_wg.values())
            survivors = sum(1 for c in ess if ess[c] >= min_games)
            moves = [abs(r[c] - base[c]) for c in eligible]
            avg = sum(moves) / len(moves) if moves else 0.0
            rb = {c: i for i, c in enumerate(sorted(base, key=lambda x: base[x]))}
            rn = {c: i for i, c in enumerate(sorted(r, key=lambda x: r[x]))}
            moved = max(abs(rb[c] - rn[c]) for c in base)
            print(f"    {label:>10}{kept:>9.3f}{survivors:>9}"
                  f"{avg * 100:>10.2f}%p{max(moves) * 100 if moves else 0:>10.2f}%p"
                  f"{moved:>9}칸")
        print()
    print("  'ess>=10' 은 유효표본이 표본 기준선(D9)을 넘는 챔피언 수다. 40명 중 몇 명이")
    print("  화면에 뜰 자격을 유지하는지를 뜻한다 — 감쇠의 진짜 비용은 이쪽이다.")
    print()


if __name__ == "__main__":
    careers = load_careers()
    measure_time_span(careers)
    measure_current_decay(careers)
    measure_half_life_grid(careers)
    measure_patch_effect(careers)
    measure_synergy_samples(careers)
    measure_two_stage(careers)
    measure_patch_effect_signed(careers)
    measure_patch_effect_net_of_regression(careers)
    measure_self_change_axis(careers)
    measure_self_half_life_on_touched(careers)
    measure_career_divergence(careers)
    measure_meta_axis_per_career(careers)
