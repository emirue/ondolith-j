package dev.ondolithj.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.core.context.DeferredSecurityContext;

/**
 * 세션에는 사용자 ID 와 authAt(JDK 타입)만 두고, 권한이 필요할 때 DB 에서 인증을 새로 만든다(1-A 스펙 3.3·3.4).
 * Spring 기본 저장소는 권한 목록이 든 Authentication 을 통째로 세션에 넣는다 — 회수가 로그아웃 전까지 안 듣는다.
 * 세션은 getSession(false) 로만 읽는다: 익명 요청이 세션을 만들지 않는다.
 */
class SessionContextRepository implements SecurityContextRepository {

	static final String USER_ID = "ondolithj.user_id";

	static final String AUTH_AT = "ondolithj.auth_at";

	private final ActorQuery actors;

	SessionContextRepository(ActorQuery actors) {
		this.actors = actors;
	}

	@Override
	public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
		return new DeferredSecurityContext() {

			private SecurityContext context;

			private boolean generated;

			@Override
			public SecurityContext get() {
				if (context == null) {
					Optional<Authentication> auth = authenticate(request);
					generated = auth.isEmpty();
					context = auth.map(a -> (SecurityContext) new SecurityContextImpl(a))
						.orElseGet(SecurityContextHolder::createEmptyContext);
				}
				return context;
			}

			@Override
			public boolean isGenerated() {
				get();
				return generated;
			}
		};
	}

	private Optional<Authentication> authenticate(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return Optional.empty();
		}
		// 속성이 없거나 타입이 다르면(판이 바뀐 세션 등) 버리고 익명으로 이어진다
		if (!(session.getAttribute(USER_ID) instanceof String id) || !(session.getAttribute(AUTH_AT) instanceof Instant at)) {
			session.invalidate();
			return Optional.empty();
		}
		Optional<Actor> actor;
		try {
			actor = actors.load(UUID.fromString(id), at);
		}
		catch (IllegalArgumentException e) {
			actor = Optional.empty();
		}
		if (actor.isEmpty()) {
			// 없음·비활성·기준 시각 이전 — 세션을 지운다(D15 5.4)
			session.invalidate();
			return Optional.empty();
		}
		return actor.map(SessionContextRepository::token);
	}

	static Authentication token(Actor a) {
		return UsernamePasswordAuthenticationToken.authenticated(a, null,
				a.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
	}

	@Override
	@Deprecated
	public SecurityContext loadContext(HttpRequestResponseHolder holder) {
		return loadDeferredContext(holder.getRequest()).get();
	}

	@Override
	public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
		Authentication auth = context.getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Actor a) {
			HttpSession session = request.getSession(true);
			session.setAttribute(USER_ID, a.id().toString());
			session.setAttribute(AUTH_AT, a.authAt());
		}
	}

	@Override
	public boolean containsContext(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		return session != null && session.getAttribute(USER_ID) != null;
	}
}
