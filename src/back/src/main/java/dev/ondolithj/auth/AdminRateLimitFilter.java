package dev.ondolithj.auth;

import dev.ondolithj.shared.ClientIp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 경로 /api/admin/** 의 IP 당 분당 한도(D15 4.3-2). 보안 체인에만 넣는다 — 빈으로 두면 Boot 가 서블릿 필터로도
 * 등록해 두 번 센다(AuthConfig 가 서블릿 등록을 끈다). IP 는 ClientIp 한 곳에서 정한다.
 */
public class AdminRateLimitFilter extends OncePerRequestFilter {

	private final RateLimiter limiter;

	private final RateLimitProperties limits;

	private final JsonMapper json;

	AdminRateLimitFilter(RateLimiter limiter, RateLimitProperties limits, JsonMapper json) {
		this.limiter = limiter;
		this.limits = limits;
		this.json = json;
	}

	// MVC 와 같은 방식(디코딩한 경로 조각)으로 맞춘다 — 날 URI 를 비교하면 /api/%61dmin 이 세지 않고 MVC 에는 닿았다
	private static final RequestMatcher ADMIN = new OrRequestMatcher(PathPatternRequestMatcher.pathPattern("/api/admin"),
			PathPatternRequestMatcher.pathPattern("/api/admin/**"));

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !ADMIN.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		RateLimiter.Decision d = limiter.hit("admin-ip:" + ClientIp.of(request), limits.adminIp());
		if (!d.allowed()) {
			response.setStatus(429);
			response.setHeader("Retry-After", Long.toString(d.retryAfterSeconds()));
			response.setContentType("application/problem+json");
			response.setCharacterEncoding("UTF-8");
			json.writeValue(response.getOutputStream(), java.util.Map.of("type", "about:blank", "title", "Too Many Requests",
					"status", 429, "detail", "잠시 뒤 다시 시도하세요"));
			return;
		}
		chain.doFilter(request, response);
	}
}
