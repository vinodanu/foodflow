package com.foodflow.catalog;

import java.util.List;
import java.util.Map;

/**
 * Read-only catalogue contract. Implementations may back this with a DB, cache, or remote service
 * without changing callers (OCP).
 */
public interface CatalogService {

    List<Map<String, Object>> restaurants();

    Map<String, Object> restaurant(String id);
}
