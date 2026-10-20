package com.foodflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

record OfferInput(@NotBlank String partner,@Min(0) long expectedVersion,@Min(15) @Max(600) int expiresInSeconds) {}
record AvailabilityInput(@NotNull Boolean available) {}
record DeliveryOfferView(String id,String orderId,String partner,String status,String expiresAt) {}

@Service
class DeliveryOperations {
    private final JdbcTemplate db;
    DeliveryOperations(JdbcTemplate db) { this.db=db; }
    private void require(boolean condition,int status,String message) { if(!condition)throw new Failure(status,message); }
    private boolean role(Authentication auth,String name) { return auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_"+name)); }
    private DeliveryOfferView view(String id) {
        return db.query("SELECT * FROM delivery_offers WHERE id=?",(rs,n)->new DeliveryOfferView(rs.getString("id"),rs.getString("order_id"),rs.getString("partner"),rs.getString("status"),rs.getString("expires_at")),id)
                .stream().findFirst().orElseThrow(()->new Failure(404,"Delivery offer not found"));
    }

    @Transactional
    DeliveryOfferView offer(Authentication auth,String orderId,OfferInput input,boolean reassign) {
        require(role(auth,"ADMIN"),403,"Only operations can create delivery offers");
        var rows=db.queryForList("SELECT status,partner,version FROM orders WHERE id=? FOR UPDATE",orderId);
        require(!rows.isEmpty(),404,"Order not found");var order=rows.getFirst();
        require("READY_FOR_PICKUP".equals(order.get("status")),409,"Order is not ready for delivery");
        require(((Number)order.get("version")).longValue()==input.expectedVersion(),409,"Order changed; reload before offering");
        if(reassign){
            require(order.get("partner")!=null,409,"Order has no assigned partner to replace");
            require(!input.partner().equals(order.get("partner")),422,"Choose a different delivery partner");
            db.update("UPDATE partner_availability SET available=TRUE,updated_at=? WHERE username=?",Instant.now().toString(),order.get("partner"));
            db.update("UPDATE orders SET partner=NULL,version=version+1 WHERE id=?",orderId);
        } else require(order.get("partner")==null,409,"Order already has an assigned partner");

        require(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=? AND role='PARTNER'",Integer.class,input.partner())==1,422,"Unknown delivery partner");
        require(db.queryForObject("SELECT COUNT(*) FROM partner_availability WHERE username=? AND available=TRUE",Integer.class,input.partner())==1,409,"Delivery partner is unavailable");
        require(db.queryForObject("SELECT COUNT(*) FROM orders WHERE partner=? AND id<>? AND status NOT IN ('DELIVERED','CANCELLED','REJECTED')",Integer.class,input.partner(),orderId)==0,409,"Partner has another active delivery");
        require(db.queryForObject("SELECT COUNT(*) FROM delivery_offers WHERE partner=? AND status='OFFERED' AND expires_at>?",Integer.class,input.partner(),Instant.now().toString())==0,409,"Partner already has a pending offer");
        String id=UUID.randomUUID().toString(),now=Instant.now().toString(),expiry=Instant.now().plusSeconds(input.expiresInSeconds()).toString();
        db.update("INSERT INTO delivery_offers(id,order_id,partner,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",id,orderId,input.partner(),"OFFERED",expiry,now,now);
        return view(id);
    }

    @Transactional
    DeliveryOfferView accept(Authentication auth,String orderId,String offerId) {
        require(role(auth,"PARTNER"),403,"Only delivery partners can accept offers");
        var rows=db.queryForList("SELECT * FROM delivery_offers WHERE id=? AND order_id=? FOR UPDATE",offerId,orderId);
        require(!rows.isEmpty(),404,"Delivery offer not found");var offer=rows.getFirst();
        require(auth.getName().equals(offer.get("partner")),404,"Delivery offer not found");
        require("OFFERED".equals(offer.get("status")),409,"Delivery offer is no longer available");
        require(Instant.parse(offer.get("expires_at").toString()).isAfter(Instant.now()),409,"Delivery offer expired");
        var order=db.queryForList("SELECT status,partner FROM orders WHERE id=? FOR UPDATE",orderId).getFirst();
        require("READY_FOR_PICKUP".equals(order.get("status"))&&order.get("partner")==null,409,"Order is no longer available for assignment");
        require(db.queryForObject("SELECT COUNT(*) FROM partner_availability WHERE username=? AND available=TRUE",Integer.class,auth.getName())==1,409,"Delivery partner is unavailable");
        String now=Instant.now().toString();
        db.update("UPDATE delivery_offers SET status='ACCEPTED',updated_at=? WHERE id=?",now,offerId);
        db.update("UPDATE partner_availability SET available=FALSE,updated_at=? WHERE username=?",now,auth.getName());
        db.update("UPDATE orders SET partner=?,version=version+1 WHERE id=?",auth.getName(),orderId);
        return view(offerId);
    }

    @Transactional
    DeliveryOfferView decline(Authentication auth,String orderId,String offerId) {
        require(role(auth,"PARTNER"),403,"Only delivery partners can decline offers");
        int changed=db.update("UPDATE delivery_offers SET status='DECLINED',updated_at=? WHERE id=? AND order_id=? AND partner=? AND status='OFFERED'",
                Instant.now().toString(),offerId,orderId,auth.getName());
        require(changed==1,404,"Delivery offer not found");return view(offerId);
    }

    @Transactional
    boolean availability(Authentication auth,boolean available) {
        require(role(auth,"PARTNER"),403,"Only delivery partners can change availability");
        if(available) require(db.queryForObject("SELECT COUNT(*) FROM orders WHERE partner=? AND status NOT IN ('DELIVERED','CANCELLED','REJECTED')",Integer.class,auth.getName())==0,409,"Complete the active delivery before going available");
        String now=Instant.now().toString();
        require(db.update("UPDATE partner_availability SET available=?,updated_at=? WHERE username=?",available,now,auth.getName())==1,404,"Delivery partner not found");
        if(!available)db.update("UPDATE delivery_offers SET status='DECLINED',updated_at=? WHERE partner=? AND status='OFFERED'",now,auth.getName());
        return available;
    }

    List<Map<String,Object>> availability(Authentication auth) {
        require(role(auth,"ADMIN"),403,"Only operations can list delivery partners");
        return db.queryForList("SELECT username,available,updated_at FROM partner_availability ORDER BY username");
    }

    @Scheduled(fixedDelayString="${foodflow.delivery.offer-scan-ms:5000}")
    @Transactional public int expireOffers() {
        return db.update("UPDATE delivery_offers SET status='EXPIRED',updated_at=? WHERE status='OFFERED' AND expires_at<=?",Instant.now().toString(),Instant.now().toString());
    }
}

@RestController
@RequestMapping("/api/v1")
class DeliveryOperationsController {
    private final DeliveryOperations delivery;
    DeliveryOperationsController(DeliveryOperations delivery){this.delivery=delivery;}
    @PutMapping("/partners/availability") Map<String,Boolean> availability(Authentication auth,@Valid @RequestBody AvailabilityInput input){return Map.of("available",delivery.availability(auth,input.available()));}
    @GetMapping("/partners/availability") List<Map<String,Object>> partners(Authentication auth){return delivery.availability(auth);}
    @PostMapping("/orders/{orderId}/offers") DeliveryOfferView offer(Authentication auth,@PathVariable String orderId,@Valid @RequestBody OfferInput input){return delivery.offer(auth,orderId,input,false);}
    @PostMapping("/orders/{orderId}/reassign") DeliveryOfferView reassign(Authentication auth,@PathVariable String orderId,@Valid @RequestBody OfferInput input){return delivery.offer(auth,orderId,input,true);}
    @PostMapping("/orders/{orderId}/offers/{offerId}/accept") DeliveryOfferView accept(Authentication auth,@PathVariable String orderId,@PathVariable String offerId){return delivery.accept(auth,orderId,offerId);}
    @PostMapping("/orders/{orderId}/offers/{offerId}/decline") DeliveryOfferView decline(Authentication auth,@PathVariable String orderId,@PathVariable String offerId){return delivery.decline(auth,orderId,offerId);}
}
