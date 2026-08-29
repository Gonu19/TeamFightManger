"""시너지 분산 분해 — `/synergy` 화면을 만들 값어치가 있는지를 가르는 측정.

**묻는 것.** 같은 팀에 선 조합의 승률이 흔들리는 이유를 쪼갠다.

    조합 고유 시너지  — "이 둘이 같이 서면 합이 좋다" 는 진짜 효과
    챔피언 강도       — 센 챔피언 둘이 만나면 그냥 이긴다. 시너지가 아니다
    팀 강도           — 센 팀이 그 조합을 즐겨 쓴다. 이것도 시너지가 아니다
    노이즈            — 표본이 적어서 생기는 흔들림

D14 가 **카운터**에서 같은 분해를 했고 페어 고유 상성이 분산의 3% 였다.
시너지가 그보다 작으면 화면을 만들 값어치가 없다.

**D14 와 다른 점 셋.**

1. **기대 승률을 식으로 가정하지 않는다.** D14 는 Bradley-Terry 결합식을 썼는데,
   시너지에는 쓸 결합 연산이 없다(`architecture.md` 가 미결로 남긴 그 문제다).
   대신 **"팀 구성은 챔피언 강도의 합으로만 작동한다"** 는 가법 로지스틱 모형을
   데이터에서 직접 적합한다. 이것이 귀무가설이고, 조합 고유 시너지는 그 잔차다.

2. **대조군을 같이 돌린다.** 잔차 분산은 진짜 효과가 0이어도 0이 아니다. 그리고
   방향이 직관과 반대다 — 모형을 **같은 데이터에** 적합했으므로 기대값이 관측을
   따라가고, 잔차는 표본 노이즈보다 **작아진다.** 그래서 "조합 고유 = 잔차분산 −
   노이즈" 는 체계적으로 음수 쪽으로 치우친다. 적합된 모형에서 승패만 다시 뽑아
   (모수 부트스트랩) 매번 **다시 적합**하면 그 편향의 크기가 그대로 나온다.
   관측값에서 그 바닥을 빼야 비로소 추정치가 된다. D14 는 이 보정 없이 3% 를 얻었다.

3. **팀 강도를 뺀다.** 같은 팀이 같은 조합을 반복해 쓴다. 팀 효과를 모형에 넣지
   않으면 선수단 강도가 통째로 "조합 고유 시너지" 로 넘어온다. 3인 조합은 거의
   팀 지문이라 이 오염이 가장 크다. 그래서 **팀 효과 없는 모형과 있는 모형을
   둘 다** 돌리고 차이를 본다. 그 차이가 곧 오염의 크기다.

DB 가 아니라 골든 파일(`tests/baseline/*.json`)을 읽는다. 비밀번호가 필요 없다.

    python tools/synergy_variance.py
    python tools/synergy_variance.py --trials 500 --min-n 10 --scrims
"""

import argparse
import glob
import io
import json
import math
import os
import random
from collections import defaultdict
from itertools import combinations

BASELINE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "tests", "baseline", "slot_*.json")

TEAM_SIZE = 4
RIDGE = 1.0          # 로지스틱 적합의 L2. 표본이 얇은 챔피언·팀을 0 쪽으로 당긴다
FIT_ITERS = 25       # 처음 적합
NULL_ITERS = 6       # 대조군은 참값에서 warm start 하므로 몇 번이면 수렴한다

SPECS = [
    ("synergy", 2, "같은 팀 2인 조합 시너지"),
    ("synergy", 3, "같은 팀 3인 조합 시너지"),
    ("counter", 2, "카운터 — D14 를 같은 방법으로 다시 잰다"),
]


# --------------------------------------------------------------- 자료 만들기

