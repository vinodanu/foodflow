package com.foodflow.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foodflow.config.AppProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Seeds demo users and catalogue data on startup.
 * SRP: seeding is separate from catalogue query logic.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final CatalogService catalog;
    private final PasswordEncoder passwords;
    private final AppProperties appProperties;
    private final ObjectMapper json = new ObjectMapper();
    private final JdbcTemplate db;

    public DataSeeder(CatalogService catalog,
                      PasswordEncoder passwords,
                      AppProperties appProperties,
                      JdbcTemplate db) {
        this.catalog = catalog;
        this.passwords = passwords;
        this.appProperties = appProperties;
        this.db = db;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        seedUsers();
        seedCatalogue();
    }

    private void seedUsers() {
        for (var entry : Map.of(
            "customer", "CUSTOMER",
            "customer2", "CUSTOMER",
            "restaurant", "RESTAURANT",
            "partner", "PARTNER",
            "admin", "ADMIN"
        ).entrySet()) {
            String username = entry.getKey();
            String role = entry.getValue();
            if (db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?",
                    Integer.class, username) == 0) {
                db.update(
                    "INSERT INTO app_users VALUES(?,?,?,?)",
                    username,
                    passwords.encode(appProperties.getDemoPassword()),
                    role,
                    role.equals("RESTAURANT") ? "green" : null
                );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void seedCatalogue() throws Exception {
        List<Map<String, Object>> restaurants = json.readValue(
            new ClassPathResource("catalog.json").getInputStream(),
            new TypeReference<>() {}
        );
        for (var r : restaurants) {
            if (db.queryForObject("SELECT COUNT(*) FROM restaurants WHERE id=?",
                    Integer.class, r.get("id")) > 0) {
                continue;
            }
            db.update(
                "INSERT INTO restaurants(id,content) VALUES(?,?)",
                r.get("id"),
                json.writeValueAsString(r)
            );
            for (var item : (List<?>) r.get("menu")) {
                var dish = (Map<?, ?>) item;
                db.update(
                    "INSERT INTO dishes(id,restaurant_id,name,price) VALUES(?,?,?,?)",
                    dish.get("id"),
                    r.get("id"),
                    dish.get("name"),
                    new BigDecimal(dish.get("price").toString())
                );
            }
        }
    }
}
