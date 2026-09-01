package com.teamfighter.tfm.web.view;

/**
 * 커리어 고르개의 한 줄.
 *
 * <h2>번호만으로는 못 고른다</h2>
 *
 * "슬롯 1 · 슬롯 2 · 슬롯 3" 만 보여주면 어느 것이 어느 커리어인지 알 수 없다.
 * 세이브 파일명은 {@code slot_638683925954242004.tfm} 같은 tick 값이라 더 나쁘다.
 * 그래서 <b>플레이어 팀 이름</b>을 붙인다 — 사람이 커리어를 기억하는 이름이 그것이다.
 *
 * <p>{@code save_slot.label}·{@code team_name} 은 스키마에 자리만 있고 아무도 안 채운다.
 * 채우는 경로가 생기면 그쪽이 우선이 되겠지만, 지금은 {@code team.is_player} 가
 * 유일하게 실재하는 이름이다.
 *
 * <p>이름이 유일하지 않을 수 있다 — 실측에서 슬롯 2와 3이 둘 다 "Ketos" 다.
 * 그래서 번호를 <b>지우지 않고</b> 앞에 남긴다.
 *
 * @param teamName 플레이어 팀 이름. 아직 공식전이 없으면 {@code null} —
 *                 팀은 공식전에서만 식별된다 (D54)
 * @param filled   이 화면이 보여줄 내용이 있는가. 뜻은 화면마다 다르다
 *                 (티어=집계됨 · 연대기=기사 있음 · 갤러리=갤 있음)
 */
public record SlotOption(int slotId, String teamName, boolean filled) {

    /**
     * 고르개에 찍힐 한 줄.
     *
     * <p>비어 있다는 사실을 <b>글자로</b> 말한다. 칩이었을 때는 흐린 색으로 말했는데,
     * 드롭다운은 닫혀 있을 때 색이 안 보인다 — 고른 뒤에야 "왜 빈 화면이지" 가 된다.
     */
    public String label() {
        StringBuilder out = new StringBuilder("슬롯 ").append(slotId);
        if (teamName != null && !teamName.isBlank()) {
            out.append(" — ").append(teamName);
        }
        if (!filled) {
            out.append(" (비어 있음)");
        }
        return out.toString();
    }
}
