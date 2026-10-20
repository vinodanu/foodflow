package com.foodflow;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:customer_experience;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.password="})
class CustomerExperienceTest {
 @Autowired Orders orders;
 @Autowired Payments payments;
 @Autowired CustomerExperience experience;
 Authentication user(String name,String role){return new UsernamePasswordAuthenticationToken(name,"",List.of(new SimpleGrantedAuthority("ROLE_"+role)));}

 @Test void orderEventsReachOnlyTheCustomerAndSupportIsScoped(){
  var customer=user("customer","CUSTOMER");
  var order=orders.place(customer,UUID.randomUUID().toString(),new Checkout("green","Flat 201, Road 12, Jubilee Hills 500033","Jubilee Hills, Hyderabad",List.of(new Line("bowl",1))));
  var payment=payments.create(customer,order.id(),UUID.randomUUID().toString());payments.simulate(customer,payment.id(),"SUCCEEDED");
  var inbox=experience.notifications(customer);
  assertTrue(inbox.stream().anyMatch(n->n.get("title").toString().contains("Order received")));
  assertTrue(inbox.stream().anyMatch(n->n.get("message").toString().contains("SUCCEEDED")));
  assertTrue(experience.notifications(user("customer2","CUSTOMER")).isEmpty());
  var request=experience.createSupport(customer,new SupportRequestInput(order.id(),"Missing item","One dish was missing from my delivered order."));
  assertEquals("OPEN",request.get("status"));
  assertEquals(1,experience.supportList(customer).size());
  assertEquals(403,assertThrows(Failure.class,()->experience.notifications(user("partner","PARTNER"))).status);
  assertEquals(404,assertThrows(Failure.class,()->experience.createSupport(user("customer2","CUSTOMER"),new SupportRequestInput(order.id(),"Wrong owner","I cannot access this order."))).status);
  experience.read(customer,inbox.getFirst().get("id").toString());
  assertNotNull(experience.notifications(customer).stream().filter(n->n.get("id").equals(inbox.getFirst().get("id"))).findFirst().orElseThrow().get("read_at"));
 }
}
