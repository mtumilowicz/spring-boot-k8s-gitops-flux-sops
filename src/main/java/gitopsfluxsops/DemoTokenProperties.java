package gitopsfluxsops;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo")
public record DemoTokenProperties(String token1, String token2) {
}