def load_games(include_scrims):
    """골든 파일에서 4v4 경기를 뽑는다. 한 경기 = 두 팀의 픽 + 승패 + 팀 신원.

    밴 수는 보지 않는다. D35 는 적재 규칙이고, 여기서 재는 것은 구성과 승패뿐이라
    2밴 경기도 같은 형식의 관측이다. 커리어가 다르면 같은 팀 번호도 다른 팀이므로
    팀 키는 (커리어, 팀 번호) 다. 스크림은 팀 번호가 전부 0(D54)이라 팀을 못 붙인다.
    """
    games = []
    for path in sorted(glob.glob(BASELINE)):
        career = os.path.basename(path).replace("slot_", "").replace(".json", "")[:6]
        data = json.load(io.open(path, encoding="utf-8"))

        for g in data["game_stats"]:
            blue, red = g["blue_pick"], g["red_pick"]
            if len(set(blue)) != TEAM_SIZE or len(set(red)) != TEAM_SIZE:
                continue
            blue_won = (g["win_team"] == 0)
            games.append({
                "career": career,
                "source": "official",
                "blue": list(blue),
                "red": list(red),
                "blue_team": (career, g["blue_team_id"]),
                "red_team": (career, g["red_team_id"]),
                "blue_won": blue_won,
            })

        if not include_scrims:
            continue
        for s in data["scrim_stats"]:
            if s.get("team_size") != TEAM_SIZE:
                continue
            blue = [c["champion"] for c in s["blue_stat"]]
            red = [c["champion"] for c in s["red_stat"]]
            if len(set(blue)) != TEAM_SIZE or len(set(red)) != TEAM_SIZE:
                continue
            if s["blue_score"] == s["red_score"]:
                continue
            games.append({
                "career": career,
                "source": "scrim",
                "blue": blue,
                "red": red,
                "blue_team": None,
                "red_team": None,
                "blue_won": s["blue_score"] > s["red_score"],
            })
    return games


def build_rows(games, champ_index, team_index, use_teams, seed=20260830):
    """모형이 먹는 꼴로 바꾼다.

    각 행은 (왼쪽 챔피언, 오른쪽 챔피언, 왼쪽 추가항, 오른쪽 추가항, y) 다.
    챔피언과 추가항(팀)을 나눠 두는 이유는 **조합 집계에는 챔피언만 써야** 하기
    때문이다. 좌우는 무작위로 뒤집는다 — 전부 승팀을 왼쪽에 두면 y 가 다 1이라
    모형이 서지 않는다. 씨앗을 고정해 두 모형이 같은 배치를 본다.
    """
    rnd = random.Random(seed)
    rows = []
    for g in games:
        blue = [champ_index[c] for c in g["blue"]]
        red = [champ_index[c] for c in g["red"]]
        bt = [team_index[g["blue_team"]]] if use_teams and g["blue_team"] else []
        rt = [team_index[g["red_team"]]] if use_teams and g["red_team"] else []
        y = 1 if g["blue_won"] else 0
        if rnd.random() < 0.5:
            rows.append((blue, red, bt, rt, y))
        else:
            rows.append((red, blue, rt, bt, 1 - y))
    return rows


# ------------------------------------------------------- 가법 모형 (귀무가설)

def fit_model(rows, nparam, ridge=RIDGE, warm=None, iters=FIT_ITERS):
    """P(왼쪽 승) = sigmoid( Σθ(왼쪽) − Σθ(오른쪽) ) 를 IRLS 로 적합한다.

    설계행렬의 각 행은 합이 0이라 θ 는 상수 이동만큼 자유롭다 — 능형(ridge)이
    그중 최소 노름 해를 고른다. 한 행에 0 아닌 성분이 10개 이하라 희소 누적으로
    X'WX 를 만든다.
    """
    theta = list(warm) if warm else [0.0] * nparam

    for _ in range(iters):
        ata = [[0.0] * nparam for _ in range(nparam)]
        atb = [0.0] * nparam
        for left, right, xl, xr, y in rows:
            terms = ([(i, 1.0) for i in left] + [(i, 1.0) for i in xl]
                     + [(i, -1.0) for i in right] + [(i, -1.0) for i in xr])
            eta = 0.0
            for i, s in terms:
                eta += s * theta[i]
            p = 1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, eta))))
            w = max(p * (1.0 - p), 1e-6)
            z = eta + (y - p) / w
            for i, si in terms:
                atb[i] += w * si * z
                row_i = ata[i]
                for j, sj in terms:
                    row_i[j] += w * si * sj
        for i in range(nparam):
            ata[i][i] += ridge

        new_theta = solve(ata, atb)
        shift = max(abs(new_theta[i] - theta[i]) for i in range(nparam))
        theta = new_theta
        if shift < 1e-8:
            break
    return theta


def solve(a, b):
    """부분 피벗 가우스 소거. 모수는 수십~백여 개라 이 정도면 충분하다."""
    k = len(b)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for col in range(k):
        piv = max(range(col, k), key=lambda r: abs(m[r][col]))
        if abs(m[piv][col]) < 1e-12:
            continue
        m[col], m[piv] = m[piv], m[col]
        inv = 1.0 / m[col][col]
        for r in range(k):
            if r == col:
                continue
            f = m[r][col] * inv
            if f == 0.0:
                continue
            mr, mc = m[r], m[col]
            for c in range(col, k + 1):
                mr[c] -= f * mc[c]
    return [m[i][k] / m[i][i] if abs(m[i][i]) > 1e-12 else 0.0 for i in range(k)]


