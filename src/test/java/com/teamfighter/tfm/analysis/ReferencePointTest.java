package com.teamfighter.tfm.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 추정 시점 {@link ReferencePoint} 를 못 박는다 (D24).
 *
 * <p>DB 는 필요 없다 — 뺄셈이다. 그런데 이 뺄셈의 부호가 D42 가 경고한 그 자리다.
 *
 * <p>화면의 패치 선택은 집계 대상을 자르는 필터가 아니라 <b>추정 시점의 선택</b>이다.
 * 따라서 선택한 패치보다 뒤에 치러진 경기도 집계에 들어온다 — 그 경기들의 "경과" 는 음수가
 * 아니라 거리다. 감쇠가 재는 것은 방향이 아니라 <b>얼마나 떨어져 있는가</b> 이기 때문이다.
 */
class ReferencePointTest {

    @Test
    @DisplayName("기준 시점 이전 경기의 경과 패치는 순번 차이다")
    void elapsedPatches_pastMatchIsDistance() {
        ReferencePoint ref = new ReferencePoint(20, Map.of());

        assertThat(ref.elapsedPatchesFrom(8)).isEqualTo(12);
    }

    @Test
    @DisplayName("기준 시점보다 뒤에 치러진 경기도 거리로 잰다 — 음수가 되지 않는다")
    void elapsedPatches_futureMatchIsAlsoDistance() {
        ReferencePoint ref = new ReferencePoint(8, Map.of());

        // 변조: 절댓값을 빼고 refSeq - matchSeq 로 두면 -12 가 나오고,
        //       DecayWeight 가 던져서 과거 패치를 선택하는 순간 집계가 통째로 죽는다.
        assertThat(ref.elapsedPatchesFrom(20)).isEqualTo(12);
    }

    @Test
    @DisplayName("패치가 배정되지 않은 경기는 순번 0 으로 쳐서 가장 멀다 — 커리어 시작 직후다")
    void elapsedPatches_nullPatchIsOldest() {
        ReferencePoint ref = new ReferencePoint(20, Map.of());

        assertThat(ref.elapsedPatchesFrom(null)).isEqualTo(20);
    }

    @Test
    @DisplayName("자기 변경 횟수도 거리다 — 기준 시점 누적에서 경기 시점 누적을 뺀 절댓값")
    void selfChanges_isDistanceFromReference() {
        ReferencePoint ref = new ReferencePoint(20, Map.of(7, 5));

        assertThat(ref.selfChangesFrom(7, 2)).isEqualTo(3);
        assertThat(ref.selfChangesFrom(7, 5)).isEqualTo(0);
        assertThat(ref.selfChangesFrom(7, 8)).isEqualTo(3);
    }

    @Test
    @DisplayName("기준 시점에 한 번도 바뀐 적 없는 챔피언은 누적 0 이다")
    void selfChanges_championNeverPatchedIsZero() {
        ReferencePoint ref = new ReferencePoint(20, Map.of());

        assertThat(ref.selfChangesFrom(7, 0)).isEqualTo(0);
    }

    @Test
    @DisplayName("기준 순번이 음수면 던진다")
    void constructor_negativeSeqThrows() {
        assertThatThrownBy(() -> new ReferencePoint(-1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("경기 순번이 음수면 던진다 — 순번은 1부터다")
    void elapsedPatches_negativeMatchSeqThrows() {
        ReferencePoint ref = new ReferencePoint(20, Map.of());

        assertThatThrownBy(() -> ref.elapsedPatchesFrom(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("경기 시점 누적이 음수면 던진다")
    void selfChanges_negativeMatchCountThrows() {
        ReferencePoint ref = new ReferencePoint(20, Map.of(7, 5));

        assertThatThrownBy(() -> ref.selfChangesFrom(7, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
