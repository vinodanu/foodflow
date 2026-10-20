package com.foodflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class Catalog {
 final JdbcTemplate db; final ObjectMapper json=new ObjectMapper();
 private final ZoneId zone;
 Catalog(JdbcTemplate db,@Value("${foodflow.service-zone:Asia/Kolkata}")String zone){this.db=db;this.zone=ZoneId.of(zone);}
 List<Map<String,Object>> restaurants(){return db.query("SELECT id,content,opening_hours,preparation_minutes FROM restaurants WHERE open_for_orders=TRUE ORDER BY id",(rs,n)->{try{String id=rs.getString("id");if(!withinHours(rs.getString("opening_hours")))return null;Map<String,Object> restaurant=new java.util.LinkedHashMap<>(json.readValue(rs.getString("content"),new TypeReference<Map<String,Object>>(){}));var original=(List<Map<String,Object>>)restaurant.get("menu");restaurant.put("menu",db.query("SELECT id,name,price,available FROM dishes WHERE restaurant_id=? ORDER BY id",(d,i)->{String dishId=d.getString("id");Map<String,Object> item=new java.util.LinkedHashMap<>(original.stream().filter(x->dishId.equals(x.get("id"))).findFirst().orElse(Map.of("id",dishId)));item.put("id",dishId);item.put("name",d.getString("name"));item.put("price",d.getBigDecimal("price"));item.put("available",d.getBoolean("available"));return item;},id));restaurant.put("preparationMinutes",rs.getInt("preparation_minutes"));return restaurant;}catch(Exception e){throw new IllegalStateException(e);}}).stream().filter(java.util.Objects::nonNull).toList();}
 boolean withinHours(String encoded){try{var hours=json.readValue(encoded,new TypeReference<Map<String,String>>(){});if(hours.isEmpty())return true;String day=LocalDate.now(zone).getDayOfWeek().name().toLowerCase();String range=hours.get(day);if(range==null||range.isBlank())return false;String[] parts=range.split("-",-1);if(parts.length!=2)return false;LocalTime open=LocalTime.parse(parts[0]),close=LocalTime.parse(parts[1]),now=LocalTime.now(zone);return open.equals(close)||(!open.isAfter(close)?(!now.isBefore(open)&&now.isBefore(close)):(!now.isBefore(open)||now.isBefore(close)));}catch(Exception e){return false;}}
 Map<String,Object> restaurant(String id){return restaurants().stream().filter(r->id.equals(r.get("id"))).findFirst().orElseThrow(()->new Failure(422,"Restaurant unavailable"));}
}
@Component
class Seed implements CommandLineRunner {
 private final Catalog catalog; private final PasswordEncoder passwords; private final String password;
 Seed(Catalog catalog,PasswordEncoder passwords,@Value("${foodflow.demo-password}")String password){this.catalog=catalog;this.passwords=passwords;this.password=password;}
 @Override @Transactional public void run(String...args)throws Exception{
  var db=catalog.db;
  for(var entry:Map.of("customer","CUSTOMER","customer2","CUSTOMER","restaurant","RESTAURANT","partner","PARTNER","partner2","PARTNER","admin","ADMIN").entrySet())if(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?",Integer.class,entry.getKey())==0)db.update("INSERT INTO app_users VALUES(?,?,?,?)",entry.getKey(),passwords.encode(password),entry.getValue(),entry.getValue().equals("RESTAURANT")?"green":null);
  for(String username:db.queryForList("SELECT username FROM app_users WHERE role='PARTNER'",String.class))if(db.queryForObject("SELECT COUNT(*) FROM partner_availability WHERE username=?",Integer.class,username)==0)db.update("INSERT INTO partner_availability(username,available,updated_at) VALUES(?,?,?)",username,true,java.time.Instant.now().toString());
  List<Map<String,Object>> restaurants=catalog.json.readValue(new ClassPathResource("catalog.json").getInputStream(),new TypeReference<>(){});
  for(var r:restaurants){if(db.queryForObject("SELECT COUNT(*) FROM restaurants WHERE id=?",Integer.class,r.get("id"))>0)continue;db.update("INSERT INTO restaurants(id,content) VALUES(?,?)",r.get("id"),catalog.json.writeValueAsString(r));
   for(var item:(List<?>)r.get("menu")){var dish=(Map<?,?>)item;db.update("INSERT INTO dishes(id,restaurant_id,name,price) VALUES(?,?,?,?)",dish.get("id"),r.get("id"),dish.get("name"),new BigDecimal(dish.get("price").toString()));}
  }
 }
}
@RestController
@RequestMapping("/api/v1")
class CatalogController {
 final Catalog catalog;CatalogController(Catalog catalog){this.catalog=catalog;}
 @GetMapping("/restaurants") List<Map<String,Object>> restaurants(){return catalog.restaurants();}
 @GetMapping("/health") Map<String,String> health(){catalog.db.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP","paymentMode","DEMO");}
}
