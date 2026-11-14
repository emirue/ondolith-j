package dev.ondolithj.shared;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.ondolithj.auth.AdminRateLimitFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.json.JsonMapper;

/** 두 컨텍스트 공통 보안(Phase 0 스펙 3절, Phase 1-A 스펙 5·6절). */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	/** @RequiresPermission(Permission.X) 의 {value} 를 채운다(Spring Security 애너테이션 템플릿). */
	@Bean
	static AnnotationTemplateExpressionDefaults templateExpressionDefaults() {
		return new AnnotationTemplateExpressionDefaults();
	}

	@Bean
	SecurityFilterChain api(HttpSecurity http, JsonMapper json, ObjectProvider<SecurityContextRepository> contexts,
			ObjectProvider<AdminRateLimitFilter> adminLimit) throws Exception {
		// 운영 컨텍스트: 관리자 경로 횟수 제한(1-A 스펙 4절)
		adminLimit.ifAvailable(f -> http.addFilterBefore(f, AuthorizationFilter.class));
		// 운영 컨텍스트: 세션에는 사용자 ID·authAt 만 두는 저장소(1-A 스펙 3.3). 설치 컨텍스트에는 없다
		contexts.ifAvailable(r -> {
			try {
				http.securityContext(s -> s.securityContextRepository(r));
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		http
			// 프런트가 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 돌려보낸다(Spring Security 7.1 SPA 설정)
			.csrf(c -> c.spa())
			// 기본 /logout 을 끈다 — 로그아웃은 POST /api/auth/logout(1-A 스펙 3.2)
			.logout(l -> l.disable())
			// 401 때 원래 요청을 세션에 저장하지 않는다 — 익명 요청마다 세션 행이 생기면 안 된다(1-A 스펙 3.3)
			.requestCache(r -> r.requestCache(new NullRequestCache()))
			// 401·403(CSRF 거부 포함)도 Problem Details. 401 에 WWW-Authenticate 를 붙이지 않는다(브라우저 로그인 창)
			.exceptionHandling(e -> e
				.authenticationEntryPoint((req, res, ex) -> problem(res, json, HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"))
				.accessDeniedHandler((req, res, ex) -> problem(res, json, HttpStatus.FORBIDDEN, "권한이 없습니다")))
			.headers(h -> h.frameOptions(f -> f.deny())
				.referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
			// URL 층은 열어 둔다. 기본 거부는 메서드마다 붙인 @RequiresPermission·@PublicApi 와 기동 점검이 맡는다(1-A 스펙 5절).
			// 없는 경로는 MVC 가 404 를 낸다(D-3)
			.authorizeHttpRequests(a -> a.anyRequest().permitAll());
		return http.build();
	}

	static void problem(HttpServletResponse res, JsonMapper json, HttpStatus status, String detail) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "about:blank");
		body.put("title", status.getReasonPhrase());
		body.put("status", status.value());
		body.put("detail", detail);
		res.setStatus(status.value());
		res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		res.setCharacterEncoding("UTF-8");
		json.writeValue(res.getOutputStream(), body);
	}
}
