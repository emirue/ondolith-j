package dev.ondolithj.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 경로 /api/admin/** 의 IP 당 분당 한도(D15 4.3-2). 보안 체인에만 넣는다 — 빈으로 두면 Boot 가 서블릿 필터로도
 * 등록해 두 번 센다(AuthConfig 가 서블릿 등록을 끈다). ponytail: getRemoteAddr — 실제 IP 판정은 Phase 4.
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

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !(path.equals("/api/admin") || path.startsWith("/api/admin/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		RateLimiter.Decision d = limiter.hit("admin-ip:" + request.getRemoteAddr(), limits.adminIp());
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
