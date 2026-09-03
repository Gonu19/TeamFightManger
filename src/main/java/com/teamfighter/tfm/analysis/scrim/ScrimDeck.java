package com.teamfighter.tfm.analysis.scrim;

import java.util.List;

/**
 * 스크림에서 해 볼 덱 하나. 4픽이다 (D35).
 *
 * <p>자리마다 <b>왜 이 픽인지</b>가 붙는다. 근거는 둘 중 하나다:
 *
 * <ul>
 *   <li><b>카운터</b> — 메타 상위 아무개를 σ 만큼 더 죽인다. 측정된 주장이다</li>
 *   <li><b>발굴</b> — 아직 아무 데서도 안 나온 조합을 만든다. 근거가 아니라
 *       <b>근거가 없다는 사실</b>이 이유다 (D62)</li>
 * </ul>
 *
 * <p><b>세 쌍 수의 합은 항상 {@link ScrimSuggester#PAIRS_PER_DECK}(6)이다.</b>
 * 4인을 뽑으면 같은 편 듀오가 여섯 쌍 생기고, 그 여섯은 셋 중 하나에 반드시 속한다.
 *
 * @param unexploredPairs 아무 데서도 같이 안 나온 쌍
 * @param scrimTriedPairs 스크림에서만 해 본 쌍. 공식전 표본은 아직 없다
 * @param knownPairs      공식전으로 이미 아는 쌍
 */
public record ScrimDeck(
        List<DeckSlot> slots,
        int unexploredPairs,
        int scrimTriedPairs,
        int knownPairs) {

    public ScrimDeck {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    /**
     * 덱의 한 자리.
     *
     * @param targetNameKo 이 픽이 잡는 메타 상위. 발굴 자리면 {@code null}
     * @param effect       그 대상의 죽음이 얼마나 늘어나나 (σ). 발굴 자리면 {@code null}
     * @param observations 그 쌍을 본 횟수. 발굴 자리면 {@code null}
     */
    public record DeckSlot(
            int championId,
            String code,
            String nameKo,
            String category,
            int games,
            String targetNameKo,
            Double effect,
            Integer observations) {

        /** 발굴 자리는 잡는 대상이 없다. 화면이 두 줄을 다르게 그린다. */
        public boolean isCounter() {
            return targetNameKo != null;
        }

        /**
         * 스크림에서 확인할 값어치가 있나 — <b>효과는 있는데 표본이 얇다.</b>
         *
         * <p>40번 본 카운터는 이미 알고 있으니 그냥 쓰면 된다. 11번 본 것은 릿지에
         * 세게 눌린 값이라 아직 흔들린다. 그 차이가 곧 스크림에서 할 일이다.
         */
        public boolean worthTesting() {
            return observations != null && observations < CounterPick.THIN_OBSERVATIONS;
        }

        /** 사람이 읽는 효과 크기. {@code +0.16σ} 꼴. */
        public String effectText() {
            return effect == null ? "" : String.format("+%.2fσ", effect);
        }

        static DeckSlot of(CounterPick p) {
            return new DeckSlot(p.championId(), p.code(), p.nameKo(), p.category(),
                    p.games(), p.targetNameKo(), p.effect(), p.observations());
        }

        static DeckSlot of(ScrimCandidate c) {
            return new DeckSlot(c.championId(), c.code(), c.nameKo(), c.category(),
                    c.games(), null, null, null);
        }
    }

    /** 이 덱이 잡는 메타 상위들. 겹치면 한 번만 센다. */
    public List<String> targets() {
        return slots.stream()
                .map(DeckSlot::targetNameKo)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /** 스크림에서 확인할 값어치가 있는 자리 수. 이 덱을 추천하는 이유의 크기다. */
    public long toTest() {
        return slots.stream().filter(DeckSlot::worthTesting).count();
    }
}
