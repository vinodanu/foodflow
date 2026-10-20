package com.foodflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

record Line(@NotBlank String dishId,@Min(1) @Max(20) int quantity){}
record Checkout(@NotBlank String restaurantId,@NotBlank @Size(min=10,max=500) String address,@NotBlank String area,@NotEmpty @Size(max=30) List<@Valid Line> items){}
record Action(@NotBlank String status,@Min(0) long expectedVersion,@NotBlank @Size(max=500) String note){}
record Assign(@NotBlank String partner,@Min(0) long expectedVersion){}
record OrderLine(String dishId,String name,String unitPrice,int quantity){}
record OrderView(String id,String restaurant,String restaurantId,String customer,String partner,String status,String paymentStatus,String refundStatus,String subtotal,String deliveryFee,String total,String address,String area,String createdAt,long version,List<OrderLine> items){}

@Service
class Orders {
 private final JdbcTemplate db; private final Catalog catalog; private final Payments payments;private final boolean demo;
 private static final Set<String> AREAS=Set.of("Jubilee Hills, Hyderabad","Banjara Hills, Hyderabad","Gachibowli, Hyderabad");
 Orders(JdbcTemplate db,Catalog catalog,Payments payments,@Value("${foodflow.demo-operations}")boolean demo){this.db=db;this.catalog=catalog;this.payments=payments;this.demo=demo;}
 boolean role(Authentication a,String r){return a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_"+r));}
 void require(boolean condition,int status,String message){if(!condition)throw new Failure(status,message);}
 String text(BigDecimal amount){return amount.setScale(2).toPlainString();}
 @Transactional public OrderView place(Authentication auth,String key,Checkout request){
  require(role(auth,"CUSTOMER"),403,"Only customers can order");require(key!=null&&key.matches("[A-Za-z0-9_-]{8,100}"),400,"Supply an Idempotency-Key of 8–100 letters, digits, underscores or hyphens");
  require(AREAS.contains(request.area()),422,"This area is outside the pilot service zone");
  // A database row lock serialises this customer's retries across application instances.
  db.queryForObject("SELECT username FROM app_users WHERE username=? FOR UPDATE",String.class,auth.getName());
  var sorted=request.items().stream().sorted(Comparator.comparing(Line::dishId)).toList();
  require(sorted.stream().map(Line::dishId).distinct().count()==sorted.size(),422,"Combine duplicate dishes into one line");
  String fingerprint;
  try{fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(catalog.json.writeValueAsBytes(new Checkout(request.restaurantId(),request.address().trim(),request.area(),sorted))));}catch(Exception e){throw new IllegalStateException(e);}
  var existing=db.queryForList("SELECT id,request_hash FROM orders WHERE customer=? AND idempotency_key=?",auth.getName(),key);
  if(!existing.isEmpty()){require(fingerprint.equals(existing.getFirst().get("request_hash")),409,"Idempotency key was used for different input");return view(existing.getFirst().get("id").toString());}
  catalog.restaurant(request.restaurantId());
  List<OrderLine> lines=new ArrayList<>();BigDecimal subtotal=BigDecimal.ZERO;
  for(var line:sorted){var dish=db.queryForList("SELECT * FROM dishes WHERE id=? AND restaurant_id=? AND available=TRUE",line.dishId(),request.restaurantId());require(!dish.isEmpty(),422,"Dish unavailable or belongs to another restaurant: "+line.dishId());var d=dish.getFirst();var price=(BigDecimal)d.get("price");subtotal=subtotal.add(price.multiply(BigDecimal.valueOf(line.quantity())));lines.add(new OrderLine(line.dishId(),d.get("name").toString(),text(price),line.quantity()));}
  String id=UUID.randomUUID().toString();var fee=new BigDecimal("35.00");Instant created=Instant.now();String now=created.toString();
  db.update("INSERT INTO orders(id,customer,restaurant_id,status,payment_status,refund_status,subtotal,delivery_fee,total,address,area,created_at,idempotency_key,request_hash,accept_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,auth.getName(),request.restaurantId(),"PAYMENT_PENDING","PENDING","NONE",subtotal,fee,subtotal.add(fee),request.address().trim(),request.area(),now,key,fingerprint,null);
  for(var line:lines)db.update("INSERT INTO order_items VALUES(?,?,?,?,?)",id,line.dishId(),line.name(),new BigDecimal(line.unitPrice()),line.quantity());
  audit(id,auth.getName(),"OrderPlaced","Order created and awaiting sandbox payment");return view(id);
 }
 OrderView view(String id){return db.query("SELECT o.*,r.content FROM orders o JOIN restaurants r ON o.restaurant_id=r.id WHERE o.id=?",(rs,n)->{String name;try{name=catalog.json.readTree(rs.getString("content")).get("name").asText();}catch(Exception e){throw new IllegalStateException(e);}return new OrderView(rs.getString("id"),name,rs.getString("restaurant_id"),rs.getString("customer"),rs.getString("partner"),rs.getString("status"),rs.getString("payment_status"),rs.getString("refund_status"),text(rs.getBigDecimal("subtotal")),text(rs.getBigDecimal("delivery_fee")),text(rs.getBigDecimal("total")),rs.getString("address"),rs.getString("area"),rs.getString("created_at"),rs.getLong("version"),db.query("SELECT * FROM order_items WHERE order_id=? ORDER BY dish_id",(r,i)->new OrderLine(r.getString("dish_id"),r.getString("name"),text(r.getBigDecimal("unit_price")),r.getInt("quantity")),id));},id).stream().findFirst().orElseThrow(()->new Failure(404,"Order not found"));}
 boolean access(Authentication auth,OrderView o){return role(auth,"ADMIN")||o.customer().equals(auth.getName())||(role(auth,"RESTAURANT")&&o.restaurantId().equals(restaurantId(auth)))||(role(auth,"PARTNER")&&auth.getName().equals(o.partner()));}
 String restaurantId(Authentication auth){return db.queryForObject("SELECT restaurant_id FROM app_users WHERE username=?",String.class,auth.getName());}
 OrderView get(Authentication auth,String id){var order=view(id);require(access(auth,order),404,"Order not found");return order;}
 List<OrderView> list(Authentication auth){String where;Object[] values;if(role(auth,"ADMIN")){where="";values=new Object[0];}else if(role(auth,"RESTAURANT")){where=" WHERE restaurant_id=?";values=new Object[]{restaurantId(auth)};}else if(role(auth,"PARTNER")){where=" WHERE partner=?";values=new Object[]{auth.getName()};}else{where=" WHERE customer=?";values=new Object[]{auth.getName()};}return db.queryForList("SELECT id FROM orders"+where+" ORDER BY created_at DESC LIMIT 100",String.class,values).stream().map(this::view).toList();}
 @Transactional public OrderView assign(Authentication auth,String id,Assign request){require(role(auth,"ADMIN"),403,"Only operations can assign partners");lock(id);var o=view(id);require(o.version()==request.expectedVersion(),409,"Order changed; reload before assigning");require(Set.of("ACCEPTED","PREPARING","READY_FOR_PICKUP").contains(o.status()),409,"Order is not ready for assignment");require(o.partner()==null,409,"Already assigned");require(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=? AND role='PARTNER'",Integer.class,request.partner())==1,422,"Unknown delivery partner");db.queryForObject("SELECT username FROM app_users WHERE username=? FOR UPDATE",String.class,request.partner());require(db.queryForObject("SELECT COUNT(*) FROM partner_availability WHERE username=? AND available=TRUE",Integer.class,request.partner())==1,409,"Delivery partner is unavailable");require(db.queryForObject("SELECT COUNT(*) FROM orders WHERE partner=? AND status NOT IN ('DELIVERED','CANCELLED','REJECTED')",Integer.class,request.partner())==0,409,"Partner has an active delivery");db.update("UPDATE partner_availability SET available=FALSE,updated_at=? WHERE username=?",Instant.now().toString(),request.partner());db.update("UPDATE orders SET partner=?,version=version+1 WHERE id=?",request.partner(),id);audit(id,auth.getName(),"PartnerAssigned",request.partner());return view(id);}
 @Transactional public OrderView transition(Authentication auth,String id,Action request){lock(id);var o=get(auth,id);require(o.version()==request.expectedVersion(),409,"Order changed; reload before acting");String next=request.status();boolean allowed=false;
  if(role(auth,"CUSTOMER"))allowed=next.equals("CANCELLED")&&Set.of("PLACED","PAYMENT_PENDING").contains(o.status());
  if(role(auth,"ADMIN")||role(auth,"RESTAURANT")){allowed=switch(o.status()){case "PLACED"->Set.of("ACCEPTED","REJECTED").contains(next);case "ACCEPTED"->next.equals("PREPARING");case "PREPARING"->next.equals("READY_FOR_PICKUP");default->false;};}
  if(role(auth,"PARTNER")&&auth.getName().equals(o.partner()))allowed=(o.status().equals("READY_FOR_PICKUP")&&next.equals("PICKED_UP"))||(o.status().equals("PICKED_UP")&&next.equals("DELIVERED"));
  require(allowed,409,"Action is not permitted for this role and order state");db.update("UPDATE orders SET status=?,version=version+1 WHERE id=?",next,id);if(Set.of("CANCELLED","REJECTED").contains(next)){String refund=payments.refundIfPaid(id);db.update("UPDATE orders SET refund_status=? WHERE id=?",refund,id);}if((next.equals("DELIVERED")||Set.of("CANCELLED","REJECTED").contains(next))&&o.partner()!=null)db.update("UPDATE partner_availability SET available=TRUE,updated_at=? WHERE username=?",Instant.now().toString(),o.partner());audit(id,auth.getName(),"Order"+next,request.note());return view(id);
 }
 @Transactional public OrderView advanceDemo(Authentication auth,String id,Action request){require(demo&&role(auth,"ADMIN"),403,"Demo advancement is restricted to demo administrators");lock(id);var o=view(id);require(o.version()==request.expectedVersion(),409,"Order changed; reload");String next=switch(o.status()){case "PLACED"->"ACCEPTED";case "ACCEPTED"->"PREPARING";case "PREPARING"->"READY_FOR_PICKUP";case "READY_FOR_PICKUP"->"PICKED_UP";case "PICKED_UP"->"DELIVERED";default->throw new Failure(409,"Order is terminal");};db.update("UPDATE orders SET status=?,version=version+1 WHERE id=?",next,id);audit(id,auth.getName(),"DemoAdvanced",request.note());return view(id);}
 void lock(String id){require(!db.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE",String.class,id).isEmpty(),404,"Order not found");}
 void audit(String id,String actor,String action,String note){String now=Instant.now().toString();String actionId=UUID.randomUUID().toString();db.update("INSERT INTO order_actions VALUES(?,?,?,?,?,?)",actionId,id,actor,action,now,note);try{db.update("INSERT INTO outbox(event_id,aggregate_id,event_type,payload,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),id,action,catalog.json.writeValueAsString(Map.of("orderId",id,"actor",actor,"action",action,"occurredAt",now)),now);var recipient=db.queryForObject("SELECT customer FROM orders WHERE id=?",String.class,id);String title=action.equals("OrderPlaced")?"Order received":"Order update: "+action.replace("Order","").replace("PartnerAssigned","partner assigned");String message=note==null||note.isBlank()?"Your order status is now "+action.replace("Order","").toLowerCase(Locale.ROOT)+".":note;db.update("INSERT INTO customer_notifications(id,action_id,customer,order_id,title,message,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),actionId,recipient,id,title,message,now);}catch(Exception e){throw new IllegalStateException(e);}}
 List<Map<String,Object>> history(Authentication auth,String id){get(auth,id);return db.queryForList("SELECT actor,action,occurred_at,note FROM order_actions WHERE order_id=? ORDER BY occurred_at",id);}
 @Scheduled(fixedDelayString="${foodflow.restaurant.timeout-scan-ms:15000}")
 @Transactional public int rejectExpired(){String now=Instant.now().toString();var ids=db.queryForList("SELECT id FROM orders WHERE status='PLACED' AND accept_by IS NOT NULL AND accept_by<=? ORDER BY accept_by",String.class,now);int rejected=0;for(String id:ids){lock(id);var o=view(id);if(!o.status().equals("PLACED"))continue;db.update("UPDATE orders SET status='REJECTED',version=version+1 WHERE id=? AND status='PLACED'",id);payments.refundIfPaid(id);audit(id,"system","OrderRejected","Restaurant acceptance timed out");rejected++;}return rejected;}
}

@RestController
@RequestMapping("/api/v1/orders")
class OrdersController {
 final Orders orders;OrdersController(Orders orders){this.orders=orders;}
 @PostMapping OrderView place(Authentication auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody Checkout input){return orders.place(auth,key,input);}
 @GetMapping List<OrderView> list(Authentication auth){return orders.list(auth);}
 @GetMapping("/{id}") OrderView get(Authentication auth,@PathVariable String id){return orders.get(auth,id);}
 @GetMapping("/{id}/history") List<Map<String,Object>> history(Authentication auth,@PathVariable String id){return orders.history(auth,id);}
 @PostMapping("/{id}/status") OrderView status(Authentication auth,@PathVariable String id,@Valid @RequestBody Action request){return orders.transition(auth,id,request);}
 @PostMapping("/{id}/assign") OrderView assign(Authentication auth,@PathVariable String id,@Valid @RequestBody Assign request){return orders.assign(auth,id,request);}
 @PostMapping("/{id}/demo-advance") OrderView advance(Authentication auth,@PathVariable String id,@Valid @RequestBody Action request){return orders.advanceDemo(auth,id,request);}
}
