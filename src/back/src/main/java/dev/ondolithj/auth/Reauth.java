package dev.ondolithj.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 재인증 창(1-B2 스펙 3.1). 세션 셋째 속성(선택)에 로그인·재인증 때 앱 시계로 시각을 찍고, 15분 안이면 열림이다.
 * 닫힘은 세션을 버리지 않는다 — 버리는 것은 필수 속성 둘뿐이다(SessionContextRepository).
 * 세션은 getSession(false) 로만 읽는다: 없으면 만들지 않는다.
 */
public class Reauth {

	public static final String AT = "ondolithj.reauth_at";

	static final Duration WINDOW = Duration.ofMinutes(15);

	/** 재인증 대상 처리기가 창이 닫힌 세션으로 불렸다. ApiErrors 가 재인증 전용 403 으로 바꾼다. */
	public static final class Required extends RuntimeException {

		public Required() {
			super("재인증이 필요합니다", null, false, false);
		}
	}

	private final Clock clock;

	Reauth(Clock clock) {
		this.clock = clock;
	}

	/** 값이 Instant t 이고 t ≤ now < t + 15분. 없음·다른 타입·미래(시계가 뒤로 감)는 닫힘. */
	public static boolean open(Object value, Instant now) {
		return value instanceof Instant t && !t.isAfter(now) && now.isBefore(t.plus(WINDOW));
	}

	/** 재인증 대상 처리기의 첫 줄. 창이 닫혀 있으면 Required. */
	public void require(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (!open(session == null ? null : session.getAttribute(AT), clock.instant())) {
			throw new Required();
		}
	}

	/** 기존 세션에 지금 시각을 찍는다(로그인·재인증 성공). */
	void stamp(HttpServletRequest request) {
		request.getSession(false).setAttribute(AT, clock.instant());
	}
}
