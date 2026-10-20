package com.foodflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

record MenuUpdate(@NotBlank String name, @NotNull @Positive BigDecimal price, boolean available) {}
record RestaurantSettings(@NotNull Boolean openForOrders, @NotNull Map<String,String> openingHours,
        @NotNull @Min(1) @Max(180) Integer preparationMinutes,
        @NotNull @Min(15) @Max(3600) Integer acceptanceTimeoutSeconds) {}

@Service
class RestaurantOperations {
    private final JdbcTemplate db;
    RestaurantOperations(JdbcTemplate db) { this.db = db; }

    private void require(boolean condition, int status, String message) { if (!condition) throw new Failure(status, message); }
    private boolean role(Authentication auth, String name) { return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + name)); }

    private void authorize(Authentication auth, String restaurantId) {
        require(role(auth,"ADMIN") || role(auth,"RESTAURANT"), 403, "Only restaurant operations can edit a menu");
        if (role(auth,"RESTAURANT")) {
            String own = db.queryForObject("SELECT restaurant_id FROM app_users WHERE username=?", String.class, auth.getName());
            require(restaurantId.equals(own), 404, "Restaurant not found");
        }
        require(db.queryForObject("SELECT COUNT(*) FROM restaurants WHERE id=?", Integer.class, restaurantId) == 1, 404, "Restaurant not found");
    }

    @Transactional(readOnly=true)
    Map<String,Object> get(Authentication auth, String restaurantId) {
        authorize(auth,restaurantId);
        var row=db.queryForList("SELECT open_for_orders,opening_hours,preparation_minutes,acceptance_timeout_seconds FROM restaurants WHERE id=?",restaurantId).getFirst();
        return Map.of("restaurantId",restaurantId,"openForOrders",row.get("open_for_orders"),"openingHours",row.get("opening_hours"),
                "preparationMinutes",row.get("preparation_minutes"),"acceptanceTimeoutSeconds",row.get("acceptance_timeout_seconds"),
                "menu",db.query("SELECT id,name,price,available FROM dishes WHERE restaurant_id=? ORDER BY id",
                        (rs,n)->Map.of("id",rs.getString("id"),"name",rs.getString("name"),"price",rs.getBigDecimal("price"),"available",rs.getBoolean("available")),restaurantId));
    }

    @Transactional
    Map<String,Object> updateSettings(Authentication auth, String restaurantId, RestaurantSettings input) {
        authorize(auth,restaurantId);
        var validDays=List.of("monday","tuesday","wednesday","thursday","friday","saturday","sunday");
        for (var entry:input.openingHours().entrySet()) {
            require(validDays.contains(entry.getKey()),422,"Unknown opening-hours day: "+entry.getKey());
            try { String[] range=entry.getValue().split("-",-1);require(range.length==2,422,"Hours must use HH:mm-HH:mm");LocalTime.parse(range[0]);LocalTime.parse(range[1]); }
            catch (Failure failure) { throw failure; }
            catch (Exception e) { throw new Failure(422,"Hours must use HH:mm-HH:mm"); }
        }
        String encoded;
        try { encoded=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input.openingHours()); }
        catch (Exception e) { throw new IllegalStateException(e); }
        db.update("UPDATE restaurants SET open_for_orders=?,opening_hours=?,preparation_minutes=?,acceptance_timeout_seconds=? WHERE id=?",
                input.openForOrders(),encoded,input.preparationMinutes(),input.acceptanceTimeoutSeconds(),restaurantId);
        return get(auth,restaurantId);
    }

    @Transactional
    Map<String,Object> updateMenu(Authentication auth, String restaurantId, String dishId, MenuUpdate input) {
        authorize(auth,restaurantId);
        int changed=db.update("UPDATE dishes SET name=?,price=?,available=? WHERE id=? AND restaurant_id=?",
                input.name().trim(),input.price(),input.available(),dishId,restaurantId);
        require(changed==1,404,"Menu item not found");
        return get(auth,restaurantId);
    }
}

@RestController
@RequestMapping("/api/v1/restaurants/{restaurantId}/operations")
class RestaurantOperationsController {
    private final RestaurantOperations operations;
    RestaurantOperationsController(RestaurantOperations operations) { this.operations=operations; }
    @GetMapping Map<String,Object> get(Authentication auth,@PathVariable String restaurantId) { return operations.get(auth,restaurantId); }
    @PutMapping @ResponseBody Map<String,Object> update(Authentication auth,@PathVariable String restaurantId,@Valid @RequestBody RestaurantSettings input) { return operations.updateSettings(auth,restaurantId,input); }
    @PutMapping("/menu/{dishId}") Map<String,Object> menu(Authentication auth,@PathVariable String restaurantId,@PathVariable String dishId,@Valid @RequestBody MenuUpdate input) { return operations.updateMenu(auth,restaurantId,dishId,input); }
}
