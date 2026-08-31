package com.teamfighter.tfm.web.view;

import com.teamfighter.tfm.web.view.PairRow.Bucket;

import java.util.Comparator;
import java.util.List;

/**
 * 챔피언 화면의 칸 하나 — 상대하기 어려움 · 쉬움 · 듀오 시너지.
 *
 * <h2>칸이 비는 문제와, 문턱을 내려도 안 풀리는 이유</h2>
 *
 * 화면을 셋으로 나눈 순간 <b>자주 비는 칸</b>이 생겼다. 문턱을 0.15σ 에서 0.10σ 로
 * 내려 많이 나아졌지만(FOE 52→82 · ALLY 30→53), 그것으로 "칸마다 최소 다섯 줄" 이
 * 되지는 않는다 — <b>챔피언마다 잰 쌍의 수가 다르기 때문이다.</b> 어떤 챔피언은
 * 애초에 상대 쌍이 여섯뿐이고, 그중 셋만 방향이 맞다.
 *
 * <p>그래서 문턱과 채우기를 <b>따로</b> 둔다.
 *
 * <pre>
 *   rows   문턱을 넘은 줄.        확실하다고 말할 수 있는 것
 *   faint  문턱 아래로 채운 줄.   방향은 맞는데 작은 것 — 화면이 흐리게 그린다
 * </pre>
 *
 * <p>둘을 한 목록으로 합치지 않는 것이 핵심이다. 합치면 "확실한 것" 과 "그럴지도
 * 모르는 것" 이 같은 무게로 읽히고, 그건 문턱을 아예 없앤 것과 같다. 나눠 놓으면
 * 칸은 차는데 <b>어디까지가 근거인지가 화면에 남는다.</b>
 *
 * <p>채우는 줄도 아무거나 넣지 않는다. 방향이 맞아야 한다 — "상대하기 어려움" 칸에
 * 죽음이 <i>줄어드는</i> 챔피언을 채워 넣으면 칸의 제목이 거짓말이 된다.
 *
 * @param rows     문턱을 넘어 확실하다고 말할 수 있는 줄. 위에서부터 센 순서다
 * @param faint    문턱 아래지만 방향이 맞아 채워 넣은 줄. 화면이 흐리게 그린다
 * @param measured 그 편에서 값이 나온 쌍의 수. <b>문턱과 무관하게</b> 센다
 */
public record PairBucket(
        String title,
        String subtitle,
        String css,
        Bucket kind,
        List<PairRow> rows,
        List<PairRow> faint,
        int measured) {

    /**
     * 칸 하나에 적어도 이만큼은 채운다.
     *
     * <p>다섯인 이유는 <b>비교가 시작되는 수</b>여서다. 한둘이면 "이 챔피언이 유독
     * 그렇다" 인지 "다 그렇다" 인지 알 수 없고, 열이면 아래쪽은 어차피 안 읽힌다.
     * 잰 쌍이 다섯보다 적으면 있는 만큼만 나온다 — 없는 것을 지어내지는 않는다.
     */
    public static final int MIN_SHOWN = 5;

    public PairBucket {
        rows = List.copyOf(rows);
        faint = List.copyOf(faint);
    }

    /**
     * 그 편의 쌍들에서 칸 하나를 만든다.
     *
     * @param side  그 편(동료 또는 상대) 전부. 여기서 거른다
     * @param limit 확실한 줄의 최대 수. 채우는 줄은 {@link #MIN_SHOWN} 까지만 붙는다
     */
    public static PairBucket of(String title, String subtitle, String css,
                                Bucket kind, List<PairRow> side, int limit) {
        List<PairRow> hits = side.stream()
                .filter(row -> row.bucket() == kind)
                .sorted(Comparator.comparingDouble(PairRow::rankValue).reversed())
                .limit(limit)
                .toList();

        // 모자란 만큼만 채운다. 방향이 맞아야 하고(affinity > 0), 이미 다른 칸에
        // 들어간 줄은 안 가져온다 — 같은 챔피언이 두 칸에 나오면 화면이 모순된다.
        int shortfall = Math.max(0, MIN_SHOWN - hits.size());
        List<PairRow> faint = shortfall == 0 ? List.of()
                : side.stream()
                        .filter(row -> row.bucket() == Bucket.NEUTRAL)
                        .filter(row -> row.affinity(kind) > 0)
                        .sorted(Comparator.comparingDouble(
                                (PairRow row) -> row.affinity(kind)).reversed())
                        .limit(shortfall)
                        .toList();

        return new PairBucket(title, subtitle, css, kind, hits, faint, side.size());
    }

    /** 그릴 줄이 하나도 없는가. */
    public boolean isEmpty() {
        return rows.isEmpty() && faint.isEmpty();
    }

    /**
     * 쟀는데 문턱을 넘은 것이 하나도 없는가.
     *
     * <p>화면이 이 경우에 한 줄 덧붙인다. 흐린 줄만 있는 칸을 말없이 그리면 읽는
     * 사람은 그것이 확실한 목록인 줄 안다.
     */
    public boolean hasNoConfidentRows() {
        return rows.isEmpty() && !faint.isEmpty();
    }

    /** 아무것도 못 잰 칸인가. "문턱 아래" 와 다른 상태다 — 화면 문구가 다르다. */
    public boolean isUnmeasured() {
        return isEmpty() && measured == 0;
    }
}
