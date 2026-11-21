package dev.ondolithj.auth;

import java.sql.Array;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 사용자와 유효 권한을 쿼리 한 번으로 읽는다(ondolith D15 4.3-1). 권한은 세션에 담지 않으므로 회수가 다음 요청에 반영된다. */
@Component
public class ActorQuery {

	/** 방문자 권한 키. 코드의 enum 에서 오므로(형식이 고정된 상수) SQL 에 그대로 넣는다. */
	private static final String VISITOR_KEYS = Arrays.stream(Permission.values()).filter(Permission::visitor)
		.map(p -> "'" + p.key() + "'").collect(Collectors.joining(", ", "ARRAY[", "]::text[]"));

	// 유효 역할 = {anonymous, member} ∪ user_roles (D15 1.1).
	// R9: anonymous·member 의 부여는 방문자 권한만 센다 — 이 규칙 전에 들어간 부여까지 효력이 없다
	private static final String SQL = """
			SELECT u.email, u.display_name, u.is_active, u.sessions_valid_from,
			       COALESCE(bool_or(r.is_superuser) FILTER (WHERE r.key NOT IN ('anonymous', 'member')), false) AS superuser,
			       array_remove(array_agg(DISTINCT p.key), NULL) AS perms
			FROM users u
			LEFT JOIN roles r ON r.key IN ('anonymous', 'member')
			    OR r.id IN (SELECT ur.role_id FROM user_roles ur WHERE ur.user_id = u.id)
			LEFT JOIN role_permissions rp ON rp.role_id = r.id
			LEFT JOIN permissions p ON p.id = rp.permission_id
			    AND (r.key NOT IN ('anonymous', 'member') OR p.key = ANY(%s))
			WHERE u.id = ?
			GROUP BY u.id
			""".formatted(VISITOR_KEYS);

	private final JdbcClient jdbc;

	ActorQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 없음·비활성·기준 시각 이전 로그인(authAt < sessions_valid_from)이면 빈 값. 같으면 유효하다. */
	public Optional<Actor> load(UUID id, Instant authAt) {
		return jdbc.sql(SQL).param(id).query((rs, n) -> {
			if (!rs.getBoolean("is_active")) {
				return null;
			}
			Instant validFrom = rs.getObject("sessions_valid_from", OffsetDateTime.class).toInstant();
			if (authAt.isBefore(validFrom)) {
				return null;
			}
			Set<Permission> perms = EnumSet.noneOf(Permission.class);
			if (rs.getBoolean("superuser")) {
				perms = EnumSet.allOf(Permission.class);
			}
			else {
				Array keys = rs.getArray("perms");
				for (Object k : (Object[]) keys.getArray()) {
					Permission.ofKey((String) k).ifPresent(perms::add);
				}
			}
			return new Actor(id, rs.getString("email"), rs.getString("display_name"), authAt, Set.copyOf(perms));
		}).optional();
	}
}
