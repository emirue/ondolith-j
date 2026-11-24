package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A-403 역할 목록, A-404 역할 권한 부여·빼기, A-405 사용자 역할 부여·빼기(1-B2 스펙 6절). operation_logs 는 비울 수 없으니
 * 계정·시험 역할은 시험마다 새로 만든다.
 */
class RolesApiTest {

	static final String URN = "urn:ondolithj:problem:reauth-required";

	static final AtomicInteger SEQ = new AtomicInteger();

	static final JsonMapper json = JsonMapper.builder().build();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture();
		// 질의 수를 세려고 pg_stat_statements 를 올린다(UsersApiTest 와 같다)
		f.pg.withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements");
		f.start(false);
		f.sql("CREATE EXTENSION pg_stat_statements");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static String email() {
		return "roles" + SEQ.incrementAndGet() + "@ondolith.test";
	}

	record Login(UUID id, TestHttp http) {
	}

	static Login login(String... roles) throws Exception {
		String email = email();
		UUID id = f.user(email, roles);
		return new Login(id, f.loginAs(email));
	}

	static UUID role(String key) throws SQLException {
		try (Connection c = f.db(); PreparedStatement s = c.prepareStatement("SELECT id FROM roles WHERE key = ?")) {
			s.setString(1, key);
			try (ResultSet r = s.executeQuery()) {
				r.next();
				return r.getObject(1, UUID.class);
			}
		}
	}

