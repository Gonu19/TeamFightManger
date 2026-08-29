package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.Athlete;
import com.teamfighter.tfm.ingest.entity.AthleteId;
import com.teamfighter.tfm.ingest.entity.SaveSlot;
import com.teamfighter.tfm.ingest.repository.AthleteNameSeedRepository;
import com.teamfighter.tfm.ingest.repository.AthleteRepository;
import com.teamfighter.tfm.parser.common.AthleteParser;
import com.teamfighter.tfm.parser.common.ParsedAthlete;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 선수를 적재한다 (D58).
 *
 * <p><b>{@code @Transactional} 이 없다.</b> {@link SaveLoader} 의 트랜잭션 안에서 불린다 —
 * 선수는 그 파일 적재의 일부라 같이 들어가거나 같이 사라져야 한다.
 *
 * <p><b>덮어쓴다.</b> 팀 이름(D55)은 그 커리어의 기록이라 안 덮지만, 선수의 나이·연봉·팬 수는
 * 시간에 따라 실제로 변하는 값이고 세이브에는 <b>지금</b> 값만 있다. 적재할 때마다 최신으로
 * 갱신하는 것이 맞다. 잃는 것도 없다 — 경기별 소속은 경기 자체가 알려준다.
 *
 * <p><b>이름을 못 풀어도 선수는 적재한다.</b> 인덱스가 시드에 없으면 이름만 비우고 인덱스는
 * 남긴다. 나중에 시드를 채우면 그때 이름이 붙는다 — 팀 쪽의 {@code name_key} 와 같은 방식이다.
 */
@Component
public class AthleteLoader {

    private static final Logger log = LoggerFactory.getLogger(AthleteLoader.class);

    private final AthleteRepository athletes;
    private final AthleteNameSeedRepository nameSeeds;

    public AthleteLoader(AthleteRepository athletes, AthleteNameSeedRepository nameSeeds) {
        this.athletes = athletes;
        this.nameSeeds = nameSeeds;
    }

    /**
     * 세이브의 선수를 전부 적재하거나 갱신한다.
     *
     * <p>세이브를 읽지 못해도 <b>던지지 않는다</b>. 선수는 기사·화면용이고 경기가 본체라,
     * 이것 때문에 공식 경기 수백 건이 롤백되는 쪽이 훨씬 나쁘다. 대신 조용히 넘어가지 않고
     * WARN 을 남긴다 — 결과는 화면에 선수 없는 경기로 그대로 드러난다.
     *
     * @param teamIdOf 게임 팀 번호를 우리 {@code team.team_id} 로 바꾸는 함수.
     *                 선수 소속 팀 중에는 경기에 한 번도 안 나온 팀이 있어 {@code null} 이 나올 수 있다
     * @return 적재하거나 갱신한 선수 수
     */
    public int load(SaveSlot slot, Path saveFile, java.util.function.Function<Integer, Integer> teamIdOf) {
        List<ParsedAthlete> parsed;
        try {
            parsed = AthleteParser.read(saveFile);
        } catch (IOException | RuntimeException e) {
            log.warn("세이브에서 선수를 읽지 못했다: {} — {}", saveFile, e.toString());
            return 0;
        }
        if (parsed.isEmpty()) {
            return 0;
        }

        Map<Integer, String> names = new HashMap<>();
        nameSeeds.findAll().forEach(s -> names.put(s.getIdx(), s.getName()));

        Map<Integer, Athlete> existing = new HashMap<>();
        athletes.findByIdSlotId(slot.getSlotId())
                .forEach(a -> existing.put(a.getId().getGameAthleteId(), a));

        int unresolved = 0;
        for (ParsedAthlete p : parsed) {
            Athlete athlete = existing.computeIfAbsent(p.id(),
                    id -> athletes.save(new Athlete(new AthleteId(slot.getSlotId(), id))));
            String name = p.nameIndex() == null ? null : names.get(p.nameIndex());
            if (name == null) {
                unresolved++;
            }
            athlete.snapshot(p, teamIdOf.apply(p.gameTeamId()), name);
        }

        if (unresolved > 0) {
            // 조용히 넘기지 않는다. 시드가 낡았다는 뜻이고, 화면에는 이름 없는 선수로 나온다.
            log.warn("선수 {}명의 이름을 풀지 못했다 — athlete_name_seed 가 낡았을 수 있다", unresolved);
        }
        return parsed.size();
    }
}
