package com.foodflow;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.password=","foodflow.demo-operations=true"})
class CommerceTest {
 @Autowired Orders orders;
 @Autowired Payments payments;
 Authentication user(String name,String role){return new UsernamePasswordAuthenticationToken(name,"",List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
 Checkout checkout(){return new Checkout("green","Flat 201, Road 12, Jubilee Hills 500033","Jubilee Hills, Hyderabad",List.of(new Line("bowl",1)));}
 OrderView place(){var customer=user("customer","CUSTOMER");var pending=orders.place(customer,UUID.randomUUID().toString(),checkout());var payment=payments.create(customer,pending.id(),UUID.randomUUID().toString());payments.simulate(customer,payment.id(),"SUCCEEDED");return orders.get(customer,pending.id());}
 @Test void serverPricesAndIdempotency(){String key=UUID.randomUUID().toString();var customer=user("customer","CUSTOMER");var first=orders.place(customer,key,checkout());assertEquals("284.00",first.total());assertEquals(first.id(),orders.place(customer,key,checkout()).id());var changed=new Checkout("green",checkout().address(),checkout().area(),List.of(new Line("bowl",2)));assertEquals(409,assertThrows(Failure.class,()->orders.place(customer,key,changed)).status);}
 @Test void restrictsOwnershipAndPriceSources(){var saved=place();assertEquals(404,assertThrows(Failure.class,()->orders.get(user("customer2","CUSTOMER"),saved.id())).status);var wrongDish=new Checkout("green",checkout().address(),checkout().area(),List.of(new Line("margherita",1)));assertEquals(422,assertThrows(Failure.class,()->orders.place(user("customer","CUSTOMER"),UUID.randomUUID().toString(),wrongDish)).status);assertEquals(403,assertThrows(Failure.class,()->orders.advanceDemo(user("customer","CUSTOMER"),saved.id(),new Action("DEMO",0,"test"))).status);}
 @Test void legalWorkflowAndOptimisticVersion(){var saved=place();var restaurant=user("restaurant","RESTAURANT");var accepted=orders.transition(restaurant,saved.id(),new Action("ACCEPTED",saved.version(),"Kitchen accepted"));assertEquals(409,assertThrows(Failure.class,()->orders.transition(restaurant,saved.id(),new Action("PREPARING",saved.version(),"Old version"))).status);var cooking=orders.transition(restaurant,saved.id(),new Action("PREPARING",accepted.version(),"Cooking"));var ready=orders.transition(restaurant,saved.id(),new Action("READY_FOR_PICKUP",cooking.version(),"Ready"));var assigned=orders.assign(user("admin","ADMIN"),saved.id(),new Assign("partner",ready.version()));var picked=orders.transition(user("partner","PARTNER"),saved.id(),new Action("PICKED_UP",assigned.version(),"Pickup verified"));var delivered=orders.transition(user("partner","PARTNER"),saved.id(),new Action("DELIVERED",picked.version(),"Delivered"));assertEquals("DELIVERED",delivered.status());assertEquals(8,orders.history(user("customer","CUSTOMER"),saved.id()).size());}
 @Test void cancellationCreatesSandboxRefund(){var saved=place();var cancelled=orders.transition(user("customer","CUSTOMER"),saved.id(),new Action("CANCELLED",saved.version(),"Changed mind"));assertEquals("REFUNDED",cancelled.refundStatus());}
 @Test void concurrentRetryProducesOneOrder()throws Exception{String key=UUID.randomUUID().toString();try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<String> task=()->{start.await();return orders.place(user("customer","CUSTOMER"),key,checkout()).id();};var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}}
}
