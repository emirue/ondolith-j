package dev.ondolithj.auth;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/me — 화면 표시용 로그인 상태(1-A 스펙 6절). can 은 UX 일 뿐 보안 판정은 서버가 메서드마다 한다(D15 4.3). */
@RestController
class MeController {

	record User(String email, String displayName) {
	}

	record Me(User user, Map<String, Boolean> can) {
	}

	@PublicApi
	@GetMapping("/api/me")
	Me me() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		Actor actor = auth != null && auth.getPrincipal() instanceof Actor a ? a : null;
		Map<String, Boolean> can = new LinkedHashMap<>();
		Arrays.stream(Permission.values()).forEach(p -> can.put(p.key(), actor != null && actor.permissions().contains(p)));
		return new Me(actor == null ? null : new User(actor.email(), actor.displayName()), can);
	}
}
