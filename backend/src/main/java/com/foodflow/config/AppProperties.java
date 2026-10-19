package com.foodflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralised, typed configuration for FoodFlow feature flags.
 * Follows DIP: services depend on this interface-like POJO rather than raw {@code @Value} strings.
 */
@Configuration
@ConfigurationProperties(prefix = "foodflow")
public class AppProperties {

    private String demoPassword = "FoodFlow-demo-2026!";
    private boolean demoOperations = true;

    public String getDemoPassword() {
        return demoPassword;
    }

    public void setDemoPassword(String demoPassword) {
        this.demoPassword = demoPassword;
    }

    public boolean isDemoOperations() {
        return demoOperations;
    }

    public void setDemoOperations(boolean demoOperations) {
        this.demoOperations = demoOperations;
    }
}
