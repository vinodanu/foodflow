package com.foodflow;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:delivery_ops;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.password=","foodflow.demo-operations=true","foodflow.restaurant.timeout-scan-ms=3600000","foodflow.delivery.offer-scan-ms=3600000"})
class DeliveryOperationsTest {
    @Autowired Orders orders;
    @Autowired Payments payments;
    @Autowired DeliveryOperations delivery;
    @Autowired JdbcTemplate db;

    Authentication user(String name,String role){return new UsernamePasswordAuthenticationToken(name,"",List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    @BeforeEach void makePartnersAvailable(){db.update("UPDATE partner_availability SET available=TRUE WHERE username IN ('partner','partner2')");}

    OrderView readyForPickup(){
        var customer=user("customer","CUSTOMER");
        var order=orders.place(customer,UUID.randomUUID().toString(),new Checkout("green","Flat 201, Road 12, Jubilee Hills 500033","Jubilee Hills, Hyderabad",List.of(new Line("bowl",1))));
        var payment=payments.create(customer,order.id(),UUID.randomUUID().toString());payments.simulate(customer,payment.id(),"SUCCEEDED");
        var current=orders.get(customer,order.id());
        current=orders.transition(user("restaurant","RESTAURANT"),order.id(),new Action("ACCEPTED",current.version(),"Accepted"));
        current=orders.transition(user("restaurant","RESTAURANT"),order.id(),new Action("PREPARING",current.version(),"Cooking"));
        return orders.transition(user("restaurant","RESTAURANT"),order.id(),new Action("READY_FOR_PICKUP",current.version(),"Ready"));
    }

    @Test void offerExpiresAndUnavailablePartnersAreNotOfferedOrders(){
        var order=readyForPickup();var admin=user("admin","ADMIN");
        delivery.availability(user("partner","PARTNER"),false);
        assertEquals(409,assertThrows(Failure.class,()->delivery.offer(admin,order.id(),new OfferInput("partner",order.version(),30),false)).status);
        delivery.availability(user("partner","PARTNER"),true);
        var offer=delivery.offer(admin,order.id(),new OfferInput("partner",order.version(),30),false);
        db.update("UPDATE delivery_offers SET expires_at=? WHERE id=?",java.time.Instant.now().minusSeconds(1).toString(),offer.id());
        assertEquals(1,delivery.expireOffers());
        assertEquals("EXPIRED",db.queryForObject("SELECT status FROM delivery_offers WHERE id=?",String.class,offer.id()));
        assertEquals(409,assertThrows(Failure.class,()->delivery.accept(user("partner","PARTNER"),order.id(),offer.id())).status);
    }

    @Test void acceptedOfferReassignsAndDeliveryCompletionRestoresAvailability(){
        var ready=readyForPickup();var admin=user("admin","ADMIN");var partner=user("partner","PARTNER");
        var first=delivery.offer(admin,ready.id(),new OfferInput("partner",ready.version(),60),false);
        assertEquals("ACCEPTED",delivery.accept(partner,ready.id(),first.id()).status());
        assertFalse(db.queryForObject("SELECT available FROM partner_availability WHERE username='partner'",Boolean.class));
        var assigned=orders.get(user("customer","CUSTOMER"),ready.id());
        var replacement=delivery.offer(admin,ready.id(),new OfferInput("partner2",assigned.version(),60),true);
        assertTrue(db.queryForObject("SELECT available FROM partner_availability WHERE username='partner'",Boolean.class));
        assertEquals("partner2",delivery.accept(user("partner2","PARTNER"),ready.id(),replacement.id()).partner());
        var current=orders.get(user("customer","CUSTOMER"),ready.id());
        current=orders.transition(user("partner2","PARTNER"),ready.id(),new Action("PICKED_UP",current.version(),"Picked up"));
        current=orders.transition(user("partner2","PARTNER"),ready.id(),new Action("DELIVERED",current.version(),"Delivered"));
        assertEquals("DELIVERED",current.status());
        assertTrue(db.queryForObject("SELECT available FROM partner_availability WHERE username='partner2'",Boolean.class));
    }
}
