package dev.ondolithj.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 분당 한도(ondolith D15 4.3-2). 사내망처럼 IP 가 하나로 뭉치는 곳을 위해 바꿀 수 있다. */
@ConfigurationProperties("ondolithj.rate-limit")
public record RateLimitProperties(@DefaultValue("10") int loginIp, @DefaultValue("5") int loginAccount,
		@DefaultValue("60") int adminIp) {
}
