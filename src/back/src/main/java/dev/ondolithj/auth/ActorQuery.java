package dev.ondolithj.auth;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
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
	/**
	 * 「superuser 역할」 술어 하나(별칭 r = roles). 인증·R6 대상 판정·마지막 관리자 셈·A-403·A-405 가 이것만 쓴다 —
	 * 셈이 인증보다 넓으면 인증이 인정하지 않는 보유자가 세어진다(스펙 5절). R9: 모두가 갖는 역할의 표시는 세지 않는다.
	 */
	public static final String SUPERUSER_ROLE = "(r.is_superuser AND r.key NOT IN ('anonymous', 'member'))";

	/** 사용자 u(별칭 u = users)가 superuser 역할을 user_roles 로 갖는다. */
	public static final String HOLDS_SUPERUSER = "EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
			+ "WHERE ur.user_id = u.id AND " + SUPERUSER_ROLE + ")";

	private static final String VISITOR_KEYS = Arrays.stream(Permission.values()).filter(Permission::visitor)
		.map(p -> "'" + p.key() + "'").collect(Collectors.joining(", ", "ARRAY[", "]::text[]"));

	// 유효 역할 = {anonymous, member} ∪ user_roles (D15 1.1).
	// R9: anonymous·member 의 부여는 방문자 권한만 센다 — 이 규칙 전에 들어간 부여까지 효력이 없다
	private static final String SQL = """
			SELECT u.email, u.display_name, u.is_active, u.sessions_valid_from,
			       COALESCE(bool_or(%s), false) AS superuser,
			       array_remove(array_agg(DISTINCT p.key), NULL) AS perms
			FROM users u
			LEFT JOIN roles r ON r.key IN ('anonymous', 'member')
			    OR r.id IN (SELECT ur.role_id FROM user_roles ur WHERE ur.user_id = u.id)
			LEFT JOIN role_permissions rp ON rp.role_id = r.id
			LEFT JOIN permissions p ON p.id = rp.permission_id
			    AND (r.key NOT IN ('anonymous', 'member') OR p.key = ANY(%s))
			WHERE u.id = ?
			GROUP BY u.id
			""".formatted(SUPERUSER_ROLE, VISITOR_KEYS);

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
			return new Actor(id, rs.getString("email"), rs.getString("display_name"), authAt, rs.getBoolean("superuser"),
					permissions(rs));
		}).optional();
	}

	/**
	 * 활성 여부·기준 시각을 보지 않은 유효 권한(Q20 활성화의 R2). load 와 같은 질의라 R9 가 한 곳에 남는다. 없으면 빈 값.
	 * 인증·요청자 판정에 쓰지 않는다 — 비활성 계정·회수된 세션의 권한도 그대로 돌려준다. 그런 판정은 load 를 쓴다.
	 */
	public Optional<Set<Permission>> permissions(UUID id) {
		return jdbc.sql(SQL).param(id).query((rs, n) -> permissions(rs)).optional();
	}

	/** superuser 면 코드의 권한 전부. */
	private static Set<Permission> permissions(ResultSet rs) throws SQLException {
		if (rs.getBoolean("superuser")) {
			return Set.copyOf(EnumSet.allOf(Permission.class));
		}
		Set<Permission> perms = EnumSet.noneOf(Permission.class);
		Array keys = rs.getArray("perms");
		for (Object k : (Object[]) keys.getArray()) {
			Permission.ofKey((String) k).ifPresent(perms::add);
		}
		return Set.copyOf(perms);
	}
}