def predict(rows, theta):
    """각 경기에서 '왼쪽이 이길' 모형 확률."""
    out = []
    for left, right, xl, xr, _ in rows:
        eta = 0.0
        for i in left:
            eta += theta[i]
        for i in xl:
            eta += theta[i]
        for i in right:
            eta -= theta[i]
        for i in xr:
            eta -= theta[i]
        out.append(1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, eta)))))
    return out


# ----------------------------------------------------------------- 집계 단위

def group_keys(mode, left, right, size):
    """한 경기에서 나오는 (집계 키, 그 키의 승패가 왼쪽 기준인가) 목록.

    synergy — 같은 팀 조합. 양쪽 팀 모두에서 뽑는다 (한 경기가 두 관측을 준다)
    counter — 서로 다른 팀의 챔피언 쌍. 방향이 있다 (A 기준 승률과 B 기준은 여집합)
    """
    out = []
    if mode == "synergy":
        for combo in combinations(sorted(left), size):
            out.append((combo, True))
        for combo in combinations(sorted(right), size):
            out.append((combo, False))
    else:
        for a in left:
            for b in right:
                out.append(((a, b), True))
                out.append(((b, a), False))
    return out


def collect(rows, probs, mode, size):
    """집계 키별로 관측 수·승수·모형 기대 확률의 합·포아송이항 분산을 모은다."""
    stats = defaultdict(lambda: {"n": 0, "wins": 0, "sum_p": 0.0, "sum_pq": 0.0})
    for (left, right, _, _, y), p in zip(rows, probs):
        for key, from_left in group_keys(mode, left, right, size):
            s = stats[key]
            s["n"] += 1
            s["wins"] += y if from_left else (1 - y)
            q = p if from_left else (1.0 - p)
            s["sum_p"] += q
            s["sum_pq"] += q * (1.0 - q)
    return stats


# -------------------------------------------------------------------- 분해

def decompose(stats, min_n):
    """관측 분산을 모형이 설명하는 몫 · 조합 고유 · 노이즈로 쪼갠다.

        obs  = 실제 승률
        exp  = 모형이 그 조합이 실제로 나온 경기들에서 준 평균 승률
        d    = obs − exp

    Var(d) 에서 표본 노이즈의 기대값을 뺀 나머지가 조합 고유 성분의 **원값**이다.
    노이즈는 이항 근사가 아니라 경기별 확률이 다른 포아송이항의 분산 Σp(1−p)/n².
    원값은 편향돼 있다 — 보정은 대조군이 한다(`null_pass`).
    """
    picked = [(k, s) for k, s in stats.items() if s["n"] >= min_n]
    if len(picked) < 2:
        return None

    obs = [s["wins"] / s["n"] for _, s in picked]
    exp = [s["sum_p"] / s["n"] for _, s in picked]
    noise = [s["sum_pq"] / (s["n"] ** 2) for _, s in picked]

    resid = [o - e for o, e in zip(obs, exp)]
    mean_noise = sum(noise) / len(noise)
    return {
        "groups": len(picked),
        "var_obs": variance(obs),
        "var_exp": variance(exp),
        "var_resid": variance(resid),
        "var_noise": mean_noise,
        "var_pair_raw": variance(resid) - mean_noise,
        "picked": picked,
        "resid": resid,
        "noise": noise,
    }


def variance(xs):
    m = sum(xs) / len(xs)
    return sum((x - m) ** 2 for x in xs) / len(xs)


# ------------------------------------------------------------------ 대조군

def null_pass(rows, probs, nparam, warm, min_n, trials, seed=20260830):
    """모수 부트스트랩 — 적합된 모형에서 승패만 다시 뽑아 같은 분해를 돌린다.

    이 세계에는 조합 고유 시너지가 정의상 0이다. 그런데도 나오는 값이
    **적합 자체가 만드는 편향**이다. 관측값에서 이 평균을 빼야 추정치가 된다.

    모형을 매 시행마다 다시 적합한다. 다시 적합하지 않으면 모형이 참 확률을 아는
    셈이 되어 편향이 잡히지 않는다 — 실제로는 우리도 데이터에서 추정했다.
    시행 하나로 세 측정(2인·3인·카운터)을 전부 계산한다. 적합이 비싸기 때문이다.
    """
    rnd = random.Random(seed)
    out = {(m, s): [] for m, s, _ in SPECS}
    for _ in range(trials):
        sim = [(left, right, xl, xr, 1 if rnd.random() < p else 0)
               for (left, right, xl, xr, _), p in zip(rows, probs)]
        theta = fit_model(sim, nparam, warm=warm, iters=NULL_ITERS)
        sim_probs = predict(sim, theta)
        for mode, size, _ in SPECS:
            dec = decompose(collect(sim, sim_probs, mode, size), min_n)
            if dec:
                out[(mode, size)].append(dec["var_pair_raw"])
    return out


