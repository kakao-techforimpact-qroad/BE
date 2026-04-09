package com.qroad.be.uuid;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(String path) {
        Counter.builder("user_access_total")
                .tag("path", path)
                .register(meterRegistry)
                .increment();
    }

    public void recordNewUser() {
        Counter.builder("new_user_total")
                .register(meterRegistry)
                .increment();
    }
}
