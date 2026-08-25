package com.teamfighter.tfm.ingest;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적재 한 번에 실제로 몇 개의 SQL 이 나가는지 <b>센다.</b>
 *
 * <p>리뷰에서 "행마다 SELECT+INSERT 가 나간다", "배치가 안 걸린다" 는 지적을 받았다.
 * 그럴듯하지만 <b>추측이다.</b> 이 프로젝트는 데이터로 확인할 수 있는 것을 추측하지 않는다.
 *
 * <p>이 테스트는 임계값을 걸어 회귀를 막지 않는다 — 그러려면 먼저 무엇이 정상인지
 * 알아야 하는데 아직 모른다. <b>측정값을 출력하는 것이 목적이다.</b>
 * 숫자를 보고 나서 고칠지 말지 정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngestCostTest {

    private static final Path FIXTURES = Path.of("fixtures");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    static boolean fixturesExist() {
        return !fixtures().isEmpty();
    }

    private static List<Path> fixtures() {
        if (!Files.isDirectory(FIXTURES)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(FIXTURES)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".tfm")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("적재 한 번의 SQL 발생 수를 측정한다 (임계값 없음 — 숫자를 본다)")
    void ingest_sqlCost_isMeasured() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        Path fixture = fixtures().get(fixtures().size() - 1);   // 가장 큰 슬롯
        IngestService.IngestResult result = ingestService.ingest(fixture);

        // 엔티티 수와 statement 수는 단위가 다르다. 배치가 걸리면 여러 엔티티가
        // statement 하나로 묶이므로, 둘을 빼서 "SELECT 수" 를 구할 수 없다.
        // 실제로 그렇게 계산했다가 음수가 나왔다 — 뺄셈 자체가 틀린 것이었다.
        long entityInserts = stats.getEntityInsertCount();
        long entityLoads = stats.getEntityLoadCount();
        long statements = stats.getPrepareStatementCount();
        int rows = result.newMatches() + result.newScrims();

        System.out.printf("""

                === 적재 비용 측정 : %s ===
                  적재 경기        %d건 (공식 %d · 스크림 %d)
                  엔티티 INSERT    %d행
                  엔티티 LOAD      %d행   (있을 리 없는 행을 확인하면 여기가 커진다)
                  JDBC statement   %d     (배치가 걸리면 행 수보다 훨씬 작다)
                  경기당 statement %.1f
                %n""",
                fixture.getFileName(), rows, result.newMatches(), result.newScrims(),
                entityInserts, entityLoads, statements,
                rows == 0 ? 0.0 : (double) statements / rows);

        // 적재가 실제로 일어났는지만 확인한다. 비용은 판단 재료이지 합격 기준이 아니다.
        assertThat(rows).isPositive();
        assertThat(entityInserts).isPositive();
    }
}
