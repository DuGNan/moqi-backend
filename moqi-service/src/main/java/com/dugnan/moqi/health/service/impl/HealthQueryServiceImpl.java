package com.dugnan.moqi.health.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Service;

import com.dugnan.moqi.health.service.HealthQueryService;

@Service
public class HealthQueryServiceImpl implements HealthQueryService {

    private final HealthEndpoint healthEndpoint;

    public HealthQueryServiceImpl(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Override
    public Map<String, Object> currentHealth() {
        HealthComponent health = healthEndpoint.health();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", health.getStatus().getCode());
        if (health instanceof Health singleHealth) {
            payload.put("details", singleHealth.getDetails());
        } else if (health instanceof CompositeHealth compositeHealth) {
            payload.put("details", compositeHealth.getComponents());
        } else {
            payload.put("details", Map.of());
        }
        return payload;
    }
}
