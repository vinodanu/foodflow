package com.foodflow;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@Configuration
class Security {
 @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder();}
 @Bean UserDetailsService users(JdbcTemplate db){return username->db.query("SELECT * FROM app_users WHERE username=?",(rs,n)->User.withUsername(rs.getString("username")).password(rs.getString("password_hash")).roles(rs.getString("role")).build(),username).stream().findFirst().orElseThrow(()->new UsernameNotFoundException("Invalid credentials"));}
 @Bean AuthenticationManager authenticationManager(UserDetailsService users,PasswordEncoder passwords){var provider=new DaoAuthenticationProvider(users);provider.setPasswordEncoder(passwords);return new ProviderManager(provider);}
 @Bean SecurityFilterChain chain(HttpSecurity http)throws Exception{return http.csrf(c->c.ignoringRequestMatchers("/api/v1/payments/webhook")).authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/restaurants","/api/v1/health","/api/v1/payments/webhook").permitAll().anyRequest().authenticated()).exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->r.sendError(401,"Sign in required"))).logout(l->l.logoutUrl("/api/v1/auth/logout").logoutSuccessHandler((q,r,a)->r.setStatus(204))).build();}
}

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
 private final AuthenticationManager manager;
 AuthController(AuthenticationManager manager){this.manager=manager;}
 record Login(@NotBlank String username,@NotBlank String password){}
 @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
 @PostMapping("/login") Map<String,Object> login(@Valid @RequestBody Login input,HttpServletRequest request,HttpServletResponse response){var auth=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username(),input.password()));var session=request.getSession(false);if(session!=null)request.changeSessionId();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);new HttpSessionSecurityContextRepository().saveContext(context,request,response);return Map.of("username",auth.getName(),"roles",auth.getAuthorities().stream().map(Object::toString).toList());}
 @GetMapping("/me") Map<String,Object> me(Authentication auth){return Map.of("username",auth.getName(),"roles",auth.getAuthorities().stream().map(Object::toString).toList());}
}
