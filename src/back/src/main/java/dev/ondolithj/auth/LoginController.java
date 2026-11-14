package dev.ondolithj.auth;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/auth/login·logout(1-A 스펙 3.1·3.2). Spring 문서의 REST 로그인 방식을 따른다. */
@RestController
class LoginController {

	private static final Logger log = LoggerFactory.getLogger(LoginController.class);

	private final AuthenticationManager authentication;

	private final SessionAuthenticationStrategy sessionStrategy;

	private final SecurityContextRepository contexts;

	private final ActorQuery actors;

	private final RateLimiter limiter;

	private final RateLimitProperties limits;

	LoginController(AuthenticationManager authentication, SessionAuthenticationStrategy sessionStrategy,
			SecurityContextRepository contexts, ActorQuery actors, RateLimiter limiter, RateLimitProperties limits) {
		this.authentication = authentication;
		this.sessionStrategy = sessionStrategy;
		this.contexts = contexts;
		this.actors = actors;
		this.limiter = limiter;
		this.limits = limits;
	}

	/** 응답 코드는 OpenApiFileTest 가 문서를 확인한다. 애너테이션은 실행 jar 에 없어도 무시된다. */
	@PublicApi
	@PostMapping("/api/auth/login")
	@ApiResponse(responseCode = "204", description = "로그인됨. 세션 쿠키와 새 CSRF 토큰", content = @Content)
	@ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호가 맞지 않음(원인과 관계없이 같다)", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "입력 오류", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "시도가 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
		String email = req.email().strip().toLowerCase(Locale.ROOT);
		// 비밀번호를 대조하기 전에 거른다(FR-209). ponytail: getRemoteAddr — CloudFront 뒤의 실제 IP 는 Phase 4 에서
		// 믿을 프록시를 정해 바꾼다. X-Forwarded-For 를 그대로 믿으면 위조로 제한을 피한다
		Optional<RateLimiter.Decision> blocked = limit(limiter, limits, request.getRemoteAddr(), email);
		if (blocked.isPresent()) {
			log.info("로그인 횟수 제한");
			return tooMany(blocked.get());
		}
		Authentication result;
		try {
			result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, req.password()));
		}
		// BCrypt 72바이트를 넘는 비밀번호는 인코더가 IllegalArgumentException 을 던진다 — 500 이 아니라 같은 실패다
		catch (AuthenticationException | IllegalArgumentException e) {
			log.info("로그인 실패");
			return failed();
		}
		LoginUser user = (LoginUser) result.getPrincipal();
		// authAt 은 비밀번호 해시와 같은 질의에서 읽은 sessions_valid_from 이다(앱 시계가 아니다, D15 5.4)
		Optional<Actor> actor = actors.load(user.id(), user.validFrom());
		if (actor.isEmpty()) {
			log.info("로그인 실패");
			return failed();
		}
		Authentication token = SessionContextRepository.token(actor.get());
		sessionStrategy.onAuthentication(token, request, response);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(token);
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
		// 새 CSRF 토큰을 이 응답의 쿠키로 바로 낸다(다음 요청이 옛 토큰을 쓰지 않게)
		if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf) {
			csrf.getToken();
		}
		log.info("로그인 성공: 사용자 {}", user.id());
		return ResponseEntity.noContent().build();
	}

	@PublicApi
	@PostMapping("/api/auth/logout")
	@ApiResponse(responseCode = "204", description = "로그아웃됨(세션이 없어도)", content = @Content)
	@ApiResponse(responseCode = "403", description = "CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		// 세션이 있으면 무효화한다 → SPRING_SESSION 행이 지워진다(FR-203). 없으면 만들지 않는다
		new SecurityContextLogoutHandler().logout(request, response, null);
		return ResponseEntity.noContent().build();
	}

	/**
	 * 막혔으면 그 결정. IP 를 먼저 보고, IP 에서 막히면 계정 키는 세지 않는다 — 한 IP 가 1분에 만드는 키 수를 묶는다.
	 * 계정 키는 없는 계정도 똑같이 센다(셈으로 계정이 있는지 드러나지 않게).
	 */
	static Optional<RateLimiter.Decision> limit(RateLimiter limiter, RateLimitProperties limits, String ip, String email) {
		RateLimiter.Decision byIp = limiter.hit("login-ip:" + ip, limits.loginIp());
		if (!byIp.allowed()) {
			return Optional.of(byIp);
		}
		RateLimiter.Decision byAccount = limiter.hit("login-account:" + email, limits.loginAccount());
		return byAccount.allowed() ? Optional.empty() : Optional.of(byAccount);
	}

	static ResponseEntity<ProblemDetail> tooMany(RateLimiter.Decision d) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header("Retry-After", Long.toString(d.retryAfterSeconds()))
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "잠시 뒤 다시 시도하세요"));
	}

	private static final String PROBLEM = "application/problem+json";

	private static ResponseEntity<ProblemDetail> failed() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다"));
	}
}