def pvalue(observed, null_values):
    """관측값 이상이 대조군에서 나온 비율 (+1 보정)."""
    if not null_values:
        return float("nan")
    return (sum(1 for v in null_values if v >= observed) + 1) / (len(null_values) + 1)


# ------------------------------------------------------------------- 보고

def report(title, dec, nulls, min_n, champs, top):
    print("-" * 78)
    print(f"  {title}")
    print("-" * 78)
    if dec is None:
        print(f"    표본 {min_n} 이상인 조합이 2개 미만이다 — 분해할 것이 없다.")
        print()
        return

    floor = sum(nulls) / len(nulls) if nulls else 0.0
    floor_sd = math.sqrt(max(variance(nulls), 0.0)) if len(nulls) > 1 else 0.0
    corrected = dec["var_pair_raw"] - floor
    p = pvalue(dec["var_pair_raw"], nulls)

    total = max(dec["var_exp"], 0.0) + max(corrected, 0.0) + dec["var_noise"]
    def pct(v):
        return (max(v, 0.0) / total * 100.0) if total > 0 else 0.0

    print(f"    대상 조합 {dec['groups']}개 (n≥{min_n}) · 관측 승률 분산 {dec['var_obs']:.5f}")
    print()
    print("    | 출처                | 표준편차 | 분산 비중 |")
    print("    |---------------------|----------|-----------|")
    print(f"    | 모형이 설명 (강도)  | {math.sqrt(max(dec['var_exp'],0)):8.4f} |"
          f" {pct(dec['var_exp']):8.1f}% |")
    print(f"    | 조합 고유 (보정 후) | {math.sqrt(max(corrected,0)):8.4f} |"
          f" {pct(corrected):8.1f}% |")
    print(f"    | 순수 노이즈         | {math.sqrt(dec['var_noise']):8.4f} |"
          f" {pct(dec['var_noise']):8.1f}% |")
    print()
    print(f"    원값 {dec['var_pair_raw']:+.6f} − 바닥 {floor:+.6f}"
          f" (sd {floor_sd:.6f}, {len(nulls)}회) = **{corrected:+.6f}** · p = {p:.3f}")

    if corrected > 0 and p < 0.05:
        print("    판정: 조합 고유 성분이 대조군을 넘는다")
    else:
        print("    판정: 대조군과 구분되지 않는다 — 화면을 만들 근거가 없다")
    print()

    if corrected > 0:
        reliability(dec, corrected)
        top_groups(dec, corrected, champs, top)


def reliability(dec, var_pair):
    """조합 하나하나를 화면에 올릴 수 있는가 — 분산 비중과는 다른 질문이다.

    분산 비중은 "효과가 전체적으로 존재하는가" 를 답한다. 화면은 그것으로 못 만든다.
    화면은 **이 조합의 값이 얼마인가** 를 말해야 하고, 그 값의 신뢰도는

        r = 조합고유분산 / (조합고유분산 + 그 조합의 표본 노이즈)

    다. r 은 축소 계수와 같은 수다 — r=0.2 면 화면에 찍히는 값의 80% 가 0 쪽으로
    깎여 나간다는 뜻이고, 남은 20% 도 대부분 노이즈다. 표본이 몇 건 있어야
    r 이 0.5 가 되는지도 같이 낸다. 그 수를 못 넘기면 화면은 이르다.
    """
    rs = [var_pair / (var_pair + nz) for nz in dec["noise"]]
    need_half = 0.25 / var_pair          # noise ≈ 0.25/n 로 두고 r=0.5 가 되는 n
    need_third = 0.5 * 0.25 / var_pair   # r=1/3
    best_n = max(s["n"] for _, s in dec["picked"])
    print(f"    개별 조합 신뢰도 r — 최대 {max(rs):.2f} · 중앙값 {sorted(rs)[len(rs)//2]:.2f}"
          f" · r≥0.5 인 조합 {sum(1 for r in rs if r >= 0.5)}개"
          f" · r≥0.33 인 조합 {sum(1 for r in rs if r >= 1/3)}개")
    print(f"    r=0.5 에 필요한 표본 n≈{need_half:.0f} · r=0.33 에 n≈{need_third:.0f}"
          f" · 지금 가장 많이 쌓인 조합이 n={best_n}")
    print()


