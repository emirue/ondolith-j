package dev.ondolithj.auth;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/admin/reauth(1-B2 스펙 3.3). 요청자의 이메일로 로그인과 같은 대조를 하고 재인증 시각을 찍는다.
 * 인증 주체가 같으므로 세션 ID 를 바꾸지 않는다. 작업 로그에는 남기지 않는다(원문과 같다).
 */
@RestController
class ReauthController {

	private static final Logger log = LoggerFactory.getLogger(ReauthController.class);

	private static final String PROBLEM = "application/problem+json";

	record ReauthRequest(@NotEmpty(message = "필수입니다") @Size(max = 1000, message = "1000자 이하") String password) {

		/** 비밀번호가 로그(DEBUG 의 요청 본문 등)에 새지 않게 가린다. */
		@Override
		public String toString() {
			return "ReauthRequest[]";
		}
	}

	private final AuthenticationManager authentication;

	private final Reauth reauth;

	private final RateLimiter limiter;

	private final RateLimitProperties limits;

	ReauthController(AuthenticationManager authentication, Reauth reauth, RateLimiter limiter, RateLimitProperties limits) {
		this.authentication = authentication;
		this.reauth = reauth;
		this.limiter = limiter;
		this.limits = limits;
	}

	@RequiresPermission(Permission.ADMIN_ACCESS)
	@PostMapping("/api/admin/reauth")
	@ApiResponse(responseCode = "204", description = "재인증됨. 15분 동안 재인증 대상 동작이 열린다", content = @Content)
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "비밀번호가 맞지 않음(type urn:ondolithj:problem:reauth-required)·admin.access 권한 없음·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "입력 오류. errors.password", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "시도가 너무 많음(계정당·관리자 경로). Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<?> reauth(@Valid @RequestBody ReauthRequest req, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		// 비밀번호를 대조하기 전에 거른다
		RateLimiter.Decision d = limiter.hit("reauth:" + actor.id(), limits.reauth());
		if (!d.allowed()) {
			log.info("재인증 횟수 제한: 사용자 {}", actor.id());
			return LoginController.tooMany(d);
		}
		try {
			authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(actor.email(), req.password()));
		}
		// 계정을 읽지 못한 것(DB 장애 등)은 비밀번호 실패가 아니다 — 로그인과 같게 500 으로 낸다
		catch (InternalAuthenticationServiceException e) {
			throw new IllegalStateException("재인증 중 계정을 읽지 못했습니다", e);
		}
		// spring-security-crypto 7.1.2 의 BCrypt.checkpw 는 73바이트 비밀번호에 false 를 낸다. catch 는 판이 바뀌어 다시 던질 때를 위해 둔다
		catch (AuthenticationException | IllegalArgumentException e) {
			log.info("재인증 실패: 사용자 {}", actor.id());
			throw new Reauth.Required();
		}
		reauth.stamp(request);
		log.info("재인증 성공: 사용자 {}", actor.id());
		return ResponseEntity.noContent().build();
	}
}
