package dev.ondolithj.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import tools.jackson.databind.json.JsonMapper;

/** 운영 컨텍스트의 로그인 부품(1-A 스펙 3절). 설치 컨텍스트는 이 패키지를 스캔하지 않는다 — 로그인·세션이 없다. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class AuthConfig {

	@Bean
	RateLimiter rateLimiter() {
		return new RateLimiter(Clock.systemUTC());
	}

	@Bean
	Reauth reauth() {
		return new Reauth(Clock.systemUTC());
	}

	@Bean
	AdminRateLimitFilter adminRateLimitFilter(RateLimiter limiter, RateLimitProperties limits, JsonMapper json) {
		return new AdminRateLimitFilter(limiter, limits, json);
	}

	/** 보안 체인에만 넣는다(SecurityConfig). 서블릿 필터로도 등록되면 같은 요청을 두 번 센다. */
	@Bean
	FilterRegistrationBean<AdminRateLimitFilter> adminRateLimitFilterRegistration(AdminRateLimitFilter filter) {
		FilterRegistrationBean<AdminRateLimitFilter> r = new FilterRegistrationBean<>(filter);
		r.setEnabled(false);
		return r;
	}

	@Bean
	SecurityContextRepository sessionContextRepository(ActorQuery actors) {
		return new SessionContextRepository(actors);
	}

	/**
	 * 비활성 계정은 「없는 계정」으로 조회한다 — DaoAuthenticationProvider 는 비활성 검사를 비밀번호 대조 전에 해서,
	 * 그대로 두면 비활성 계정만 BCrypt 를 건너뛰어 응답 시간으로 존재가 드러난다. 없는 계정은 Spring 이 가짜 BCrypt 비교를 한다.
	 */
	@Bean
	AuthenticationManager loginAuthentication(JdbcClient jdbc) {
		UserDetailsService users = email -> jdbc
			.sql("SELECT id, email, password_hash, sessions_valid_from FROM users WHERE email = ? AND is_active")
			.param(email)
			.query((rs, n) -> new LoginUser(rs.getObject("id", UUID.class), rs.getString("email"),
					rs.getString("password_hash"), rs.getObject("sessions_valid_from", OffsetDateTime.class).toInstant()))
			.optional()
			.orElseThrow(() -> new UsernameNotFoundException("없는 계정"));
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(new BCryptPasswordEncoder());
		return new ProviderManager(provider);
	}

	/** 로그인 때 세션 ID 를 새로 받고(FR-204) CSRF 토큰을 바꾼다. REST 로그인에서는 Spring 이 이것을 자동으로 걸지 않는다. */
	@Bean
	SessionAuthenticationStrategy loginSessionStrategy() {
		return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),
				new CsrfAuthenticationStrategy(CookieCsrfTokenRepository.withHttpOnlyFalse())));
	}
}
