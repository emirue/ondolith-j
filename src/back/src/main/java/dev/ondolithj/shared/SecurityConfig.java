package dev.ondolithj.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/** 두 컨텍스트 공통 보안(스펙 3절). */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		http
			// 프런트가 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 돌려보낸다(Spring Security 7.1 SPA 설정)
			.csrf(c -> c.spa())
			.headers(h -> h.frameOptions(f -> f.deny())
				.referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
			// ponytail: Phase 0 은 공개 API 뿐이라 permitAll — 없는 경로는 MVC 가 404 를 내야 한다(D-3).
			// Phase 1 로그인과 함께 기본 거부 + 경로별 허용으로 바꾼다
			.authorizeHttpRequests(a -> a.anyRequest().permitAll());
		return http.build();
	}
}
