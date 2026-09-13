package dev.repair.api.catalogue;

import dev.repair.api.common.PageResult;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogueRepository {

    private final JdbcClient jdbcClient;

    public CatalogueRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResult<ManufacturerDto> searchManufacturers(String search, int page, int size) {
        String like = "%" + (search == null ? "" : search.trim()) + "%";
        long total = jdbcClient.sql(
                        "SELECT count(*) FROM manufacturers WHERE canonical_name ILIKE :like")
                .param("like", like)
                .query(Long.class)
                .single();

        List<ManufacturerDto> items = jdbcClient.sql(
                        """
                        SELECT id, canonical_name, slug FROM manufacturers
                        WHERE canonical_name ILIKE :like
                        ORDER BY LOWER(canonical_name)
                        LIMIT :limit OFFSET :offset
                        """)
                .param("like", like)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, rowNum) -> new ManufacturerDto(
                        rs.getLong("id"), rs.getString("canonical_name"), rs.getString("slug")))
                .list();

        return new PageResult<>(items, page, size, total);
    }

    public PageResult<ModelDto> searchModels(long manufacturerId, String search, int page, int size) {
        String like = "%" + (search == null ? "" : search.trim()) + "%";
        long total = jdbcClient.sql(
                        "SELECT count(*) FROM vehicle_models WHERE manufacturer_id = :mfg AND model_name ILIKE :like")
                .param("mfg", manufacturerId)
                .param("like", like)
                .query(Long.class)
                .single();

        List<ModelDto> items = jdbcClient.sql(
                        """
                        SELECT id, manufacturer_id, model_name, slug FROM vehicle_models
                        WHERE manufacturer_id = :mfg AND model_name ILIKE :like
                        ORDER BY LOWER(model_name)
                        LIMIT :limit OFFSET :offset
                        """)
                .param("mfg", manufacturerId)
                .param("like", like)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, rowNum) -> new ModelDto(
                        rs.getLong("id"), rs.getLong("manufacturer_id"), rs.getString("model_name"), rs.getString("slug")))
                .list();

        return new PageResult<>(items, page, size, total);
    }

    public List<VariantDto> listVariantsForModel(long modelId) {
        return jdbcClient.sql(
                        """
                        SELECT id, model_id, variant_name, slug, year_start, year_end,
                               fuel_type, drive_type, gearbox, quality_status
                        FROM vehicle_variants
                        WHERE model_id = :modelId
                        ORDER BY year_start NULLS LAST, variant_name
                        """)
                .param("modelId", modelId)
                .query(CatalogueRepository::mapVariant)
                .list();
    }

    public Optional<VariantDetailDto> findVariantDetail(long variantId) {
        return jdbcClient.sql(
                        """
                        SELECT v.id, v.model_id, v.variant_name, v.slug, v.year_start, v.year_end,
                               v.fuel_type, v.drive_type, v.gearbox, v.quality_status,
                               m.model_name, mf.canonical_name AS manufacturer_name,
                               s.power_hp, s.power_kw, s.power_bhp, s.torque_nm, s.torque_lbft,
                               s.top_speed_kmh, s.acceleration_0_100_kmh_s, s.displacement_cm3,
                               s.weight_kg, s.cylinder_layout, s.cylinder_count,
                               s.co2_emissions_g_km, s.fuel_consumption_combined_l_100km,
                               EXISTS (
                                   SELECT 1 FROM document_vehicle_links dvl
                                   WHERE dvl.variant_id = v.id OR dvl.model_id = v.model_id
                               ) AS has_knowledge_coverage
                        FROM vehicle_variants v
                        JOIN vehicle_models m ON m.id = v.model_id
                        JOIN manufacturers mf ON mf.id = m.manufacturer_id
                        LEFT JOIN vehicle_specs s ON s.variant_id = v.id
                        WHERE v.id = :id
                        """)
                .param("id", variantId)
                .query((rs, rowNum) -> new VariantDetailDto(
                        mapVariant(rs, rowNum),
                        rs.getString("manufacturer_name"),
                        rs.getString("model_name"),
                        new SpecsDto(
                                nullableDouble(rs, "power_hp"),
                                nullableDouble(rs, "power_kw"),
                                nullableDouble(rs, "power_bhp"),
                                nullableDouble(rs, "torque_nm"),
                                nullableDouble(rs, "torque_lbft"),
                                nullableDouble(rs, "top_speed_kmh"),
                                nullableDouble(rs, "acceleration_0_100_kmh_s"),
                                nullableDouble(rs, "displacement_cm3"),
                                nullableDouble(rs, "weight_kg"),
                                rs.getString("cylinder_layout"),
                                (Integer) rs.getObject("cylinder_count"),
                                nullableDouble(rs, "co2_emissions_g_km"),
                                nullableDouble(rs, "fuel_consumption_combined_l_100km")
                        ),
                        rs.getBoolean("has_knowledge_coverage")
                ))
                .optional();
    }

    /**
     * Resolves the configured demo vehicle by real name/year criteria
     * (see DemoVehicleProperties) — never a hardcoded id. Only returns a
     * variant that actually has knowledge-corpus coverage; a name/year
     * match with zero coverage is treated the same as no match, since a
     * "demo vehicle" with no evidence behind it isn't a valid demo.
     */
    public Optional<VariantDetailDto> findDemoVariant(
            String manufacturerName, String modelName, String variantNameContains, Integer yearStart
    ) {
        return jdbcClient.sql(
                        """
                        SELECT v.id, v.model_id, v.variant_name, v.slug, v.year_start, v.year_end,
                               v.fuel_type, v.drive_type, v.gearbox, v.quality_status,
                               m.model_name, mf.canonical_name AS manufacturer_name,
                               s.power_hp, s.power_kw, s.power_bhp, s.torque_nm, s.torque_lbft,
                               s.top_speed_kmh, s.acceleration_0_100_kmh_s, s.displacement_cm3,
                               s.weight_kg, s.cylinder_layout, s.cylinder_count,
                               s.co2_emissions_g_km, s.fuel_consumption_combined_l_100km,
                               EXISTS (
                                   SELECT 1 FROM document_vehicle_links dvl
                                   WHERE dvl.variant_id = v.id OR dvl.model_id = v.model_id
                               ) AS has_knowledge_coverage
                        FROM vehicle_variants v
                        JOIN vehicle_models m ON m.id = v.model_id
                        JOIN manufacturers mf ON mf.id = m.manufacturer_id
                        LEFT JOIN vehicle_specs s ON s.variant_id = v.id
                        WHERE mf.canonical_name = :manufacturerName
                          AND m.model_name = :modelName
                          AND v.variant_name ILIKE :variantPattern
                          AND (:yearStart::int IS NULL OR v.year_start = :yearStart)
                          AND EXISTS (
                              SELECT 1 FROM document_vehicle_links dvl
                              WHERE dvl.variant_id = v.id OR dvl.model_id = v.model_id
                          )
                        ORDER BY v.year_start NULLS LAST, v.id
                        LIMIT 1
                        """)
                .param("manufacturerName", manufacturerName)
                .param("modelName", modelName)
                .param("variantPattern", "%" + variantNameContains + "%")
                .param("yearStart", yearStart)
                .query((rs, rowNum) -> new VariantDetailDto(
                        mapVariant(rs, rowNum),
                        rs.getString("manufacturer_name"),
                        rs.getString("model_name"),
                        new SpecsDto(
                                nullableDouble(rs, "power_hp"),
                                nullableDouble(rs, "power_kw"),
                                nullableDouble(rs, "power_bhp"),
                                nullableDouble(rs, "torque_nm"),
                                nullableDouble(rs, "torque_lbft"),
                                nullableDouble(rs, "top_speed_kmh"),
                                nullableDouble(rs, "acceleration_0_100_kmh_s"),
                                nullableDouble(rs, "displacement_cm3"),
                                nullableDouble(rs, "weight_kg"),
                                rs.getString("cylinder_layout"),
                                (Integer) rs.getObject("cylinder_count"),
                                nullableDouble(rs, "co2_emissions_g_km"),
                                nullableDouble(rs, "fuel_consumption_combined_l_100km")
                        ),
                        rs.getBoolean("has_knowledge_coverage")
                ))
                .optional();
    }

    public List<CoveredVehicleDto> listCoveredVehicles() {
        return jdbcClient.sql(
                        """
                        SELECT DISTINCT v.id AS variant_id, mf.canonical_name AS manufacturer_name,
                               m.model_name, v.variant_name, v.year_start, v.year_end
                        FROM document_vehicle_links dvl
                        JOIN vehicle_variants v ON v.id = dvl.variant_id
                            OR (dvl.model_id IS NOT NULL AND dvl.model_id = v.model_id)
                        JOIN vehicle_models m ON m.id = v.model_id
                        JOIN manufacturers mf ON mf.id = m.manufacturer_id
                        ORDER BY mf.canonical_name, m.model_name, v.year_start NULLS LAST
                        """)
                .query((rs, rowNum) -> new CoveredVehicleDto(
                        rs.getLong("variant_id"),
                        rs.getString("manufacturer_name"),
                        rs.getString("model_name"),
                        rs.getString("variant_name"),
                        (Integer) rs.getObject("year_start"),
                        (Integer) rs.getObject("year_end")
                ))
                .list();
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static VariantDto mapVariant(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new VariantDto(
                rs.getLong("id"),
                rs.getLong("model_id"),
                rs.getString("variant_name"),
                rs.getString("slug"),
                (Integer) rs.getObject("year_start"),
                (Integer) rs.getObject("year_end"),
                rs.getString("fuel_type"),
                rs.getString("drive_type"),
                rs.getString("gearbox"),
                rs.getString("quality_status")
        );
    }
}
