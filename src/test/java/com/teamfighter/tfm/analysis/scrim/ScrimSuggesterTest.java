package com.teamfighter.tfm.analysis.scrim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스크림 추천의 규칙.
 *
 * <p>DB 를 안 쓴다. 추천이 순수 함수라 그렇고, 그래야 <b>변조로 검증</b>할 수 있다 —
 * 규칙 하나를 일부러 깨뜨렸을 때 실패하는지가 이 파일이 지키는 것이다.
 */
class ScrimSuggesterTest {

    private static final String MELEE = "MELEE";
    private static final String RANGER = "RANGER";

    private static ScrimCandidate champ(int id, String category, int games) {
        return new ScrimCandidate(id, "C" + id, "챔프" + id, category, games);
    }

    private static CounterPick counter(int id, String category, String target,
                                       double effect, int observations) {
        return new CounterPick(id, "C" + id, "챔프" + id, category, 30,
                target, effect, observations);
    }

    /** 역할군이 번갈아 붙는 후보 {@code n} 명. 출전은 전부 0이다. */
    private static List<ScrimCandidate> pool(int n) {
        List<ScrimCandidate> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(champ(i, i % 2 == 0 ? MELEE : RANGER, 0));
        }
        return out;
    }

    private static PairCoverage nothingSeen() {
        return PairCoverage.empty();
    }

    @Test
    @DisplayName("덱은 넷이고, 여섯 쌍이 세 갈래로 남김없이 나뉜다")
    void deckIsFourAndPairsSumToSix() {
        List<ScrimDeck> decks = ScrimSuggester.suggest(
                List.of(counter(1, RANGER, "늑대인간", 0.16, 11)),
                pool(8), nothingSeen(), 1);

        assertThat(decks).hasSize(1);
        ScrimDeck deck = decks.get(0);
        assertThat(deck.slots()).hasSize(ScrimSuggester.DECK_SIZE);
        assertThat(deck.unexploredPairs() + deck.scrimTriedPairs() + deck.knownPairs())
                .isEqualTo(ScrimSuggester.PAIRS_PER_DECK);
    }

    /**
     * <b>이 화면의 결론이다.</b> 40번 본 상성은 이미 아는 것이라 스크림에서 확인할
     * 것이 없다. 11번 본 것이 흔들리므로 그쪽이 올라와야 한다.
     */
    @Test
    @DisplayName("가장 센 카운터가 아니라 가장 덜 확인된 카운터를 올린다")
    void thinnestCounterComesFirst() {
        List<CounterPick> counters = List.of(
                counter(1, RANGER, "늑대인간", 0.40, 90),   // 제일 세지만 이미 안다
                counter(2, MELEE, "늑대인간", 0.12, 11));   // 약하지만 안 확인됐다

        List<ScrimDeck> decks = ScrimSuggester.suggest(counters, pool(8), nothingSeen(), 1);

        assertThat(decks.get(0).slots().get(0).championId()).isEqualTo(2);
    }

    /**
     * 같은 상대를 셋이 잡는 덱은 하나가 잡는 덱보다 나을 것이 없다.
     */
    @Test
    @DisplayName("한 덱 안에서 같은 대상을 두 번 잡지 않는다")
    void oneTargetPerDeck() {
        List<CounterPick> counters = List.of(
                counter(1, RANGER, "늑대인간", 0.20, 11),
                counter(2, MELEE, "늑대인간", 0.19, 12),
                counter(3, RANGER, "늑대인간", 0.18, 13));

        ScrimDeck deck = ScrimSuggester.suggest(counters, pool(8), nothingSeen(), 1).get(0);

        assertThat(deck.targets()).containsExactly("늑대인간");
        assertThat(deck.slots()).filteredOn(ScrimDeck.DeckSlot::isCounter).hasSize(1);
    }

    @Test
    @DisplayName("대상이 다르면 여러 카운터가 한 덱에 들어간다")
    void differentTargetsShareADeck() {
        List<CounterPick> counters = List.of(
                counter(1, RANGER, "늑대인간", 0.20, 11),
                counter(2, MELEE, "네크로맨서", 0.19, 12));

        ScrimDeck deck = ScrimSuggester.suggest(counters, pool(8), nothingSeen(), 1).get(0);

        assertThat(deck.targets()).containsExactlyInAnyOrder("늑대인간", "네크로맨서");
    }

    /**
     * 카운터가 넷에 못 미치면 남는 자리는 발굴로 채운다 — 근거가 있어서가 아니라
     * <b>근거가 없다는 사실</b>이 이유다 (D62).
     */
    @Test
    @DisplayName("카운터가 모자라면 발굴 자리로 채운다")
    void fillsWithDiscoveryWhenCountersRunOut() {
        ScrimDeck deck = ScrimSuggester.suggest(
                List.of(counter(1, RANGER, "늑대인간", 0.2, 11)),
                pool(8), nothingSeen(), 1).get(0);

        assertThat(deck.slots()).hasSize(4);
        assertThat(deck.slots()).filteredOn(ScrimDeck.DeckSlot::isCounter).hasSize(1);
        assertThat(deck.slots()).filteredOn(s -> !s.isCounter()).hasSize(3);
    }

    /** 카운터가 하나도 없으면 추천할 것이 없다 — 발굴만으로 덱을 만들지 않는다. */
    @Test
    @DisplayName("카운터가 없으면 아무것도 추천하지 않는다")
    void noCountersNoDecks() {
        assertThat(ScrimSuggester.suggest(List.of(), pool(8), nothingSeen(), 2)).isEmpty();
    }

    @Test
    @DisplayName("덱끼리 챔피언이 겹치지 않는다")
    void decksDoNotShareChampions() {
        List<CounterPick> counters = List.of(
                counter(1, RANGER, "늑대인간", 0.2, 11),
                counter(2, MELEE, "네크로맨서", 0.2, 12),
                counter(3, RANGER, "궁수", 0.2, 13));

        List<ScrimDeck> decks = ScrimSuggester.suggest(counters, pool(12), nothingSeen(), 3);

        List<Integer> all = decks.stream()
                .flatMap(d -> d.slots().stream())
                .map(ScrimDeck.DeckSlot::championId)
                .toList();

        assertThat(all).doesNotHaveDuplicates();
    }

    /**
     * 실측 라인업 588개 중 역할군 1종은 <b>0건</b>이었다. 아무도 안 쓰는 덱을
     * 추천하면 첫 줄에서 신뢰를 잃는다.
     */
    @Test
    @DisplayName("한 역할군으로만 채운 덱은 안 만든다")
    void deckNeverHasASingleRole() {
        List<ScrimCandidate> pool = List.of(
                champ(1, MELEE, 0), champ(2, MELEE, 0), champ(3, MELEE, 0),
                champ(4, MELEE, 0), champ(5, MELEE, 0),
                champ(6, RANGER, 0), champ(7, RANGER, 0), champ(8, RANGER, 0));

        List<ScrimDeck> decks = ScrimSuggester.suggest(
                List.of(counter(1, MELEE, "늑대인간", 0.2, 11)), pool, nothingSeen(), 1);

        assertThat(decks).isNotEmpty();
        for (ScrimDeck deck : decks) {
            // 넷을 두 종으로 채우면 중복은 당연히 생긴다. 보는 것은 <b>종의 수</b>다.
            long roles = deck.slots().stream()
                    .map(ScrimDeck.DeckSlot::category).distinct().count();
            assertThat(roles).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("이미 아는 쌍은 미지로 세지 않는다")
    void knownPairsAreNotCountedAsUnexplored() {
        // 1-2 는 공식전으로 알고, 3-4 는 스크림에서 해 봤다.
        PairCoverage coverage = PairCoverage.of(
                List.of(new long[]{1, 2, 0}),
                List.of(new long[]{3, 4, 5}));

        ScrimDeck deck = ScrimSuggester.suggest(
                List.of(counter(1, RANGER, "늑대인간", 0.2, 11)),
                pool(4), coverage, 1).get(0);

        assertThat(deck.knownPairs()).isEqualTo(1);
        assertThat(deck.scrimTriedPairs()).isEqualTo(1);
        assertThat(deck.unexploredPairs()).isEqualTo(4);
    }

    /**
     * 새로고침할 때마다 추천이 바뀌면 사용자는 그것을 추천이 아니라 잡음으로 읽는다.
     */
    @Test
    @DisplayName("같은 입력이면 같은 덱이 나온다 — 결정적이다")
    void suggestionIsDeterministic() {
        List<CounterPick> counters = List.of(
                counter(1, RANGER, "늑대인간", 0.2, 11),
                counter(2, MELEE, "네크로맨서", 0.2, 11));

        assertThat(ScrimSuggester.suggest(counters, pool(16), nothingSeen(), 3))
                .isEqualTo(ScrimSuggester.suggest(counters, pool(16), nothingSeen(), 3));
    }

    /**
     * 한 챔피언이 여러 상위를 잡으면 <b>가장 덜 확인된</b> 줄이 대표가 된다.
     * 가장 센 줄을 남기면 이미 확인한 주장이 대표가 되어, 정작 스크림에서 확인할
     * 것이 화면에서 사라진다.
     */
    @Test
    @DisplayName("한 챔피언의 여러 대상 중 덜 확인된 쪽을 남긴다")
    void representativeCounterIsTheThinnest() {
        List<CounterPick> counters = List.of(
                counter(1, RANGER, "늑대인간", 0.40, 80),
                counter(1, RANGER, "네크로맨서", 0.12, 11));

        ScrimDeck deck = ScrimSuggester.suggest(counters, pool(8), nothingSeen(), 1).get(0);

        assertThat(deck.slots().get(0).targetNameKo()).isEqualTo("네크로맨서");
        assertThat(deck.slots().get(0).observations()).isEqualTo(11);
    }

    @Test
    @DisplayName("표본이 두꺼운 카운터에는 「확인 필요」가 안 붙는다")
    void thickCounterIsNotFlagged() {
        ScrimDeck deck = ScrimSuggester.suggest(
                List.of(counter(1, RANGER, "늑대인간", 0.2, 90)),
                pool(8), nothingSeen(), 1).get(0);

        assertThat(deck.slots().get(0).worthTesting()).isFalse();
        assertThat(deck.slots().get(0).effectText()).isEqualTo("+0.20σ");
    }

    @Test
    @DisplayName("후보가 넷에 못 미치면 아무것도 추천하지 않는다")
    void tooFewCandidatesYieldNothing() {
        List<CounterPick> counters = List.of(counter(1, RANGER, "늑대인간", 0.2, 11));
        assertThat(ScrimSuggester.suggest(counters, pool(3), nothingSeen(), 4)).isEmpty();
    }
}
