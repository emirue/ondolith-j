package dev.ondolithj;

import dev.ondolithj.shared.AppState;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/** 운영 컨텍스트. 설치 패키지는 스캔하지 않는다 — 설치 API 가 운영에 남지 않는다. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "dev\\.ondolithj\\.install\\..*"))
public class RunningApplication {

	@Bean
	AppState appState() {
		return new AppState("running");
	}
}
