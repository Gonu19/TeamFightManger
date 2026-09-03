package com.teamfighter.tfm.analysis.scrim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 스크림에서 <b>해 볼 만한 덱</b>을 고른다.
 *
 * <h2>무엇을 추천하는가 — 「세다」가 아니라 「모른다」다</h2>
 *
 * 이 클래스는 <b>어떤 덱이 강한지 말하지 않는다.</b> 말할 근거가 없기 때문이다 —
 * 4인 조합은 실측 최다 9경기라 통계가 아니고(D11), 시너지가 실재한다고 확인된 것은
 * <b>2인까지</b>다(D60). 4인 덱을 "좋다" 고 부르는 순간 그건 우리가 지은 말이 된다.
 *
 * <p>대신 말할 수 있는 것이 있다. <b>이 넷을 뽑으면 모르는 쌍 몇 개를 한 번에 보는가.</b>
 * 4인을 뽑으면 같은 편 듀오가 {@code C(4,2) = 6}쌍 생긴다. 그 여섯 중 아직 아무 데서도
 * 안 나온 쌍이 많을수록 그 한 판이 <b>많이 알려준다.</b> 그것이 이 추천의 전부다.
 *
 * <p>그래서 이 화면이 답하는 질문은 "무엇이 세냐" 가 아니라
 * <b>"무엇을 아직 모르냐"</b> 이고, 그 질문의 답이 곧 스크림에서 할 일이다.
 *
 * <h2>왜 이 계산이 필요한가</h2>
 *
 * 티어와 쌍 효과 화면은 관측이 충분한 것만 보여준다. 그래서 화면을 아무리 봐도
 * <b>안 해 본 조합은 영영 안 보인다.</b> 실측(슬롯 1): 듀오 780쌍 중 305쌍(39%)이
 * 공식전에도 스크림에도 한 번도 같이 안 나왔다. 그 305쌍은 나쁜 조합이 아니라
 * <b>모르는 조합</b>이다 (D62).
 */
public final class ScrimSuggester {

    /** 한 덱의 챔피언 수. 이 게임은 팀당 4픽 고정이다 (D35). */
    public static final int DECK_SIZE = 4;

    /**
     * 한 덱이 만드는 듀오 쌍의 수 — {@code C(4,2)}.
     *
     * <p>이 값이 추천의 단위다. 덱 하나를 뽑으면 여섯 쌍을 동시에 관측한다.
     */
    public static final int PAIRS_PER_DECK = DECK_SIZE * (DECK_SIZE - 1) / 2;

    /**
     * 한 덱에 있어야 하는 최소 역할군 수.
     *
     * <p><b>잰 값이다.</b> 슬롯 1의 공식전 라인업 588개를 세어 보니 역할군 2종이 13.6%,
     * 3종이 60.4%, 4종이 26.0% 였고 <b>1종은 0건</b>이었다. 한 역할군으로만 채운 덱은
     * 실제로 아무도 안 쓴다 — 그런 덱을 추천하면 첫 줄에서 신뢰를 잃는다.
     *
     * <p>3종을 요구하지 않는 이유는 2종이 실제로 13.6% 나오기 때문이다.
     * 관측된 것을 금지하지 않는다.
     */
    private static final int MIN_ROLES = 2;

    private ScrimSuggester() {
    }

    /**
     * 덱을 고른다. <b>같은 챔피언을 두 덱에 넣지 않는다.</b>
     *
     * <p>겹치면 덱들이 서로 닮아 보이고, 그러면 목록이 넷이어도 고를 것은 하나다.
     * 챔피언이 40종이라 넷씩 열 덱까지는 안 겹치고 만들 수 있다.
     *
     * <p><b>결정적이다.</b> 같은 입력이면 같은 순서로 같은 덱이 나온다 — 같은 값에서
     * 챔피언 번호로 갈라 준다. 새로고침할 때마다 추천이 바뀌면 사용자는 그것을
     * 추천이 아니라 잡음으로 읽는다.
     *
     * @param pool      고를 수 있는 챔피언. 호출자가 이미 걸러 온 것을 그대로 쓴다
     * @param coverage  무엇을 이미 봤나
     * @param deckCount 만들 덱 수
     * @return 발굴 효율이 높은 순. 만들 수 없으면 빈 목록
     */
    public static List<ScrimDeck> suggest(List<ScrimCandidate> pool,
                                          PairCoverage coverage,
                                          int deckCount) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(coverage, "coverage");
        if (deckCount <= 0 || pool.size() < DECK_SIZE) {
            return List.of();
        }

