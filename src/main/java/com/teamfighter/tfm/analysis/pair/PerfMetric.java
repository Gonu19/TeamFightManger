package com.teamfighter.tfm.analysis.pair;

import com.teamfighter.tfm.analysis.dao.PairObservationDao.Participant;

import java.util.function.Function;

/**
 * 어느 출력을 볼 것인가. Postgres 의 {@code perf_metric} ENUM 과 이름이 같아야 한다.
 *
 * <h2>지표를 고르지 않고 전부 잰다</h2>
 *
 * D64 는 "카운터는 {@code DEATH}, 시너지는 {@code DEATH}+{@code DEALING}" 으로 갈랐다가
 * D65 에서 그것으로 부족하다는 것을 알았다. <b>같은 죽음 증가라도 함께 오는 값이 다르면
 * 다른 현상이다.</b>
 *
 * <pre>
 *   딜↓ · 죽음↑ (양쪽 다)      역시너지 — 서로 방해한다        Pyromancer × Fighter
 *   죽음↑ · 탱↑ · 딜↓ (한쪽만)  간접 카운터 — 버프를 못 끊어 녹는다  Knight ← Bard
 *   죽음↓ · 탱↓                어그로 분산 — 동료가 대신 맞는다   Monk ← Werewolf
 *   죽음↓ · 탱 변화 없음        힐 보호                        Werewolf ← Monk
 * </pre>
 *
 * 그래서 여섯을 다 저장하고 화면이 <b>지표 묶음</b>을 보여준다(D65 결정 1).
 * 위 서명 표는 세 쌍에서 읽어낸 <b>초안</b>이라, 화면은 이름을 단정해 찍지 않고
 * 벡터를 그대로 놓는다.
 *
 * <h2>부호의 뜻이 지표마다 다르다</h2>
 *
 * {@code DEALING} 의 상대 효과가 양수인 것은 <b>"그 상대가 내 딜을 흡수해 준다"</b> 는
 * 뜻이지 "내가 저 챔피언에게 강하다" 가 아니다(D64 결정 3). 그대로 카운터 화면에 쓰면
 * 정확히 거꾸로 읽힌다. 그 해석은 코드가 아니라 화면 문구가 진다.
 */
public enum PerfMetric {

    DEALING("가한피해", Participant::dealing),
    TANKING("받은피해", Participant::tanking),
    HEALING("힐량", Participant::healing),
    KILL("킬", Participant::kills),
    DEATH("데스", Participant::deaths),
    ASSIST("어시스트", Participant::assists);

    private final String label;
    private final Function<Participant, Integer> reader;

    PerfMetric(String label, Function<Participant, Integer> reader) {
        this.label = label;
        this.reader = reader;
    }

    /** 화면에 쓰는 한글 이름. 기사·갤러리 프롬프트가 쓰는 말과 같아야 한다 (D66). */
    public String label() {
        return label;
    }

    /** 그 참가자의 이 지표 값. 안 채워졌으면 {@code null}. */
    public Integer of(Participant participant) {
        return reader.apply(participant);
    }
}
