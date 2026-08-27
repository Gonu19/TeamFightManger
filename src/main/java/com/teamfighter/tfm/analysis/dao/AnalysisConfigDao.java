package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code analysis_config} 를 읽는다.
 *
 * <p>여섯 키가 전부 있어야 한다. 없으면 {@link AnalysisConfig} 가 던진다 (D44) —
 * 여기서 기본값을 채워 넣지 않는다.
 */
@Repository
public class AnalysisConfigDao {

    private final JdbcTemplate jdbc;

    public AnalysisConfigDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AnalysisConfig load() {
        Map<String, BigDecimal> values = new HashMap<>();
        jdbc.query("SELECT key, value FROM analysis_config",
                rs -> {
                    values.put(rs.getString("key"), rs.getBigDecimal("value"));
                });
        return AnalysisConfig.from(values);
    }
}
