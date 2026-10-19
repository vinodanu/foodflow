package com.foodflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes order audit trails and publishes events to the outbox table.
 * SRP: order service delegates auditing here instead of embedding SQL.
 */
@Service
public class AuditService {

    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public AuditService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void record(String orderId, String actor, String action, String note) {
        String now = Instant.now().toString();
        db.update(
            "INSERT INTO order_actions VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString(), orderId, actor, action, now, note
        );
        try {
            db.update(
                "INSERT INTO outbox(event_id,aggregate_id,event_type,payload,created_at) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(), orderId, action,
                json.writeValueAsString(Map.of(
                    "orderId", orderId,
                    "actor", actor,
                    "action", action,
                    "occurredAt", now
                )),
                now
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
