package com.foodflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Spring Security configuration.
 * SRP: only security filter chain, password encoding, and user lookup live here.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(JdbcTemplate db) {
        return username -> db.query(
            "SELECT * FROM app_users WHERE username=?",
            (rs, n) -> User.withUsername(rs.getString("username"))
                .password(rs.getString("password_hash"))
                .roles(rs.getString("role"))
                .build(),
            username
        ).stream().findFirst()
        .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(a -> a
                .requestMatchers(
                    "/api/v1/auth/csrf",
                    "/api/v1/auth/login",
                    "/api/v1/restaurants",
                    "/api/v1/health"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e
                .authenticationEntryPoint((q, r, x) -> r.sendError(401, "Sign in required"))
            )
            .logout(l -> l
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((q, r, a) -> r.setStatus(204))
            )
            .build();
    }
}
