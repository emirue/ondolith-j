package dev.ondolithj.admin;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A-401 목록·A-402 생성(1-B2 스펙 4절). 목록은 DB 전체를 보므로 시험마다 고유 접두사를 q 로 걸러 센다.
 * operation_logs 는 비울 수 없으니 시험마다 새 계정으로 센다.
 */
class UsersApiTest {

	static final AtomicInteger SEQ = new AtomicInteger();

	static final JsonMapper json = JsonMapper.builder().build();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture();
		// 질의 수를 세려고 pg_stat_statements 를 올린다(OplogApiTest 와 같다)
		f.pg.withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements");
		f.start(false);
		f.sql("CREATE EXTENSION pg_stat_statements");
		// user.create 를 받은 비-superuser(Q21). 한 번만 넣는다 — key 는 UNIQUE 다
		f.sql("INSERT INTO roles (key, name) VALUES ('probe_create', '시험')");
		f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r, permissions p "
				+ "WHERE r.key = 'probe_create' AND p.key IN ('admin.access', 'user.create')");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static String email() {
		return "users" + SEQ.incrementAndGet() + "@ondolith.test";
	}

	record Login(UUID id, TestHttp http) {
	}

	static Login login(String... roles) throws Exception {
		String email = email();
		UUID id = f.user(email, roles);
		return new Login(id, f.loginAs(email));
	}

	/** n명을 한 문장으로 넣는다. 이메일 `<prefix><i>@ondolith.test`, created_at 은 1ms 씩 벌린다(i 가 클수록 최신). */
	static void fill(String prefix, int n) throws SQLException {
		f.sql("""
				INSERT INTO users (email, password_hash, created_at)
				SELECT ?::text || g || '@ondolith.test', 'not-a-hash', now() + g * interval '1 millisecond'
				FROM generate_series(1, ?) g""", prefix, n);
	}

	static String q(String q) {
		return "/api/admin/users?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
	}

	static JsonNode ok(HttpResponse<String> r) {
		assertThat(r.statusCode()).as("%s %s", r.uri(), r.body()).isEqualTo(200);
		return json.readTree(r.body());
	}

	static List<String> emails(JsonNode page) {
		List<String> out = new ArrayList<>();
		page.get("items").forEach(it -> out.add(it.get("email").asString()));
		return out;
	}

	static String body(String email, String displayName, String password) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("email", email);
		m.put("display_name", displayName);
		m.put("password", password);
		return json.writeValueAsString(m);
	}

	static HttpResponse<String> create(TestHttp http, String body) throws Exception {
		return http.post("/api/admin/users", body);
	}

	static void fieldError(HttpResponse<String> r, String field) {
		assertThat(r.statusCode()).as("%s", r.body()).isEqualTo(422);
		assertThat(json.readTree(r.body()).at("/errors/" + field).isString()).as("%s", r.body()).isTrue();
	}

	static long one(String sql, Object... params) throws SQLException {
		try (Connection c = f.db(); PreparedStatement s = c.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				s.setObject(i + 1, params[i]);
			}
			try (ResultSet r = s.executeQuery()) {
				r.next();
				return r.getLong(1);
			}
		}
	}

	/** 앱이 users 에 보낸 질의 수(이 시험의 통계 질의는 뺀다). */
	static long calls() throws SQLException {
		return one("SELECT coalesce(sum(calls), 0) FROM pg_stat_statements "
				+ "WHERE query ILIKE '%users%' AND query NOT ILIKE '%pg_stat_statements%'");
	}

	@Test
	void 목록은_운영자_200_편집자_403() throws Exception {
		assertThat(login("operator").http().get("/api/admin/users").statusCode()).isEqualTo(200);
		assertThat(login("editor").http().get("/api/admin/users").statusCode()).isEqualTo(403);
	}

	@Test
	void 쪽당_50_한_행_더_읽어_has_next() throws Exception {
		String p = "p" + SEQ.incrementAndGet() + "-";
		fill(p, 51);
		TestHttp http = login("operator").http();

		JsonNode p1 = ok(http.get(q(p)));
		assertThat(p1.get("items")).hasSize(50);
		assertThat(p1.get("has_next").asBoolean()).isTrue();
		assertThat(p1.get("page").asInt()).isEqualTo(1);
		assertThat(p1.get("page_size").asInt()).isEqualTo(50);
		assertThat(emails(p1).getFirst()).isEqualTo(p + "51@ondolith.test");
		List<OffsetDateTime> times = new ArrayList<>();
		p1.get("items").forEach(it -> times.add(OffsetDateTime.parse(it.get("created_at").asString())));
		assertThat(times).isSortedAccordingTo(Comparator.reverseOrder());

		JsonNode p2 = ok(http.get("/api/admin/users?page=2&q=" + p));
		assertThat(emails(p2)).containsExactly(p + "1@ondolith.test");
		assertThat(p2.get("has_next").asBoolean()).isFalse();
		assertThat(p2.get("page").asInt()).isEqualTo(2);
	}

	@Test
	void 검색은_이메일과_표시_이름의_일부이고_퍼센트와_밑줄과_역슬래시는_글자_그대로() throws Exception {
		int n = SEQ.incrementAndGet();
		String s = "s" + n;
		f.user(s + "a_b@ondolith.test");
		f.user(s + "axb@ondolith.test");
		UUID pct = f.user(s + "pct@ondolith.test");
		UUID zero = f.user(s + "zero@ondolith.test");
		UUID slash = f.user(s + "slash@ondolith.test");
		UUID plain = f.user(s + "plain@ondolith.test");
		UUID kim = f.user(s + "kim@ondolith.test");
		f.sql("UPDATE users SET display_name = ? WHERE id = ?", "100%-" + n, pct);
		f.sql("UPDATE users SET display_name = ? WHERE id = ?", "1000-" + n, zero);
		f.sql("UPDATE users SET display_name = ? WHERE id = ?", "a\\b-" + n, slash);
		f.sql("UPDATE users SET display_name = ? WHERE id = ?", "ab-" + n, plain);
		f.sql("UPDATE users SET display_name = ? WHERE id = ?", "KimDisplay-" + n, kim);
		TestHttp http = login("operator").http();

		// 이스케이프가 없으면 _ 는 한 글자, % 는 여러 글자, \ 는 다음 글자를 그대로 — 각각 옆 계정이 걸리거나 빠진다
		assertThat(emails(ok(http.get(q(s + "a_b"))))).containsExactly(s + "a_b@ondolith.test");
		assertThat(emails(ok(http.get(q("100%-" + n))))).containsExactly(s + "pct@ondolith.test");
		assertThat(emails(ok(http.get(q("a\\b-" + n))))).containsExactly(s + "slash@ondolith.test");
		// 표시 이름의 일부, 대소문자 무시. 앞뒤 공백은 뺀다
		assertThat(emails(ok(http.get(q("  imdISPLAY-" + n + " "))))).containsExactly(s + "kim@ondolith.test");
		assertThat(emails(ok(http.get(q("S" + n + "KIM@"))))).containsExactly(s + "kim@ondolith.test");
	}

	@Test
	void 공백만인_q_는_조건_없이_목록() throws Exception {
		TestHttp http = login("operator").http();
		JsonNode all = ok(http.get("/api/admin/users"));
		JsonNode blank = ok(http.get("/api/admin/users?q=%20%20"));
		assertThat(blank.get("items")).isNotEmpty().isEqualTo(all.get("items"));
		assertThat(ok(http.get("/api/admin/users?q=")).get("items")).isEqualTo(all.get("items"));
	}

	@Test
	void 쪽과_q_검사() throws Exception {
		TestHttp http = login("operator").http();
		fieldError(http.get("/api/admin/users?page=0"), "page");
		HttpResponse<String> abc = http.get("/api/admin/users?page=abc");
		assertThat(abc.statusCode()).isEqualTo(400);
		assertThat(abc.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		fieldError(http.get(q("a".repeat(201))), "q");
		// PostgreSQL text 는 NUL 을 받지 않는다 — 500 이 아니라 칸 오류(표시 이름과 같은 교훈)
		fieldError(http.get("/api/admin/users?q=a%00b"), "q");
		assertThat(http.get(q("a".repeat(200))).statusCode()).isEqualTo(200);
		assertThat(http.get(q("  " + "a".repeat(200) + "  ")).statusCode()).isEqualTo(200);
		assertThat(http.get("/api/admin/users?page=2147483647").statusCode()).isEqualTo(200);
	}

	@Test
	void 목록_질의는_행_수와_관계없이_같다() throws Exception {
		String p = "c" + SEQ.incrementAndGet() + "-";
		fill(p, 3);
		TestHttp http = login("operator").http();
		long before = calls();
		assertThat(ok(http.get(q(p))).get("items")).hasSize(3);
		long few = calls() - before;

		fill(p + "x", 51);
		before = calls();
		assertThat(ok(http.get(q(p))).get("items")).hasSize(50);
		long many = calls() - before;
		// 인증(ActorQuery) 하나 + 목록 하나. 역할 키는 같은 질의의 집계다
		assertThat(few).isEqualTo(2);
		assertThat(many).isEqualTo(2);
	}

	@Test
	void 목록에_비밀번호_해시와_기준_시각이_없다() throws Exception {
		String email = email();
		f.user(email, "editor");
		HttpResponse<String> r = login("operator").http().get(q(email));
		JsonNode page = ok(r);
		assertThat(emails(page)).containsExactly(email);
		List<String> names = new ArrayList<>();
		page.at("/items/0").properties().forEach(e -> names.add(e.getKey()));
		assertThat(names).containsExactlyInAnyOrder("id", "email", "display_name", "is_active", "roles", "created_at");
		assertThat(page.at("/items/0/roles").toString()).isEqualTo("[\"editor\"]");
		assertThat(r.body()).doesNotContain("password_hash").doesNotContain("sessions_valid_from").doesNotContain("$2a$");
	}

	@Test
	void 역할_없는_계정의_roles_는_빈_배열() throws Exception {
		String email = email();
		f.user(email);
		JsonNode page = ok(login("operator").http().get(q(email)));
		assertThat(page.at("/items/0/roles").isArray()).isTrue();
		assertThat(page.at("/items/0/roles")).isEmpty();
	}

	@Test
	void 생성은_관리자_201_과_user_create_행_운영자는_403() throws Exception {
		Login admin = login("admin");
		HttpResponse<String> r = create(admin.http(), body(email(), "새 사용자", PASSWORD));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		String id = json.readTree(r.body()).get("id").asString();
		assertThat(one("SELECT count(*) FROM users WHERE id = ?::uuid", id)).isEqualTo(1);
		assertThat(one("SELECT count(*) FROM operation_logs WHERE action = 'user.create' AND target_type = 'user' "
				+ "AND target_id = ? AND actor_user_id = ? AND summary = ''", id, admin.id())).isEqualTo(1);
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action <> 'auth.login'",
				admin.id())).isEqualTo(1);

		String other = email();
		Login op = login("operator");
		assertThat(create(op.http(), body(other, "새 사용자", PASSWORD)).statusCode()).isEqualTo(403);
		assertThat(one("SELECT count(*) FROM users WHERE email = ?", other)).isZero();
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action <> 'auth.login'",
				op.id())).isZero();
	}

	/** Q21: 1-E 전에는 만든 사람이 비밀번호를 안다 — 더 넓은 부여자가 그 계정에 역할을 주면 권한이 오른다. */
	@Test
	void 비superuser_가_user_create_를_받아도_계정을_만들면_403_이고_행이_늘지_않는다() throws Exception {
		Login probe = login("probe_create");
		String other = email();
		HttpResponse<String> r = create(probe.http(), body(other, "새 사용자", PASSWORD));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
		assertThat(json.readTree(r.body()).get("detail").asString()).isEqualTo("1-E 전에는 관리자만 계정을 만들 수 있습니다");
		assertThat(one("SELECT count(*) FROM users WHERE email = ?", other)).isZero();
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action = 'user.denied' "
				+ "AND target_type = 'user' AND target_id IS NULL AND summary = 'user.create superuser_only'", probe.id()))
			.isEqualTo(1);
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action <> 'auth.login'",
				probe.id())).isEqualTo(1);
	}

	@Test
	void 만든_계정은_인증됐고_역할이_없고_그_비밀번호로_로그인된다() throws Exception {
		String email = email();
		HttpResponse<String> r = create(login("admin").http(), body(email, " 새 이름 ", PASSWORD));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		assertThat(one("SELECT count(*) FROM users WHERE email = ? AND email_verified_at IS NOT NULL "
				+ "AND display_name = '새 이름' AND is_active", email)).isEqualTo(1);
		assertThat(one("SELECT count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id WHERE u.email = ?", email))
			.isZero();
		assertThat(f.http().post("/api/auth/login", AuthFixture.loginBody(email, PASSWORD)).statusCode()).isEqualTo(204);
	}

	@Test
	void 본문의_활성_상태와_역할과_기준_시각은_무시된다() throws Exception {
		String email = email();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("email", email);
		m.put("display_name", "새 사용자");
		m.put("password", PASSWORD);
		m.put("is_active", false);
		m.put("roles", List.of("admin"));
		m.put("sessions_valid_from", "2000-01-01T00:00:00Z");
		m.put("email_verified_at", null);
		HttpResponse<String> r = create(login("admin").http(), json.writeValueAsString(m));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		assertThat(one("SELECT count(*) FROM users WHERE email = ? AND is_active AND email_verified_at IS NOT NULL "
				+ "AND sessions_valid_from > now() - interval '1 hour'", email)).isEqualTo(1);
		assertThat(one("SELECT count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id WHERE u.email = ?", email))
			.isZero();
	}

	@Test
	void 이메일은_소문자로_저장하고_중복은_대소문자가_달라도_409() throws Exception {
		int n = SEQ.incrementAndGet();
		Login admin = login("admin");
		assertThat(create(admin.http(), body(" New" + n + "@Ondolith.test ", "새 사용자", PASSWORD)).statusCode())
			.isEqualTo(201);
		assertThat(one("SELECT count(*) FROM users WHERE email = ?", "new" + n + "@ondolith.test")).isEqualTo(1);

		HttpResponse<String> dup = create(admin.http(), body("NEW" + n + "@ondolith.test", "다른 사용자", PASSWORD));
		assertThat(dup.statusCode()).isEqualTo(409);
		assertThat(dup.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		assertThat(json.readTree(dup.body()).get("detail").asString()).isEqualTo("이미 사용 중인 이메일입니다");
		assertThat(dup.body()).doesNotContain("NEW" + n).doesNotContain("new" + n);
		// 409 는 남기지 않는다(스펙 8절)
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action = 'user.create'",
				admin.id())).isEqualTo(1);
	}

	@Test
	@Timeout(90)
	void 같은_이메일_동시_생성은_201_하나_409_하나() throws Exception {
		String email = email();
		TestHttp http = login("admin").http();
		String b = body(email, "새 사용자", PASSWORD);
		CompletableFuture<HttpResponse<String>> a = http.postAsync("/api/admin/users", b, Duration.ofSeconds(30));
		CompletableFuture<HttpResponse<String>> c = http.postAsync("/api/admin/users", b, Duration.ofSeconds(30));
		List<Integer> codes = new ArrayList<>(
				List.of(a.get(30, TimeUnit.SECONDS).statusCode(), c.get(30, TimeUnit.SECONDS).statusCode()));
		codes.sort(null);
		assertThat(codes).containsExactly(201, 409);
		assertThat(one("SELECT count(*) FROM users WHERE email = ?", email)).isEqualTo(1);
	}

	@Test
	void 표시_이름은_1자에서_50자() throws Exception {
		TestHttp http = login("admin").http();
		for (String bad : new String[] { null, "", "가".repeat(51), "   ", " ", "​", "a\u0000b", "a\nb" }) {
			String email = email();
			HttpResponse<String> r = create(http, body(email, bad, PASSWORD));
			fieldError(r, "display_name");
			assertThat(one("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
		}
		assertThat(create(http, body(email(), "가".repeat(50), PASSWORD)).statusCode()).isEqualTo(201);
	}

	@Test
	void 비밀번호는_10자_이상_72바이트_이하() throws Exception {
		TestHttp http = login("admin").http();
		for (String bad : new String[] { null, "a".repeat(9), "a".repeat(73), "가".repeat(25) }) {
			String email = email();
			HttpResponse<String> r = create(http, body(email, "새 사용자", bad));
			fieldError(r, "password");
			if (bad != null) {
				assertThat(r.body()).doesNotContain(bad);
			}
			assertThat(one("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
		}
	}

	@Test
	void 이메일은_설치와_같은_검사() throws Exception {
		TestHttp http = login("admin").http();
		for (String bad : new String[] { null, "", "not-an-email", "a".repeat(250) + "@ondolith.test" }) {
			fieldError(create(http, body(bad, "새 사용자", PASSWORD)), "email");
		}
	}

	@Test
	void 요청_레코드의_toString_은_비밀번호를_가린다() {
		assertThat(new UsersController.CreateUser("a@ondolith.test", "이름", "tostring-secret-7c1d").toString())
			.doesNotContain("tostring-secret-7c1d");
	}
}
