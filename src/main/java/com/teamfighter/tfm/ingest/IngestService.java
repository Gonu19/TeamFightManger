package com.teamfighter.tfm.ingest;

import java.nio.file.Path;

/**
 * 세이브 파일을 DB 로 옮긴다.
 *
 * <p>이 프로젝트에서 결정이 가장 많이 모이는 지점이다. 파서 출력을 그대로 넣는 일이 아니라,
 * 다음을 여기서 지켜야 한다:
 *
 * <ul>
 *   <li><b>D20</b> — 참가자 진영을 챔피언 <b>이름</b>으로 매칭한다.
 *       {@code ChampStat} 인덱스 순서는 경기의 20.5% 에서 어긋난다</li>
 *   <li><b>D8</b> — 스크림에는 시점이 없다. {@code TodayData} 의 게임 내 날짜를 붙인다</li>
 *   <li><b>D15</b> — 경기마다 {@code change_count}(그 시점까지 그 챔피언이 패치로 바뀐 횟수)를 계산한다</li>
 *   <li><b>D28</b> — {@code slot_*.tfm} 만 슬롯으로 잡는다. {@code *.tfm_backup} 을 잡으면
 *       같은 커리어가 두 벌 적재되고, 해시 중복 검사는 슬롯 안에서만 돌아 이를 막지 못한다</li>
 *   <li><b>D35</b> — 형식이 하나뿐이다. 공식전은 <b>픽 4+4 · 밴 3+3</b>,
 *       스크림은 <b>인원 4+4 · 밴 없음</b>. 그 외는 전부 버린다.
 *       DB 는 {@code team_size = 4} 만 강제할 수 있고 "밴이 정확히 3개" 는 막지 못한다 —
 *       행 개수 제약이라 CHECK 로 표현되지 않는다. 그래서 여기서 막는다</li>
 * </ul>
 *
 * <p>파서는 관대하고 적재는 엄격하다. 파서가 {@code null} 을 그대로 통과시키는 값이라도,
 * 승패나 챔피언 이름처럼 <b>없으면 그 경기가 무의미해지는 값</b>은 여기서 막는다.
 */
public interface IngestService {

    /**
     * 세이브 파일 하나를 적재한다.
     *
     * <p><b>같은 파일을 다시 적재해도 경기가 늘지 않는다.</b> 내용이 같으면
     * {@code ingest_run} 의 해시 중복으로 걸러지고, 내용이 늘었으면 새 경기만 들어간다.
     *
     * @param saveFile {@code slot_*.tfm} 경로. 세이브 파일은 읽기만 한다
     * @return 이번에 실제로 늘어난 건수
     * @throws IllegalArgumentException 파일명이 {@code slot_*.tfm} 형식이 아닐 때 (D28)
     * @throws IllegalStateException    경기에 승패나 챔피언 이름이 없을 때
     */
    IngestResult ingest(Path saveFile);

    /**
     * 적재 결과.
     *
     * @param slotId       적재된 슬롯
     * @param newMatches   새로 들어간 공식 경기 수
     * @param newScrims    새로 들어간 스크림 수
     * @param skippedGames  형식이 맞지 않아 건너뛴 공식 경기 수 (밴이 3+3 이 아님, D35)
     * @param skippedScrims 형식이 맞지 않아 건너뛴 스크림 수 (인원이 4+4 가 아님, D35)
     * @param newPatches   새로 들어간 패치 수
     * @param alreadyIngested 같은 내용이 이미 적재돼 있어 아무것도 하지 않았는지
     */
    record IngestResult(
            Integer slotId,
            int newMatches,
            int newScrims,
            int skippedGames,
            int skippedScrims,
            int newPatches,
            boolean alreadyIngested) {

        public static IngestResult duplicate(Integer slotId) {
            return new IngestResult(slotId, 0, 0, 0, 0, 0, true);
        }
    }
}