def top_groups(dec, var_pair, champs, n):
    """축소한 조합 고유 효과 상위 — 화면에 올릴 만한 것이 실제로 있는지 본다.

    축소는 D15 의 1단과 같은 꼴이다: 효과를 0 쪽으로 당기되 표본이 많으면 덜 당긴다.
    당기는 세기는 조합 고유 분산과 노이즈의 비로 정한다 (경험 베이즈).
    """
    rows = []
    for (key, s), r, nz in zip(dec["picked"], dec["resid"], dec["noise"]):
        shrunk = r * var_pair / (var_pair + nz)
        rows.append((shrunk, key, s["n"], s["wins"] / s["n"], s["sum_p"] / s["n"]))
    rows.sort(key=lambda t: -abs(t[0]))
    print("    축소한 조합 고유 효과 상위 (절대값 기준)")
    print("    | 조합 | n | 실제 | 모형 기대 | 축소 효과 |")
    print("    |------|---|------|-----------|-----------|")
    for shrunk, key, cnt, obs, exp in rows[:n]:
        name = " + ".join(champs[i] for i in key)
        print(f"    | {name} | {cnt} | {obs*100:.1f}% | {exp*100:.1f}%"
              f" | {shrunk*100:+.1f}%p |")
    print()


# -------------------------------------------------------------------- main

def run_variant(label, note, games, champs, champ_index, team_index,
                use_teams, args):
    nparam = len(champs) + (len(team_index) if use_teams else 0)
    rows = build_rows(games, champ_index, team_index, use_teams)
    theta = fit_model(rows, nparam)
    probs = predict(rows, theta)
    ll = sum(math.log(max(p if y else 1 - p, 1e-12))
             for (_, _, _, _, y), p in zip(rows, probs))

    print("=" * 78)
    print(f"모형 {label} — 모수 {nparam}개 · 로그가능도 {ll:.1f}"
          f" (50% 고정은 {len(rows)*math.log(0.5):.1f})")
    print(f"  {note}")
    print("=" * 78)
    strong = sorted(zip(champs, theta[:len(champs)]), key=lambda t: -t[1])
    print("  강한 챔피언 5: " + ", ".join(f"{c} {t:+.2f}" for c, t in strong[:5]))
    print("  약한 챔피언 5: " + ", ".join(f"{c} {t:+.2f}" for c, t in strong[-5:]))
    print()

    nulls = null_pass(rows, probs, nparam, theta, args.min_n, args.trials)
    for mode, size, title in SPECS:
        dec = decompose(collect(rows, probs, mode, size), args.min_n)
        report(title, dec, nulls[(mode, size)], args.min_n, champs, args.top)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-n", type=int, default=10)
    ap.add_argument("--trials", type=int, default=200)
    ap.add_argument("--scrims", action="store_true", help="스크림도 포함한다")
    ap.add_argument("--top", type=int, default=8)
    ap.add_argument("--only", choices=["champ", "team"], help="한 모형만 돌린다")
    args = ap.parse_args()

    games = load_games(args.scrims)
    champs = sorted({c for g in games for c in g["blue"] + g["red"]})
    champ_index = {c: i for i, c in enumerate(champs)}
    teams = sorted({t for g in games for t in (g["blue_team"], g["red_team"]) if t})
    team_index = {t: len(champs) + i for i, t in enumerate(teams)}

    by_career = defaultdict(int)
    by_source = defaultdict(int)
    for g in games:
        by_career[g["career"]] += 1
        by_source[g["source"]] += 1

    print("=" * 78)
    print("시너지 분산 분해 — /synergy 화면의 존폐를 가르는 측정")
    print("=" * 78)
    print(f"  경기 {len(games)}건 · 챔피언 {len(champs)}종 · 팀 {len(teams)}개"
          f" · 표본 기준 n≥{args.min_n} · 대조군 {args.trials}회")
    print(f"  커리어별 {dict(by_career)} · 종류별 {dict(by_source)}")
    print()

    if args.only != "team":
        run_variant("A: 챔피언 강도만", "팀 강도가 조합으로 새어 들어온다 — 상한값이다",
                    games, champs, champ_index, team_index, False, args)
    if args.only != "champ":
        run_variant("B: 챔피언 + 팀 강도", "선수단 강도를 뺀 값 — 이쪽이 진짜 시너지다",
                    games, champs, champ_index, team_index, True, args)


if __name__ == "__main__":
    main()
