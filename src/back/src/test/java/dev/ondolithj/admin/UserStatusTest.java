package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A-402 상세·비활성·활성(1-B2 스펙 4절), 경로 UUID 404(7절), 재인증 창 통합(3.1). operation_logs 는 비울 수 없으니
 * 시험마다 새 계정으로 센다. 재인증 한도는 기본 5회/분이다.
 */
class UserStatusTest {

	static final String URN = "urn:ondolithj:problem:reauth-required";

	static final AtomicInteger SEQ = new AtomicInteger();

	static final JsonMapper json = JsonMapper.builder().build();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
		// superuser 가 아니면서 비활성·활성을 부를 수 있는 시험 역할(R6). 한 번만 넣는다 — key 는 UNIQUE 다
		f.sql("INSERT INTO roles (key, name) VALUES ('probe_users', '시험')");
		f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r, permissions p "
				+ "WHERE r.key = 'probe_users' AND p.key IN ('admin.access', 'user.update')");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static String email() {
		return "status" + SEQ.incrementAndGet() + "@ondolith.test";
	}

	record Login(UUID id, TestHttp http) {
	}

	static Login login(String... roles) throws Exception {
		String email = email();
		UUID id = f.user(email, roles);
		return new Login(id, f.loginAs(email));
	}

	static HttpResponse<String> deactivate(TestHttp http, Object id) throws Exception {
		return http.post("/api/admin/users/" + id + "/deactivate", "");
	}

	static HttpResponse<String> activate(TestHttp http, Object id) throws Exception {
		return http.post("/api/admin/users/" + id + "/activate", "");
	}

	static boolean active(UUID id) throws SQLException {
		try (Connection c = f.db(); PreparedStatement s = c.prepareStatement("SELECT is_active FROM users WHERE id = ?")) {
			s.setObject(1, id);
			try (ResultSet r = s.executeQuery()) {
				r.next();
				return r.getBoolean(1);
			}
		}
	}

	/** 요청자의 작업 로그 행 `action|target_id|summary`, 시각 순. */
	static List<String> rows(UUID actor) throws SQLException {
		try (Connection c = f.db(); PreparedStatement s = c.prepareStatement(
				"SELECT action || '|' || coalesce(target_id, '') || '|' || summary FROM operation_logs "
						+ "WHERE actor_user_id = ? AND action <> 'auth.login' ORDER BY created_at, id")) {
			s.setObject(1, actor);
			List<String> out = new ArrayList<>();
			try (ResultSet r = s.executeQuery()) {
				while (r.next()) {
					out.add(r.getString(1));
				}
			}
			return out;
		}
	}

	static void reauthRequired(HttpResponse<String> r) {
		assertThat(r.statusCode()).as("%s", r.uri()).isEqualTo(403);
		assertThat(r.body()).as("%s", r.uri()).contains("\"type\":\"" + URN + "\"");
	}

	static void refused(HttpResponse<String> r, int status, String detail) {
		assertThat(r.statusCode()).as("%s", r.uri()).isEqualTo(status);
		assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
		JsonNode body = json.readTree(r.body());
		assertThat(body.get("detail").asString()).isEqualTo(detail);
		assertThat(r.body()).doesNotContain(URN);
	}

	@Test
	void 비활성화했다가_활성화해도_그_전_세션은_401() throws Exception {
		Login admin = login("admin");
		Login t = login("editor");
		assertThat(t.http().get("/api/admin").statusCode()).isEqualTo(200);
		assertThat(deactivate(admin.http(), t.id()).statusCode()).isEqualTo(204);
		assertThat(active(t.id())).isFalse();
		assertThat(activate(admin.http(), t.id()).statusCode()).isEqualTo(204);
		assertThat(active(t.id())).isTrue();
		assertThat(t.http().get("/api/admin").statusCode()).isEqualTo(401);
		assertThat(rows(admin.id())).containsExactly("user.deactivate|" + t.id() + "|", "user.activate|" + t.id() + "|");
	}

	@Test
	void 창이_닫힌_세션의_비활성화는_재인증_403_이고_아무것도_바뀌지_않는다() throws Exception {
		Object[] values = { null, "2026-11-22T00:00:00Z", Instant.now().minus(Duration.ofMinutes(16)),
				Instant.now().plus(Duration.ofHours(1)) };
		for (Object v : values) {
			Login admin = login("admin");
			UUID t = f.user(email(), "editor");
			f.reauthAt(admin.http(), v);
			reauthRequired(deactivate(admin.http(), t));
			assertThat(active(t)).as("%s", v).isTrue();
			assertThat(rows(admin.id())).as("%s", v).isEmpty();
			assertThat(admin.http().get("/api/admin").statusCode()).as("%s", v).isEqualTo(200);
		}
	}

	@Test
	void 창이_닫히면_자기_계정과_R6_거절보다_재인증이_먼저이고_기록하지_않는다() throws Exception {
		Login admin = login("admin");
		f.reauthAt(admin.http(), null);
		reauthRequired(deactivate(admin.http(), admin.id()));
		reauthRequired(activate(admin.http(), admin.id()));
		assertThat(rows(admin.id())).isEmpty();

		Login probe = login("probe_users");
		UUID holder = f.user(email(), "admin");
		f.reauthAt(probe.http(), null);
		reauthRequired(deactivate(probe.http(), holder));
		reauthRequired(activate(probe.http(), holder));
		assertThat(rows(probe.id())).isEmpty();
		assertThat(active(holder)).isTrue();
	}

	@Test
	void 재인증하면_같은_세션의_비활성화가_통과한다() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email(), "editor");
		f.reauthAt(admin.http(), null);
		reauthRequired(deactivate(admin.http(), t));
		assertThat(admin.http().post("/api/admin/reauth", json.writeValueAsString(java.util.Map.of("password", AuthFixture.PASSWORD)))
			.statusCode()).isEqualTo(204);
		assertThat(deactivate(admin.http(), t).statusCode()).isEqualTo(204);
		assertThat(active(t)).isFalse();
	}

	@Test
	void 상세는_관리자_200_운영자는_403() throws Exception {
		String email = email();
		UUID t = f.user(email, "editor");
		HttpResponse<String> r = login("admin").http().get("/api/admin/users/" + t);
		assertThat(r.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(r.body());
		assertThat(body.get("id").asString()).isEqualTo(t.toString());
		assertThat(body.get("email").asString()).isEqualTo(email);
		assertThat(body.get("is_active").asBoolean()).isTrue();
		assertThat(body.get("created_at").isString()).isTrue();
		assertThat(body.has("display_name")).isTrue();
		for (String hidden : List.of("password_hash", "sessions_valid_from", "active", "superuser")) {
			assertThat(body.has(hidden)).as(hidden).isFalse();
		}
		assertThat(body.get("roles")).singleElement().satisfies(role -> {
			assertThat(role.get("key").asString()).isEqualTo("editor");
			assertThat(role.get("is_superuser").asBoolean()).isFalse();
			assertThat(role.has("superuser")).isFalse();
			assertThat(role.get("id").isString()).isTrue();
			assertThat(role.get("name").isString()).isTrue();
		});
		List<String> keys = new ArrayList<>();
		body.get("assignable_roles").forEach(role -> keys.add(role.get("key").asString()));
		assertThat(keys).containsExactly("admin", "editor", "operator", "probe_users");
		assertThat(body.get("assignable_roles").get(0).get("is_superuser").asBoolean()).isTrue();

		assertThat(login("operator").http().get("/api/admin/users/" + t).statusCode()).isEqualTo(403);
	}

	@Test
	void 경로가_UUID_가_아니거나_없으면_404_Problem_Details() throws Exception {
		Login admin = login("admin");
		for (String id : List.of("not-a-uuid", "123", "%20", UUID.randomUUID().toString())) {
			refused(admin.http().get("/api/admin/users/" + id), 404,
					id.length() == 36 ? "사용자를 찾을 수 없습니다" : "찾을 수 없습니다");
			refused(deactivate(admin.http(), id), 404, id.length() == 36 ? "사용자를 찾을 수 없습니다" : "찾을 수 없습니다");
			refused(activate(admin.http(), id), 404, id.length() == 36 ? "사용자를 찾을 수 없습니다" : "찾을 수 없습니다");
		}
		assertThat(rows(admin.id())).isEmpty();
		// 질의 매개변수의 변환 실패는 그대로 400(A-601)
		assertThat(admin.http().get("/api/admin/oplog?page=abc").statusCode()).isEqualTo(400);
	}

	@Test
	void 자기_계정은_403_이고_self_행_대문자_경로도_같다() throws Exception {
		Login admin = login("admin");
		String upper = admin.id().toString().toUpperCase(Locale.ROOT);
		refused(deactivate(admin.http(), upper), 403, "자기 계정에는 이 작업을 할 수 없습니다");
		refused(activate(admin.http(), upper), 403, "자기 계정에는 이 작업을 할 수 없습니다");
		assertThat(active(admin.id())).isTrue();
		assertThat(rows(admin.id())).containsExactly("user.denied|" + admin.id() + "|user.deactivate self",
				"user.denied|" + admin.id() + "|user.activate self");
	}

	@Test
	void R6_비superuser_가_superuser_보유자를_비활성화하면_403_r6() throws Exception {
		Login probe = login("probe_users");
		UUID holder = f.user(email(), "admin");
		refused(deactivate(probe.http(), holder), 403, "권한이 없습니다");
		assertThat(active(holder)).isTrue();
		// 보유자가 아니면 같은 요청자도 통과한다
		UUID editor = f.user(email(), "editor");
		assertThat(deactivate(probe.http(), editor).statusCode()).isEqualTo(204);
		assertThat(rows(probe.id())).containsExactly("user.denied|" + holder + "|user.deactivate r6",
				"user.deactivate|" + editor + "|");
	}

	@Test
	void R6_은_활성화에도_걸린다_Q19() throws Exception {
		Login probe = login("probe_users");
		UUID holder = f.user(email(), "admin");
		f.sql("UPDATE users SET is_active = false WHERE id = ?", holder);
		refused(activate(probe.http(), holder), 403, "권한이 없습니다");
		assertThat(active(holder)).isFalse();
		assertThat(rows(probe.id())).containsExactly("user.denied|" + holder + "|user.activate r6");
		assertThat(activate(login("admin").http(), holder).statusCode()).isEqualTo(204);
		assertThat(active(holder)).isTrue();
	}

	@Test
	void 이미_비활성이어도_204_이고_기록한다() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email(), "editor");
		assertThat(deactivate(admin.http(), t).statusCode()).isEqualTo(204);
		assertThat(deactivate(admin.http(), t).statusCode()).isEqualTo(204);
		assertThat(rows(admin.id())).containsExactly("user.deactivate|" + t + "|", "user.deactivate|" + t + "|");
	}

	@Test
	void 권한이_없거나_익명이면_403_401_이고_기록하지_않는다() throws Exception {
		UUID t = f.user(email(), "editor");
		Login op = login("operator");
		assertThat(deactivate(op.http(), t).statusCode()).isEqualTo(403);
		assertThat(activate(op.http(), t).statusCode()).isEqualTo(403);
		assertThat(rows(op.id())).isEmpty();
		assertThat(deactivate(f.http(), t).statusCode()).isEqualTo(401);
		assertThat(active(t)).isTrue();
	}
}
