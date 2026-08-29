"""챔피언별 경기력으로 시너지·카운터를 잰다 — D62 결정 5 의 본론.

**묻는 것이 다르다.** 지금까지는 "이 조합이 있으면 팀이 이기나" 를 물었다. 여기서는
**"이 챔피언 옆에 저 챔피언이 서면 이 챔피언이 더 잘하나"** 를 묻는다. 사용자의 가설이
바로 그것이다 — 스킬이 맞물린다는 말은 승률이 아니라 **출력**에 대한 주장이다.

**통로가 8배 넓다.** 경기 하나가 승패로는 1비트지만, `champ_stat` 으로는 **챔피언 8명 ×
(딜·탱·힐·킬·데스·어시)** 다. 805경기 = 6,440 관측이다.

**모형.** 한 관측은 "이 경기에서 이 챔피언이 낸 출력" 이다. 챔피언마다 딜의 규모가
다르므로(탱커와 법사) **학습 폴드 안에서 챔피언별로 표준화**한 z 를 목표로 쓴다.
그러면 챔피언 주효과가 0 이 되고 남는 것만 본다.

    z(A, 경기) = 팀강도 + Σ_동료B 시너지(A←B) + Σ_상대C 카운터(A←C) + 잡음

    시너지(A←B)  "B 가 같은 팀이면 A 의 출력이 얼마나 달라지나" — 방향이 있다
    카운터(A←C)  "C 가 상대면 A 의 출력이 얼마나 눌리나"      — 방향이 있다

**방향이 있다는 것이 중요하다.** 승패로는 `A+B` 가 한 덩어리라 누가 누구를 살렸는지
알 수 없었다. 출력으로 보면 **"Chef 가 있으면 Werewolf 의 딜이 오른다"** 처럼 비대칭이
그대로 나온다. 밴픽에서 실제로 쓰는 지식이 이쪽이다.

**주의 — 몫이 아니라 절대값을 쓴다.** 팀 안의 딜 점유율을 쓰면 A 가 더 하면 B 가 덜 하는
기계적 음의 상관이 생겨 그것이 "역시너지" 로 보인다. 절대값에는 그 함정이 없다.

    python tools/perf_champion.py
    python tools/perf_champion.py --metric tanking --seed 3
"""

import argparse
import glob
import io
import json
import math
import os
import random
import re
from collections import defaultdict

METRICS = ("dealing", "tanking", "healing", "kill", "death", "assist")

BASELINE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "tests", "baseline", "slot_*.json")

SEED_SQL = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "resources", "db", "migration", "V3__seed_champions.sql")

TEAM_SIZE = 4
BASE_GRID = [4.0, 16.0, 64.0]
EXTRA_GRID = [16.0, 64.0, 256.0, 1024.0]
SWEEPS = 60

MODELS = [
    ("P0", "챔피언 평균만", ()),
    ("P1", "+ 팀 강도", ("team",)),
    ("P2", "+ 동료 (시너지)", ("team", "mate")),
    ("P3", "+ 상대 (카운터)", ("team", "foe")),
    ("P4", "+ 동료 + 상대", ("team", "mate", "foe")),
]


def load_roles():
    """챔피언 → 역할군. **원본은 `V3__seed_champions.sql` 하나뿐이다**(D05).

    여기에 표를 복제하면 원본이 둘이 된다. 시드가 바뀌면 이 스크립트가 조용히
    옛 매핑으로 재게 되므로, 파일에서 읽는다.
    """
    text = io.open(SEED_SQL, encoding="utf-8").read()
    roles = {}
    for m in re.finditer(r"\('([A-Za-z]+)',\s*'[^']*',\s*'([A-Z]+)'\)", text):
        roles[m.group(1)] = m.group(2)
    if len(roles) != 40:
        raise SystemExit(f"역할군 시드가 40종이 아니다: {len(roles)}종. 시드를 확인하라")
    return roles


ROLES = None


# --------------------------------------------------------------- 자료 만들기

