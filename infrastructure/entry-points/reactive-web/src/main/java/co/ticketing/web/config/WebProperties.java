package co.ticketing.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ticketing.web")
public record WebProperties(Duration availabilityCacheTtl, Duration streamInterval, Duration streamMaxDuration,
                            RateLimit rateLimit) {

    public WebProperties {
        availabilityCacheTtl = availabilityCacheTtl == null ? Duration.ofSeconds(1) : availabilityCacheTtl;
        streamInterval = streamInterval == null ? Duration.ofSeconds(1) : streamInterval;
        streamMaxDuration = streamMaxDuration == null ? Duration.ofMinutes(30) : streamMaxDuration;
        rateLimit = rateLimit == null ? new RateLimit(20, 5) : rateLimit;
    }

    /** Token bucket per client for purchase endpoints: {@code capacity} burst, {@code refillPerSecond} sustained. */
    public record RateLimit(int capacity, double refillPerSecond) {
    }
}
