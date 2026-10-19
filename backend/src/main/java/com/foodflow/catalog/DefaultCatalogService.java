package com.foodflow.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodflow.exception.DomainException;

import java.util.List;
import java.util.Map;

/**
 * JDBC-backed implementation of {@link CatalogService}.
 */
@Service
@Transactional(readOnly = true)
public class DefaultCatalogService implements CatalogService {

    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public DefaultCatalogService(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public List<Map<String, Object>> restaurants() {
        return db.query(
            "SELECT content FROM restaurants WHERE open_for_orders=TRUE ORDER BY id",
            (rs, n) -> {
                try {
                    return json.readValue(rs.getString(1), new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        );
    }

    @Override
    public Map<String, Object> restaurant(String id) {
        return restaurants().stream()
            .filter(r -> id.equals(r.get("id")))
            .findFirst()
            .orElseThrow(() -> new DomainException(422, "Restaurant unavailable"));
    }
}