def load_rows(metric, include_scrims):
    """한 행 = (경기에 나온 챔피언 하나). 동료 3명과 상대 4명을 같이 들고 있다."""
    rows = []
    for path in sorted(glob.glob(BASELINE)):
        career = os.path.basename(path).replace("slot_", "").replace(".json", "")[:6]
        data = json.load(io.open(path, encoding="utf-8"))

        for g in data["game_stats"]:
            blue, red = g["blue_pick"], g["red_pick"]
            if len(set(blue)) != TEAM_SIZE or len(set(red)) != TEAM_SIZE:
                continue
            by_champ = {c["champion"]: c for c in g["champ_stat"]}
            if not set(blue + red) <= set(by_champ):
                continue                      # D20 — 이름으로 맞춘다. 안 맞으면 버린다
            for side, mates, foes, team in ((blue, blue, red, g["blue_team_id"]),
                                            (red, red, blue, g["red_team_id"])):
                for champ in side:
                    rows.append({
                        "champ": champ,
                        "team": (career, team),
                        "mates": [c for c in mates if c != champ],
                        "foes": list(foes),
                        "value": float(by_champ[champ][metric]),
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
            for stats, mates, foes in ((s["blue_stat"], blue, red),
                                       (s["red_stat"], red, blue)):
                for c in stats:
                    rows.append({
                        "champ": c["champion"],
                        "team": None,
                        "mates": [x for x in mates if x != c["champion"]],
                        "foes": list(foes),
                        "value": float(c[metric]),
                    })
    return rows


def standardize(train, test):
    """챔피언별 평균·표준편차를 **학습 폴드에서만** 구해 둘 다에 적용한다.

    검증 폴드의 값으로 표준화하면 정답을 훔쳐보는 것이 된다. 학습에 없던 챔피언은
    전체 평균·표준편차를 쓴다.
    """
    acc = defaultdict(list)
    for r in train:
        acc[r["champ"]].append(r["value"])

    stat = {}
    all_vals = [r["value"] for r in train]
    g_mean = sum(all_vals) / len(all_vals)
    g_sd = math.sqrt(sum((v - g_mean) ** 2 for v in all_vals) / len(all_vals)) or 1.0
    for champ, vals in acc.items():
        m = sum(vals) / len(vals)
        sd = math.sqrt(sum((v - m) ** 2 for v in vals) / len(vals))
        stat[champ] = (m, sd if sd > 1e-9 else g_sd)

    def z(rs):
        out = []
        for r in rs:
            m, sd = stat.get(r["champ"], (g_mean, g_sd))
            out.append((r, (r["value"] - m) / sd))
        return out
    return z(train), z(test)


# ------------------------------------------------------------------ 설계행렬

def build(rows_z, blocks, index=None):
    """(특성 번호, 값) 목록. 새 인덱스를 만들 수도, 기존 것에 투영할 수도 있다."""
    grow = index is None
    if grow:
        index = {}

    def feat(key):
        if key in index:
            return index[key]
        if not grow:
            return None
        index[key] = len(index)
        return index[key]

    out = []
    for r, z in rows_z:
        terms = []

        def add(key):
            j = feat(key)
            if j is not None:
                terms.append((j, 1.0))

        if "team" in blocks and r["team"]:
            add(("t", r["team"]))
        if "mate" in blocks:
            for b in r["mates"]:
                add(("m", r["champ"], b))
        if "foe" in blocks:
            for c in r["foes"]:
                add(("f", r["champ"], c))
        if "mrole" in blocks:
            for b in r["mates"]:
                add(("mr", ROLES[r["champ"]], ROLES[b]))
        if "frole" in blocks:
            for c in r["foes"]:
                add(("fr", ROLES[r["champ"]], ROLES[c]))
        out.append((terms, z))
    return out, index


def block_of_key(index):
    kinds = [None] * len(index)
    for key, j in index.items():
        kinds[j] = "team" if key[0] == "t" else "extra"
    return kinds


# -------------------------------------------------------------------- 적합

def fit(rows, nparam, ridge):
    theta = [0.0] * nparam
    resid = [z for _, z in rows]

    by_feat = defaultdict(list)
    for i, (terms, _) in enumerate(rows):
        for j, _s in terms:
            by_feat[j].append(i)

    denom = {j: float(len(cells)) + ridge[j] for j, cells in by_feat.items()}
    for _ in range(SWEEPS):
        moved = 0.0
        for j, cells in by_feat.items():
            num = 0.0
            for i in cells:
                num += resid[i]
            delta = (num - ridge[j] * theta[j]) / denom[j]
            if delta == 0.0:
                continue
            theta[j] += delta
            for i in cells:
                resid[i] -= delta
            moved = max(moved, abs(delta))
        if moved < 1e-9:
            break
    return theta


def sq_errors(rows, theta):
    out = []
    for terms, z in rows:
        pred = 0.0
        for j, _s in terms:
            pred += theta[j]
        out.append((z - pred) ** 2)
    return out


# ---------------------------------------------------------------------- CV

def cross_validate(rows, blocks, folds, base_ridge, extra_ridge, seed):
    """경기 단위가 아니라 **행 단위**로 나누면 같은 경기가 학습·검증에 걸친다.
    그러면 팀 효과가 새므로 **경기 단위로 나눈다** — 한 경기의 8행은 같은 폴드에 있다.
    """
    per_match = defaultdict(list)
    for i, r in enumerate(rows):
        per_match[r["match"]].append(i)
    keys = sorted(per_match)
    rnd = random.Random(seed)
    rnd.shuffle(keys)

    err = [0.0] * len(rows)
    for f in range(folds):
        test_keys = set(keys[f::folds])
        train_i = [i for k in keys if k not in test_keys for i in per_match[k]]
        test_i = [i for k in test_keys for i in per_match[k]]
        train = [rows[i] for i in train_i]
        test = [rows[i] for i in test_i]

        tr_z, te_z = standardize(train, test)

        if not blocks:
            for i, (_, z) in zip(test_i, te_z):
                err[i] = z * z
            continue

        tr_rows, index = build(tr_z, blocks)
        kinds = block_of_key(index)
        ridge = [base_ridge if k == "team" else extra_ridge for k in kinds]
        theta = fit(tr_rows, len(index), ridge)

        te_rows, _ = build(te_z, blocks, index)
        for i, e in zip(test_i, sq_errors(te_rows, theta)):
            err[i] = e
    return err


def mean(xs):
    return sum(xs) / len(xs)


def paired_t(gain):
    m = mean(gain)
    var = sum((x - m) ** 2 for x in gain) / (len(gain) - 1)
    se = math.sqrt(var / len(gain))
    return m, (m / se if se > 0 else 0.0)


# -------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--metric", choices=METRICS, default="dealing")
    ap.add_argument("--folds", type=int, default=5)
    ap.add_argument("--scrims", action="store_true")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--limit", type=int, default=0,
                    help="경기 수를 앞에서 N 건으로 자른다 (한 시즌 분량 확인용)")
    args = ap.parse_args()

    global ROLES
    ROLES = load_roles()
    rows = load_rows(args.metric, args.scrims)
    # 경기 식별자를 붙인다 (연속한 8행이 한 경기다)
    for i, r in enumerate(rows):
        r["match"] = i // (TEAM_SIZE * 2)
    if args.limit:
        rows = [r for r in rows if r["match"] < args.limit]

    print("=" * 78)
    print(f"챔피언별 경기력으로 본 시너지·카운터 — 지표 `{args.metric}`")
    print("=" * 78)
    print(f"  관측 {len(rows)}행 = 경기 {len(rows)//8}건 × 챔피언 8명"
          f" · {args.folds}겹 · 씨앗 {args.seed}")
    print("  목표는 챔피언별 표준화 z. 값은 held-out 제곱오차의 평균(작을수록 좋다).")
    print("  z 의 분산이 1 이므로 '챔피언 평균만' 모형이 1.0 근처다.")
    print()

    results = {}
    for tag, label, blocks in MODELS:
        best = None
        if not blocks:
            grid = [(0.0, 0.0)]
        elif set(blocks) <= {"team"}:
            grid = [(b, 0.0) for b in BASE_GRID]
        else:
            grid = [(b, e) for b in BASE_GRID for e in EXTRA_GRID]
        for lb, le in grid:
            err = cross_validate(rows, blocks, args.folds, lb, le, args.seed)
            if best is None or mean(err) < mean(best[0]):
                best = (err, lb, le)
        results[tag] = best
        err, lb, le = best
        r2 = 1.0 - mean(err) / mean(results["P0"][0])
        cfg = "—" if not blocks else (f"λ팀={lb:.0f}" +
                                      (f" λ조합={le:.0f}" if le else ""))
        print(f"  {tag} {label:<16} 오차 {mean(err):.4f}  R² {r2:+.4f}  {cfg}")
    print()

    print("-" * 78)
    print("  짝지은 비교 — 관측 단위가 경기가 아니라 **챔피언-경기** 다")
    print("-" * 78)
    print("  | 성분 | 비교 | 오차 감소 | t |")
    print("  |---|---|---|---|")
    for label, base, full in (("팀 강도", "P0", "P1"),
                              ("동료 효과 (시너지)", "P1", "P2"),
                              ("상대 효과 (카운터)", "P1", "P3"),
                              ("둘 다", "P1", "P4")):
        gain = [b - f for b, f in zip(results[base][0], results[full][0])]
        m, t = paired_t(gain)
        verdict = "**있다**" if t > 2 else ("없다" if t > -2 else "**해롭다**")
        print(f"  | {label} | {base}→{full} | {m:+.5f} | {t:+.2f} | {verdict}")
    print()
    print("  방향이 있는 효과다 — '동료 효과' 는 A←B 와 B←A 를 따로 잡는다.")
    print("  승패로는 A+B 가 한 덩어리라 누가 누구를 살렸는지 알 수 없었다.")


if __name__ == "__main__":
    main()