	/** 시험 역할을 새로 만들고 권한 키들을 준다. 역할 키를 돌려준다. */
	static String testRole(String... perms) throws SQLException {
		String key = "probe_r" + SEQ.incrementAndGet();
		f.sql("INSERT INTO roles (key, name) VALUES (?, '시험')", key);
		for (String p : perms) {
			f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r, permissions p "
					+ "WHERE r.key = ? AND p.key = ?", key, p);
		}
		return key;
	}

	static HttpResponse<String> grantRole(TestHttp http, Object user, Object roleId) throws Exception {
		return http.post("/api/admin/users/" + user + "/roles", "{\"role_id\": \"" + roleId + "\"}");
	}

	static HttpResponse<String> revokeRole(TestHttp http, Object user, Object roleId) throws Exception {
		return http.post("/api/admin/users/" + user + "/roles/revoke", "{\"role_id\": \"" + roleId + "\"}");
	}

	static HttpResponse<String> grantPerm(TestHttp http, Object roleId, String permission) throws Exception {
		return http.post("/api/admin/roles/" + roleId + "/permissions", "{\"permission\": \"" + permission + "\"}");
	}

	static HttpResponse<String> revokePerm(TestHttp http, Object roleId, String permission) throws Exception {
		return http.post("/api/admin/roles/" + roleId + "/permissions/revoke", "{\"permission\": \"" + permission + "\"}");
	}

	static List<String> permKeys(UUID roleId) throws SQLException {
		return strings("SELECT p.key FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id "
				+ "WHERE rp.role_id = ? ORDER BY p.key", roleId);
	}

	static boolean active(UUID id) throws SQLException {
		return strings("SELECT is_active::text FROM users WHERE id = ?", id).equals(List.of("true"));
	}

	static void fieldError(HttpResponse<String> r, String field, String message) {
		assertThat(r.statusCode()).as("%s %s", r.uri(), r.body()).isEqualTo(422);
		assertThat(json.readTree(r.body()).at("/errors/" + field).asString()).as("%s", r.body()).isEqualTo(message);
	}

	/** 앱이 roles·permissions 가 든 문장을 보낸 횟수(이 시험의 통계 질의는 뺀다). */
	static long calls() throws SQLException {
		return Long.parseLong(strings("SELECT coalesce(sum(calls), 0) FROM pg_stat_statements "
				+ "WHERE (query ILIKE '%roles%' OR query ILIKE '%permissions%') AND query NOT ILIKE '%pg_stat_statements%'")
			.getFirst());
	}

	static JsonNode roles(TestHttp http) throws Exception {
		HttpResponse<String> r = http.get("/api/admin/roles");
		assertThat(r.statusCode()).as("%s", r.body()).isEqualTo(200);
		return json.readTree(r.body());
	}

	static JsonNode roleIn(JsonNode list, String key) {
		for (JsonNode r : list.get("roles")) {
			if (r.get("key").asString().equals(key)) {
				return r;
			}
		}
		throw new AssertionError("역할이 없다: " + key);
	}

	static List<String> roleKeys(UUID user) throws SQLException {
		return strings("SELECT r.key FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY r.key",
				user);
	}

	/** 요청자의 작업 로그 행 `action|target_id|summary`, 시각 순(로그인은 뺀다). */
	static List<String> rows(UUID actor) throws SQLException {
		return strings("SELECT action || '|' || coalesce(target_id, '') || '|' || summary FROM operation_logs "
				+ "WHERE actor_user_id = ? AND action <> 'auth.login' ORDER BY created_at, id", actor);
	}

	static List<String> strings(String sql, Object... params) throws SQLException {
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

	static void refused(HttpResponse<String> r, int status, String detail) {
		assertThat(r.statusCode()).as("%s %s", r.uri(), r.body()).isEqualTo(status);
		JsonNode body = json.readTree(r.body());
		assertThat(body.get("detail").asString()).isEqualTo(detail);
		assertThat(r.body()).doesNotContain(URN);
	}

	@Test
	void R1_자기에게_역할을_주면_403_이고_r1_행_하나() throws Exception {
		Login admin = login("admin");
		refused(grantRole(admin.http(), admin.id(), role("editor")), 403, "자기 계정의 역할은 변경할 수 없습니다");
		assertThat(rows(admin.id())).containsExactly("user.denied|" + admin.id() + "|user.grant_role editor r1");
		assertThat(roleKeys(admin.id())).containsExactly("admin");
	}

	// ---- A-405 ----

	@Test
	void R3_비superuser_가_admin_을_주면_403_r3() throws Exception {
		Login p = login(testRole("admin.access", "role.assign"));
		UUID t = f.user(email());
		refused(grantRole(p.http(), t, role("admin")), 403, "이 역할은 관리자만 부여할 수 있습니다");
		assertThat(rows(p.id())).containsExactly("user.denied|" + t + "|user.grant_role admin r3");
		assertThat(roleKeys(t)).isEmpty();
	}

	@Test
	void R2_자기에게_없는_권한이_든_operator_를_주면_403_r2() throws Exception {
		Login p = login(testRole("admin.access", "role.assign"));
		UUID t = f.user(email());
		refused(grantRole(p.http(), t, role("operator")), 403, "보유하지 않은 권한이 포함된 역할입니다");
		// editor 의 권한(admin.access)은 모두 가졌다
		assertThat(grantRole(p.http(), t, role("editor")).statusCode()).isEqualTo(204);
		assertThat(rows(p.id())).containsExactly("user.denied|" + t + "|user.grant_role operator r2",
				"user.grant_role|" + t + "|editor");
		assertThat(roleKeys(t)).containsExactly("editor");
	}

	@Test
	void anonymous_를_주면_422_줄_수_없는_역할_기록_없음() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email());
		fieldError(grantRole(admin.http(), t, role("anonymous")), "role_id", "줄 수 없는 역할입니다");
		fieldError(grantRole(admin.http(), t, role("member")), "role_id", "줄 수 없는 역할입니다");
		assertThat(rows(admin.id())).isEmpty();
		assertThat(roleKeys(t)).isEmpty();
	}

	@Test
	void 없는_사용자나_역할은_404_기록_없음() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email());
		refused(grantRole(admin.http(), UUID.randomUUID(), role("editor")), 404, "찾을 수 없습니다");
		refused(grantRole(admin.http(), t, UUID.randomUUID()), 404, "찾을 수 없습니다");
		refused(revokeRole(admin.http(), UUID.randomUUID(), role("editor")), 404, "찾을 수 없습니다");
		refused(revokeRole(admin.http(), t, UUID.randomUUID()), 404, "찾을 수 없습니다");
		assertThat(rows(admin.id())).isEmpty();
	}

	@Test
	void 이미_가진_역할을_다시_주면_204_행_하나_그대로() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email());
		assertThat(grantRole(admin.http(), t, role("editor")).statusCode()).isEqualTo(204);
		assertThat(grantRole(admin.http(), t, role("editor")).statusCode()).isEqualTo(204);
		assertThat(roleKeys(t)).containsExactly("editor");
		assertThat(rows(admin.id())).containsExactly("user.grant_role|" + t + "|editor", "user.grant_role|" + t + "|editor");
	}

	@Test
	void 갖지_않은_역할_빼기는_404() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email());
		refused(revokeRole(admin.http(), t, role("editor")), 404, "그 사용자는 이 역할을 갖고 있지 않습니다");
		// superuser 역할은 잠금 아래서 지우고, 0행이면 같은 404
		refused(revokeRole(admin.http(), t, role("admin")), 404, "그 사용자는 이 역할을 갖고 있지 않습니다");
		assertThat(rows(admin.id())).isEmpty();
	}

	@Test
	void 다른_관리자의_admin_은_뺄_수_있다() throws Exception {
		Login admin = login("admin");
		UUID other = f.user(email(), "admin");
		assertThat(revokeRole(admin.http(), other, role("admin")).statusCode()).isEqualTo(204);
		assertThat(roleKeys(other)).isEmpty();
		assertThat(rows(admin.id())).containsExactly("user.revoke_role|" + other + "|admin");
	}

	@Test
	void 자기_역할_빼기는_403_r1() throws Exception {
		Login admin = login("admin");
		refused(revokeRole(admin.http(), admin.id(), role("admin")), 403, "자기 계정의 역할은 변경할 수 없습니다");
		// 대문자 경로도 같은 사람이다
		refused(revokeRole(admin.http(), admin.id().toString().toUpperCase(java.util.Locale.ROOT), role("admin")), 403,
				"자기 계정의 역할은 변경할 수 없습니다");
		assertThat(rows(admin.id())).containsExactly("user.denied|" + admin.id() + "|user.revoke_role admin r1",
				"user.denied|" + admin.id() + "|user.revoke_role admin r1");
		assertThat(roleKeys(admin.id())).containsExactly("admin");
	}

	@Test
	void 주고_나면_다음_요청부터_그_사람의_can_이_바뀐다() throws Exception {
		Login admin = login("admin");
		Login t = login();
		assertThat(json.readTree(t.http().get("/api/me").body()).at("/can/user.view").asBoolean(true)).isFalse();
		assertThat(grantRole(admin.http(), t.id(), role("operator")).statusCode()).isEqualTo(204);
		assertThat(json.readTree(t.http().get("/api/me").body()).at("/can/user.view").asBoolean(false)).isTrue();
		assertThat(revokeRole(admin.http(), t.id(), role("operator")).statusCode()).isEqualTo(204);
		assertThat(json.readTree(t.http().get("/api/me").body()).at("/can/user.view").asBoolean(true)).isFalse();
	}

	@Test
	void 비활성_계정에_editor_를_주고_빼면_각각_204_와_행_is_active_는_false_Q18() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email());
		f.sql("UPDATE users SET is_active = false WHERE id = ?", t);
		assertThat(grantRole(admin.http(), t, role("editor")).statusCode()).isEqualTo(204);
		assertThat(roleKeys(t)).containsExactly("editor");
		assertThat(revokeRole(admin.http(), t, role("editor")).statusCode()).isEqualTo(204);
		assertThat(roleKeys(t)).isEmpty();
		assertThat(active(t)).isFalse();
		assertThat(rows(admin.id())).containsExactly("user.grant_role|" + t + "|editor", "user.revoke_role|" + t + "|editor");
	}

	@Test
	void 비superuser_가_비활성_계정에_admin_을_주면_403_r3() throws Exception {
		Login p = login(testRole("admin.access", "role.assign"));
		UUID t = f.user(email());
		f.sql("UPDATE users SET is_active = false WHERE id = ?", t);
		refused(grantRole(p.http(), t, role("admin")), 403, "이 역할은 관리자만 부여할 수 있습니다");
		assertThat(rows(p.id())).containsExactly("user.denied|" + t + "|user.grant_role admin r3");
		assertThat(roleKeys(t)).isEmpty();
	}

	@Test
	void 본문이_비거나_role_id_가_null_이면_422_필수_기록_없음() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email());
		for (String body : List.of("{}", "{\"role_id\": null}")) {
			fieldError(admin.http().post("/api/admin/users/" + t + "/roles", body), "role_id", "필수입니다");
			fieldError(admin.http().post("/api/admin/users/" + t + "/roles/revoke", body), "role_id", "필수입니다");
		}
		assertThat(rows(admin.id())).isEmpty();
	}

	@Test
	void role_id_가_UUID_가_아니면_400() throws Exception {
		Login admin = login("admin");
		UUID t = f.user(email());
		assertThat(grantRole(admin.http(), t, "editor").statusCode()).isEqualTo(400);
		assertThat(revokeRole(admin.http(), t, "editor").statusCode()).isEqualTo(400);
		assertThat(rows(admin.id())).isEmpty();
	}

	// ---- A-404 ----

	@Test
	void R5_admin_역할의_권한은_403_r5() throws Exception {
		Login admin = login("admin");
		UUID r = role("admin");
		refused(grantPerm(admin.http(), r, "log.view"), 403, "이 역할의 권한은 변경할 수 없습니다");
		refused(revokePerm(admin.http(), r, "log.view"), 403, "이 역할의 권한은 변경할 수 없습니다");
		assertThat(rows(admin.id())).containsExactly("role.denied|" + r + "|role.grant_permission log.view r5",
				"role.denied|" + r + "|role.revoke_permission log.view r5");
		assertThat(permKeys(r)).isEmpty();
	}

	@Test
	void R9_member_에_user_view_는_422_r9_행() throws Exception {
		Login admin = login("admin");
		UUID r = role("member");
		fieldError(grantPerm(admin.http(), r, "user.view"), "permission", "모두가 갖는 역할에는 방문자 권한만 줄 수 있습니다");
		assertThat(rows(admin.id())).containsExactly("role.denied|" + r + "|role.grant_permission user.view r9");
		assertThat(permKeys(r)).isEmpty();
	}

	@Test
	void 이미_들어간_member_의_user_view_는_뺄_수_있다() throws Exception {
		Login admin = login("admin");
		UUID r = role("member");
		f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT ?, id FROM permissions WHERE key = 'user.view'", r);
		assertThat(revokePerm(admin.http(), r, "user.view").statusCode()).isEqualTo(204);
		assertThat(permKeys(r)).isEmpty();
		assertThat(rows(admin.id())).containsExactly("role.revoke_permission|" + r + "|user.view");
	}

	@Test
	void R4_자기에게_없는_user_update_를_주면_403_r4() throws Exception {
		Login p = login(testRole("admin.access", "role.manage"));
		UUID r = role("editor");
		refused(grantPerm(p.http(), r, "user.update"), 403, "보유하지 않은 권한은 부여할 수 없습니다");
		refused(revokePerm(p.http(), r, "user.update"), 403, "보유하지 않은 권한은 부여할 수 없습니다");
		// 가진 권한은 준다(이미 있으면 그대로)
		assertThat(grantPerm(p.http(), r, "admin.access").statusCode()).isEqualTo(204);
		assertThat(rows(p.id())).containsExactly("role.denied|" + r + "|role.grant_permission user.update r4",
				"role.denied|" + r + "|role.revoke_permission user.update r4", "role.grant_permission|" + r + "|admin.access");
		assertThat(permKeys(r)).containsExactly("admin.access");
	}

	@Test
	void 모르는_키는_422_unknown_permission_행에_보낸_키_문자열이_없다() throws Exception {
		Login admin = login("admin");
		UUID r = role("editor");
		fieldError(grantPerm(admin.http(), r, "ghost.key-9f2a"), "permission", "알 수 없는 권한입니다");
		fieldError(revokePerm(admin.http(), r, "ghost.key-9f2a"), "permission", "알 수 없는 권한입니다");
		assertThat(rows(admin.id())).containsExactly("role.denied|" + r + "|role.grant_permission unknown_permission",
				"role.denied|" + r + "|role.revoke_permission unknown_permission");
		assertThat(strings("SELECT row_to_json(l)::text FROM operation_logs l WHERE actor_user_id = ?", admin.id()))
			.isNotEmpty().allSatisfy(row -> assertThat(row).doesNotContain("ghost"));
	}

	@Test
	void 없는_부여_빼기는_404() throws Exception {
		Login admin = login("admin");
		refused(revokePerm(admin.http(), role("editor"), "log.view"), 404, "그런 부여가 없습니다");
		refused(grantPerm(admin.http(), UUID.randomUUID(), "log.view"), 404, "찾을 수 없습니다");
		refused(revokePerm(admin.http(), UUID.randomUUID(), "log.view"), 404, "찾을 수 없습니다");
		assertThat(rows(admin.id())).isEmpty();
	}

	@Test
	void 권한이_null_이면_422_필수_기록_없음() throws Exception {
		Login admin = login("admin");
		UUID r = role("editor");
		for (String body : List.of("{}", "{\"permission\": null}")) {
			fieldError(admin.http().post("/api/admin/roles/" + r + "/permissions", body), "permission", "필수입니다");
			fieldError(admin.http().post("/api/admin/roles/" + r + "/permissions/revoke", body), "permission", "필수입니다");
		}
		assertThat(rows(admin.id())).isEmpty();
	}

	@Test
	void editor_에_log_view_를_주고_빼면_204_와_행_둘() throws Exception {
		Login admin = login("admin");
		UUID r = role("editor");
		assertThat(grantPerm(admin.http(), r, "log.view").statusCode()).isEqualTo(204);
		assertThat(permKeys(r)).containsExactly("admin.access", "log.view");
		assertThat(revokePerm(admin.http(), r, "log.view").statusCode()).isEqualTo(204);
		assertThat(permKeys(r)).containsExactly("admin.access");
		assertThat(rows(admin.id())).containsExactly("role.grant_permission|" + r + "|log.view",
				"role.revoke_permission|" + r + "|log.view");
	}

	// ---- A-403 ----

	@Test
	void 역할_목록은_운영자_200_편집자_403() throws Exception {
		assertThat(login("operator").http().get("/api/admin/roles").statusCode()).isEqualTo(200);
		assertThat(login("editor").http().get("/api/admin/roles").statusCode()).isEqualTo(403);
	}

	@Test
	void member_에_넣어_둔_관리_권한은_effective_false() throws Exception {
		UUID member = role("member");
		f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT ?, id FROM permissions WHERE key = 'log.view'",
				member);
		String other = testRole("admin.access", "log.view");
		try {
			JsonNode list = roles(login("operator").http());
			assertThat(roleIn(list, "member").get("permissions").toString())
				.isEqualTo("[{\"key\":\"log.view\",\"effective\":false}]");
			assertThat(roleIn(list, other).get("permissions").toString())
				.isEqualTo("[{\"key\":\"admin.access\",\"effective\":true},{\"key\":\"log.view\",\"effective\":true}]");
		}
		finally {
			f.sql("DELETE FROM role_permissions WHERE role_id = ?", member);
		}
	}

	@Test
	void admin_은_permissions_가_비고_is_superuser_true() throws Exception {
		JsonNode list = roles(login("operator").http());
		JsonNode admin = roleIn(list, "admin");
		assertThat(admin.get("is_superuser").asBoolean()).isTrue();
		assertThat(admin.get("permissions")).isEmpty();
		assertThat(admin.get("is_builtin").asBoolean()).isTrue();
		assertThat(admin.get("is_assignable").asBoolean()).isTrue();
		assertThat(admin.get("name").asString()).isEqualTo("관리자");
		assertThat(admin.get("description").asString()).isNotEmpty();
		assertThat(admin.get("id").asString()).isEqualTo(role("admin").toString());
		JsonNode anonymous = roleIn(list, "anonymous");
		assertThat(anonymous.get("is_superuser").asBoolean()).isFalse();
		assertThat(anonymous.get("is_assignable").asBoolean()).isFalse();
		List<String> keys = new ArrayList<>();
		list.get("roles").forEach(r -> keys.add(r.get("key").asString()));
		assertThat(keys).isSorted().contains("admin", "anonymous", "editor", "member", "operator");
		// A-404 가 고를 권한 목록은 코드의 권한 전부, 키 순
		List<String> perms = new ArrayList<>();
		list.get("permissions").forEach(p -> perms.add(p.get("key").asString()));
		assertThat(perms).isSorted().hasSize(dev.ondolithj.auth.Permission.values().length);
		assertThat(list.at("/permissions/0/description").asString()).isNotEmpty();
	}

	@Test
	void 질의_수는_역할과_부여_수와_관계없이_같다() throws Exception {
		TestHttp http = login("operator").http();
		long before = calls();
		roles(http);
		long few = calls() - before;
		testRole("admin.access", "log.view");
		before = calls();
		roles(http);
		long many = calls() - before;
		// 인증(ActorQuery) 하나 + 목록 둘(역할+부여 집계, 권한 목록)
		assertThat(few).isEqualTo(3);
		assertThat(many).isEqualTo(3);
	}

	@Test
	void 생성_재인증_부여_흐름_뒤_작업_로그에_비밀번호_세션_토큰이_없다() throws Exception {
		Login admin = login("admin");
		HttpResponse<String> created = admin.http().post("/api/admin/users", json.writeValueAsString(java.util.Map.of(
				"email", email(), "display_name", "새 사용자", "password", "created-pass-5e8b")));
		assertThat(created.statusCode()).isEqualTo(201);
		UUID t = UUID.fromString(json.readTree(created.body()).get("id").asString());
		f.reauthAt(admin.http(), null);
		assertThat(admin.http().post("/api/admin/reauth", "{\"password\": \"" + AuthFixture.PASSWORD + "\"}").statusCode())
			.isEqualTo(204);
		assertThat(grantRole(admin.http(), t, role("editor")).statusCode()).isEqualTo(204);
		String cookie = admin.http().cookie("SESSION");
		List<String> secrets = List.of("created-pass-5e8b", AuthFixture.PASSWORD, cookie,
				new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8), admin.http().xsrf());
		List<String> logged = strings("SELECT row_to_json(l)::text FROM operation_logs l "
				+ "WHERE actor_user_id IN (?, ?) OR target_id IN (?, ?)", admin.id(), t, admin.id().toString(), t.toString());
		assertThat(logged).hasSizeGreaterThanOrEqualTo(3); // 로그인·생성·부여
		for (String row : logged) {
			for (String secret : secrets) {
				assertThat(secret).isNotBlank();
				assertThat(row).doesNotContain(secret);
			}
		}
	}

	// ---- 검사 순서(스펙 6절) ----

	@Test
	void 창이_닫히면_입력_오류와_R_거절보다_재인증이_먼저이고_기록하지_않는다() throws Exception {
		Login admin = login("admin");
		f.reauthAt(admin.http(), null);
		TestHttp h = admin.http();
		List<HttpResponse<String>> rs = List.of(grantRole(h, admin.id(), role("editor")),
				revokeRole(h, admin.id(), role("admin")), h.post("/api/admin/users/" + admin.id() + "/roles", "{}"),
				grantPerm(h, role("editor"), "ghost.key-9f2a"), grantPerm(h, role("admin"), "log.view"),
				h.post("/api/admin/roles/" + role("editor") + "/permissions/revoke", "{}"));
		for (HttpResponse<String> r : rs) {
			assertThat(r.statusCode()).as("%s", r.uri()).isEqualTo(403);
			assertThat(r.body()).as("%s", r.uri()).contains(URN);
		}
		assertThat(rows(admin.id())).isEmpty();
	}

	@Test
	void A_405_는_줄_수_없는_역할_R1_R3_순서() throws Exception {
		Login p = login(testRole("admin.access", "role.assign"));
		// 줄 수 없는 역할 422 가 R1 보다 먼저(남기지 않음)
		fieldError(grantRole(p.http(), p.id(), role("anonymous")), "role_id", "줄 수 없는 역할입니다");
		// R1 이 R3 보다 먼저
		refused(grantRole(p.http(), p.id(), role("admin")), 403, "자기 계정의 역할은 변경할 수 없습니다");
		assertThat(rows(p.id())).containsExactly("user.denied|" + p.id() + "|user.grant_role admin r1");
	}

	@Test
	void A_404_는_모르는_키_R5_R9_R4_순서() throws Exception {
		Login p = login(testRole("admin.access", "role.manage"));
		UUID admin = role("admin");
		UUID member = role("member");
		// 모르는 키가 R5 보다, R5 가 R4 보다, R9 가 R4 보다 먼저
		fieldError(grantPerm(p.http(), admin, "ghost.key-9f2a"), "permission", "알 수 없는 권한입니다");
		refused(grantPerm(p.http(), admin, "user.update"), 403, "이 역할의 권한은 변경할 수 없습니다");
		fieldError(grantPerm(p.http(), member, "user.update"), "permission", "모두가 갖는 역할에는 방문자 권한만 줄 수 있습니다");
		assertThat(rows(p.id())).containsExactly("role.denied|" + admin + "|role.grant_permission unknown_permission",
				"role.denied|" + admin + "|role.grant_permission user.update r5",
				"role.denied|" + member + "|role.grant_permission user.update r9");
	}
}