        // 덜 나온 챔피언을 먼저 본다 — 발굴이 목적이므로 출전이 적을수록 값이 크다.
        // 같은 출전 수에서는 번호로 갈라 결정적으로 만든다.
        List<ScrimCandidate> ordered = new ArrayList<>(pool);
        ordered.sort(Comparator.comparingInt(ScrimCandidate::games)
                .thenComparingInt(ScrimCandidate::championId));

        Set<Integer> used = new HashSet<>();
        List<ScrimDeck> decks = new ArrayList<>();

        while (decks.size() < deckCount) {
            List<ScrimCandidate> deck = buildOne(ordered, coverage, used);
            if (deck == null) {
                break;                                          // 남은 챔피언으로는 더 못 만든다
            }
            deck.forEach(c -> used.add(c.championId()));
            decks.add(score(deck, coverage));
        }

        // 많이 알려주는 순으로. 같으면 스크림에서 덜 해 본 순, 그것도 같으면 만든 순.
        decks.sort(Comparator.comparingInt(ScrimDeck::unexploredPairs).reversed()
                .thenComparingInt(ScrimDeck::scrimTriedPairs));
        return List.copyOf(decks);
    }

    /**
     * 덱 하나를 탐욕적으로 만든다.
     *
     * <p>씨앗은 <b>아직 안 쓴 챔피언 중 가장 덜 나온</b> 하나다. 거기서부터 세 번,
     * 지금까지 고른 것들과 <b>모르는 쌍을 가장 많이 만드는</b> 챔피언을 붙인다.
     *
     * <p>탐욕법이라 최적해가 아니다. 최적을 찾으려면 40개에서 4개를 고르는
     * 91,390가지를 다 봐야 하는데, 그렇게 얻는 것이 "쌍 한두 개 더" 뿐이라
     * 값어치가 없다 — 어차피 여섯 쌍 중 몇이냐를 말하는 화면이다.
     */
    private static List<ScrimCandidate> buildOne(List<ScrimCandidate> ordered,
                                                 PairCoverage coverage,
                                                 Set<Integer> used) {
        ScrimCandidate seed = ordered.stream()
                .filter(c -> !used.contains(c.championId()))
                .findFirst()
                .orElse(null);
        if (seed == null) {
            return null;
        }

        List<ScrimCandidate> deck = new ArrayList<>();
        deck.add(seed);

        while (deck.size() < DECK_SIZE) {
            ScrimCandidate best = null;
            int bestGain = -1;
            for (ScrimCandidate c : ordered) {
                if (used.contains(c.championId()) || contains(deck, c)) {
                    continue;
                }
                // 마지막 자리는 역할군 조건을 지킬 수 있는 후보만 본다.
                if (deck.size() == DECK_SIZE - 1 && roles(deck, c) < MIN_ROLES) {
                    continue;
                }
                int gain = 0;
                for (ScrimCandidate in : deck) {
                    if (coverage.unexplored(in.championId(), c.championId())) {
                        gain++;
                    }
                }
                // ordered 가 이미 "덜 나온 순" 이라 동점이면 앞의 것이 이긴다.
                if (gain > bestGain) {
                    bestGain = gain;
                    best = c;
                }
            }
            if (best == null) {
                return null;                                    // 역할군 조건을 채울 후보가 없다
            }
            deck.add(best);
        }
        return deck;
    }

    private static boolean contains(List<ScrimCandidate> deck, ScrimCandidate c) {
        return deck.stream().anyMatch(x -> x.championId() == c.championId());
    }

    /** 이 덱에 {@code extra} 를 넣었을 때의 역할군 수. */
    private static int roles(List<ScrimCandidate> deck, ScrimCandidate extra) {
        Set<String> seen = new HashSet<>();
        deck.forEach(c -> seen.add(c.category()));
        seen.add(extra.category());
        return seen.size();
    }

    /** 여섯 쌍을 세 갈래로 나눈다: 모르는 것 · 스크림에서 해 본 것 · 공식전으로 아는 것. */
    private static ScrimDeck score(List<ScrimCandidate> deck, PairCoverage coverage) {
        int unexplored = 0;
        int scrimTried = 0;
        int known = 0;
        for (int i = 0; i < deck.size(); i++) {
            for (int j = i + 1; j < deck.size(); j++) {
                int a = deck.get(i).championId();
                int b = deck.get(j).championId();
                if (coverage.knownFromOfficial(a, b)) {
                    known++;
                } else if (coverage.scrimTimes(a, b) > 0) {
                    scrimTried++;
                } else {
                    unexplored++;
                }
            }
        }
        return new ScrimDeck(List.copyOf(deck), unexplored, scrimTried, known);
    }
}
