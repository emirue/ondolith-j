package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 「권한 전부 ≠ superuser」(1-B2 최종 검토 I-1). 코드의 권한을 모두 받은 비-superuser 역할도 R3·R5·R6 에 막혀야 한다 —
 * superuser 판정을 「권한을 다 가졌는가」로 바꾸면 이 시험이 실패한다. A-404 로 모든 권한을 받은 역할이 superuser 가 되면 안 된다.
 */
class SuperuserLadderTest {

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
		// 코드의 권한 전부를 가진, superuser 가 아닌 역할
		f.sql("INSERT INTO roles (key, name) VALUES ('ladder_all', '전부')");
		f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r, permissions p WHERE r.key = 'ladder_all'");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static List<String> q(String sql, Object... params) throws Exception {
		try (Connection c = f.db(); PreparedStatement s = c.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				s.setObject(i + 1, params[i]);
			}
			List<String> out = new ArrayList<>();
			try (ResultSet r = s.executeQuery()) {
				while (r.next()) {
					out.add(r.getString(1));
				}
			}
			return out;
		}
	}

	static UUID role(String key) throws Exception {
		return UUID.fromString(q("SELECT id::text FROM roles WHERE key = ?", key).getFirst());
	}

	static List<String> roles(UUID user) throws Exception {
		return q("SELECT r.key FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY 1", user);
	}

	static List<String> denied(UUID actor) throws Exception {
		return q("SELECT summary FROM operation_logs WHERE actor_user_id = ? AND action IN ('user.denied', 'role.denied') "
				+ "ORDER BY created_at, id", actor);
	}

	static String active(UUID id) throws Exception {
		return q("SELECT is_active::text FROM users WHERE id = ?", id).getFirst();
	}

	static HttpResponse<String> role(TestHttp h, UUID user, UUID role, String suffix) throws Exception {
		return h.post("/api/admin/users/" + user + "/roles" + suffix, "{\"role_id\": \"" + role + "\"}");
	}

	@Test
	void 권한_전부를_가진_비superuser_는_R3_R5_R6_에_막힌다() throws Exception {
		UUID x = f.user("ladder-all@ondolith.test", "ladder_all");
		TestHttp h = f.loginAs("ladder-all@ondolith.test");
		// 전제: can 이 전부 true 다(코드의 권한 전부를 가졌다)
		assertThat(h.get("/api/me").body()).doesNotContain("false");

		UUID t = f.user("ladder-t@ondolith.test");
		UUID holder = f.user("ladder-holder@ondolith.test", "admin");
		UUID locked = f.user("ladder-locked@ondolith.test", "admin");
		f.sql("UPDATE users SET is_active = false WHERE id = ?", locked);
		UUID admin = role("admin");

		// R3: admin 부여(활성·비활성 대상), 그리고 admin 빼기(R6 을 역할 쪽으로 돌아가는 길)
		assertThat(role(h, t, admin, "").statusCode()).isEqualTo(403);
		assertThat(role(h, locked, admin, "/revoke").statusCode()).isEqualTo(403);
		assertThat(role(h, holder, admin, "/revoke").statusCode()).isEqualTo(403);
		// R6: 보유자 비활성화·활성화
		assertThat(h.post("/api/admin/users/" + holder + "/deactivate", "").statusCode()).isEqualTo(403);
		assertThat(h.post("/api/admin/users/" + locked + "/activate", "").statusCode()).isEqualTo(403);
		// R5: admin 역할의 권한
		assertThat(h.post("/api/admin/roles/" + admin + "/permissions", "{\"permission\": \"log.view\"}").statusCode())
			.isEqualTo(403);

		assertThat(roles(t)).isEmpty();
		assertThat(roles(holder)).containsExactly("admin");
		assertThat(roles(locked)).containsExactly("admin");
		assertThat(active(holder)).isEqualTo("true");
		assertThat(active(locked)).isEqualTo("false");
		assertThat(denied(x)).containsExactly("user.grant_role admin r3", "user.revoke_role admin r3",
				"user.revoke_role admin r3", "user.deactivate r6", "user.activate r6", "role.grant_permission log.view r5");

		// 자기 역할(전부)을 남에게 주는 것은 R2 가 허용한다 — 받은 사람도 superuser 는 아니다
		assertThat(role(h, t, role("ladder_all"), "").statusCode()).isEqualTo(204);
		TestHttp ht = f.loginAs("ladder-t@ondolith.test");
		assertThat(role(ht, x, admin, "").statusCode()).isEqualTo(403);
		assertThat(roles(x)).containsExactly("ladder_all");
	}
}
