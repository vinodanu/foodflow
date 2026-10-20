package com.foodflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

record SupportRequestInput(String orderId, @NotBlank @Size(max=120) String subject, @NotBlank @Size(min=10,max=2000) String message) {}

@Service
class CustomerExperience {
 private final JdbcTemplate db;
 private final Orders orders;
 CustomerExperience(JdbcTemplate db,Orders orders){this.db=db;this.orders=orders;}
 private void customer(Authentication auth){if(!orders.role(auth,"CUSTOMER"))throw new Failure(403,"Only customers can use this service");}
 List<Map<String,Object>> notifications(Authentication auth){customer(auth);return db.queryForList("SELECT id,order_id,title,message,created_at,read_at FROM customer_notifications WHERE customer=? ORDER BY created_at DESC LIMIT 100",auth.getName());}
 @Transactional public void read(Authentication auth,String id){customer(auth);int changed=db.update("UPDATE customer_notifications SET read_at=? WHERE id=? AND customer=? AND read_at IS NULL",Instant.now().toString(),id,auth.getName());if(changed==0&&db.queryForObject("SELECT COUNT(*) FROM customer_notifications WHERE id=? AND customer=?",Integer.class,id)==0)throw new Failure(404,"Notification not found");}
 @Transactional public Map<String,Object> createSupport(Authentication auth,SupportRequestInput input){customer(auth);if(input.orderId()!=null&&!input.orderId().isBlank())orders.get(auth,input.orderId());String id=UUID.randomUUID().toString();String now=Instant.now().toString();db.update("INSERT INTO support_requests(id,customer,order_id,subject,message,status,created_at) VALUES(?,?,?,?,?,'OPEN',?)",id,auth.getName(),input.orderId()==null||input.orderId().isBlank()?null:input.orderId(),input.subject().trim(),input.message().trim(),now);return support(id,auth);}
 List<Map<String,Object>> supportList(Authentication auth){if(!orders.role(auth,"CUSTOMER")&&!orders.role(auth,"ADMIN"))throw new Failure(403,"Only customers and operations can view support requests");return orders.role(auth,"ADMIN")?db.queryForList("SELECT id,order_id,subject,message,status,created_at FROM support_requests ORDER BY created_at DESC LIMIT 100"):db.queryForList("SELECT id,order_id,subject,message,status,created_at FROM support_requests WHERE customer=? ORDER BY created_at DESC LIMIT 100",auth.getName());}
 private Map<String,Object> support(String id,Authentication auth){var rows=db.queryForList("SELECT id,order_id,subject,message,status,created_at FROM support_requests WHERE id=? AND customer=?",id,auth.getName());if(rows.isEmpty())throw new Failure(404,"Support request not found");return rows.getFirst();}
}

@RestController
@RequestMapping("/api/v1")
class CustomerExperienceController {
 private final CustomerExperience experience;
 CustomerExperienceController(CustomerExperience experience){this.experience=experience;}
 @GetMapping("/notifications") List<Map<String,Object>> notifications(Authentication auth){return experience.notifications(auth);}
 @PostMapping("/notifications/{id}/read") ResponseEntity<Void> read(Authentication auth,@PathVariable String id){experience.read(auth,id);return ResponseEntity.noContent().build();}
 @GetMapping("/support") List<Map<String,Object>> support(Authentication auth){return experience.supportList(auth);}
 @PostMapping("/support") Map<String,Object> createSupport(Authentication auth,@Valid @RequestBody SupportRequestInput input){return experience.createSupport(auth,input);}
}
