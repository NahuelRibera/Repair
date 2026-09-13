package dev.repair.api.dataquality;

import dev.repair.api.common.PageResult;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DataQualityRepository {

    private static final int OBSERVED_VALUE_PREVIEW_LENGTH = 200;

    private final JdbcClient jdbcClient;

    public DataQualityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DataQualitySummaryDto summary() {
        long manufacturers = count("SELECT count(*) FROM manufacturers");
        long models = count("SELECT count(*) FROM vehicle_models");
        long variants = count("SELECT count(*) FROM vehicle_variants");
        long rawRecords = count("SELECT count(*) FROM raw_vehicle_records");
        long totalIssues = count("SELECT count(*) FROM data_quality_issues");

        List<DataQualitySummaryDto.RuleCountDto> byRule = jdbcClient.sql(
                        "SELECT rule AS k, count(*) AS c FROM data_quality_issues GROUP BY rule ORDER BY c DESC")
                .query((rs, rowNum) -> new DataQualitySummaryDto.RuleCountDto(rs.getString("k"), rs.getLong("c")))
                .list();

        List<DataQualitySummaryDto.RuleCountDto> bySeverity = jdbcClient.sql(
                        "SELECT severity AS k, count(*) AS c FROM data_quality_issues GROUP BY severity ORDER BY c DESC")
                .query((rs, rowNum) -> new DataQualitySummaryDto.RuleCountDto(rs.getString("k"), rs.getLong("c")))
                .list();

        return new DataQualitySummaryDto(manufacturers, models, variants, rawRecords, totalIssues, byRule, bySeverity);
    }

    public PageResult<DataQualityIssueDto> searchIssues(String rule, String severity, int page, int size) {
        long total = jdbcClient.sql(
                        """
                        SELECT count(*) FROM data_quality_issues
                        WHERE (:rule = '' OR rule = :rule) AND (:severity = '' OR severity = :severity)
                        """)
                .param("rule", rule == null ? "" : rule)
                .param("severity", severity == null ? "" : severity)
                .query(Long.class)
                .single();

        List<DataQualityIssueDto> items = jdbcClient.sql(
                        """
                        SELECT q.id, q.rule, q.field, q.severity, q.explanation,
                               LEFT(q.observed_value, :previewLen) AS observed_value_preview,
                               mf.canonical_name AS manufacturer_name, m.model_name, v.variant_name
                        FROM data_quality_issues q
                        LEFT JOIN vehicle_variants v ON v.id = q.variant_id
                        LEFT JOIN vehicle_models m ON m.id = v.model_id
                        LEFT JOIN manufacturers mf ON mf.id = m.manufacturer_id
                        WHERE (:rule = '' OR q.rule = :rule) AND (:severity = '' OR q.severity = :severity)
                        ORDER BY q.id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("rule", rule == null ? "" : rule)
                .param("severity", severity == null ? "" : severity)
                .param("previewLen", OBSERVED_VALUE_PREVIEW_LENGTH)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, rowNum) -> new DataQualityIssueDto(
                        rs.getLong("id"),
                        rs.getString("rule"),
                        rs.getString("field"),
                        rs.getString("observed_value_preview"),
                        rs.getString("severity"),
                        rs.getString("explanation"),
                        rs.getString("manufacturer_name"),
                        rs.getString("model_name"),
                        rs.getString("variant_name")
                ))
                .list();

        return new PageResult<>(items, page, size, total);
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}
