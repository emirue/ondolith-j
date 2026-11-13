package dev.ondolithj.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 두 컨텍스트 공통 보안. */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		// ponytail: Phase 0 은 공개 API 뿐이라 permitAll. Phase 1 로그인과 함께 기본 거부 + 경로별 허용으로 바꾼다
		http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
		return http.build();
	}
}
