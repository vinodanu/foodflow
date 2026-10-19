package com.foodflow.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP layer for catalogue queries. Delegates to {@link CatalogService}; contains no business logic.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalog;
    private final JdbcTemplate db;

    public CatalogController(CatalogService catalog, JdbcTemplate db) {
        this.catalog = catalog;
        this.db = db;
    }

    @GetMapping("/restaurants")
    public List<Map<String, Object>> restaurants() {
        return catalog.restaurants();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        db.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP", "paymentMode", "DEMO");
    }
}
