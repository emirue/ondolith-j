package dev.ondolithj.auth;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 요청마다 DB 에서 만드는 인증 주체. 세션에는 id·authAt 만 남는다(1-A 스펙 3.3).
 * authAt 은 로그인 때 그 계정의 sessions_valid_from 값이다 — 앱 시계가 아니다.
 */
public record Actor(UUID id, String email, String displayName, Instant authAt, boolean superuser,
		Set<Permission> permissions) {
}
