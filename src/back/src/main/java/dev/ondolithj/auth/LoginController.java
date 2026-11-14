package dev.ondolithj.auth;

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

	LoginController(AuthenticationManager authentication, SessionAuthenticationStrategy sessionStrategy,
			SecurityContextRepository contexts, ActorQuery actors) {
		this.authentication = authentication;
		this.sessionStrategy = sessionStrategy;
		this.contexts = contexts;
		this.actors = actors;
	}

	@PublicApi
	@PostMapping("/api/auth/login")
	ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
		String email = req.email().strip().toLowerCase(Locale.ROOT);
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
	ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		// 세션이 있으면 무효화한다 → SPRING_SESSION 행이 지워진다(FR-203). 없으면 만들지 않는다
		new SecurityContextLogoutHandler().logout(request, response, null);
		return ResponseEntity.noContent().build();
	}

	private static ResponseEntity<ProblemDetail> failed() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다"));
	}
}
