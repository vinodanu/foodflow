package com.foodflow;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:restaurant_ops;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.password=","foodflow.demo-operations=true","foodflow.restaurant.timeout-scan-ms=3600000"})
class RestaurantOperationsTest {
    @Autowired Orders orders;
    @Autowired Payments payments;
    @Autowired RestaurantOperations operations;
    @Autowired Catalog catalog;
    @Autowired JdbcTemplate db;

    Authentication user(String name,String role) { return new UsernamePasswordAuthenticationToken(name,"",List.of(new SimpleGrantedAuthority("ROLE_"+role))); }

    @BeforeEach void resetRestaurant() {
        db.update("UPDATE restaurants SET open_for_orders=TRUE,opening_hours='{}',preparation_minutes=25,acceptance_timeout_seconds=300 WHERE id='green'");
        db.update("UPDATE dishes SET name='Harvest grain bowl',price=249,available=TRUE WHERE id='bowl' AND restaurant_id='green'");
    }

    @Test void restaurantCanEditItemsAvailabilityAndPreparationEstimate() {
        var restaurant=user("restaurant","RESTAURANT");
        var menu=operations.updateMenu(restaurant,"green","bowl",new MenuUpdate("Harvest bowl",new BigDecimal("275.00"),false));
        var item=((List<Map<String,Object>>)menu.get("menu")).stream().filter(x->x.get("id").equals("bowl")).findFirst().orElseThrow();
        assertEquals("Harvest bowl",item.get("name"));assertEquals(new BigDecimal("275.00"),item.get("price"));assertEquals(false,item.get("available"));
        assertEquals(422,assertThrows(Failure.class,()->orders.place(user("customer","CUSTOMER"),UUID.randomUUID().toString(),checkout())).status);
        operations.updateSettings(restaurant,"green",new RestaurantSettings(true,Map.of(),38,300));
        assertEquals(38,catalog.restaurants().stream().filter(r->r.get("id").equals("green")).findFirst().orElseThrow().get("preparationMinutes"));
        assertEquals(404,assertThrows(Failure.class,()->operations.updateMenu(restaurant,"pizza","margherita",new MenuUpdate("Unauthorized",new BigDecimal("1.00"),true))).status);
    }

    @Test void closedRestaurantAndInvalidHoursCannotAcceptOrders() {
        var restaurant=user("restaurant","RESTAURANT");
        assertEquals(422,assertThrows(Failure.class,()->operations.updateSettings(restaurant,"green",new RestaurantSettings(true,Map.of("monday","not-hours"),25,300))).status);
        operations.updateSettings(restaurant,"green",new RestaurantSettings(false,Map.of("monday","09:00-22:00"),25,300));
        assertFalse(catalog.restaurants().stream().anyMatch(r->r.get("id").equals("green")));
        assertEquals(422,assertThrows(Failure.class,()->orders.place(user("customer","CUSTOMER"),UUID.randomUUID().toString(),checkout())).status);
    }

    @Test void expiredKitchenAcceptanceRejectsOrderAndRefundsSandboxPayment() {
        var customer=user("customer","CUSTOMER");
        var order=orders.place(customer,UUID.randomUUID().toString(),checkout());
        var payment=payments.create(customer,order.id(),UUID.randomUUID().toString());
        payments.simulate(customer,payment.id(),"SUCCEEDED");
        db.update("UPDATE orders SET accept_by=? WHERE id=?",Instant.now().minusSeconds(1).toString(),order.id());
        assertEquals(1,orders.rejectExpired());
        var rejected=orders.get(customer,order.id());
        assertEquals("REJECTED",rejected.status());assertEquals("REFUNDED",rejected.refundStatus());
    }

    Checkout checkout() { return new Checkout("green","Flat 201, Road 12, Jubilee Hills 500033","Jubilee Hills, Hyderabad",List.of(new Line("bowl",1))); }
}
