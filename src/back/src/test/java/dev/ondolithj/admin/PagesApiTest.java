package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import dev.ondolithj.shared.RichText;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A-302 작성, A-303 발행·발행 취소, P-202 공개 조회(1-C1 스펙 5·6·7절, 13.4). DB 를 시험끼리 나눠 쓰므로 슬러그는 SEQ 로 짓고,
 * operation_logs 는 비울 수 없으니 시험마다 새 계정으로 센다.
 */
class PagesApiTest {

	static final AtomicInteger SEQ = new AtomicInteger();

	static final JsonMapper json = JsonMapper.builder().build();

	static final String NOT_FOUND = "페이지를 찾을 수 없습니다";

	static final String U = "00000000-0000-4000-8000-000000000001";

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

	record Login(UUID id, TestHttp http) {
	}

	static Login login(String... roles) throws Exception {
		String email = "pages" + SEQ.incrementAndGet() + "@ondolith.test";
		UUID id = f.user(email, roles);
		return new Login(id, f.loginAs(email));
	}

	static String slug() {
		return "pg-" + SEQ.incrementAndGet();
	}

	/** 시험 역할을 새로 만들고 권한 키들을 준다. 역할 키를 돌려준다. */
	static String role(String... perms) throws SQLException {
		String key = "probe_pg" + SEQ.incrementAndGet();
		f.sql("INSERT INTO roles (key, name) VALUES (?, '시험')", key);
		for (String p : perms) {
			f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r, permissions p "
					+ "WHERE r.key = ? AND p.key = ?", key, p);
		}
		return key;
	}

	static Map<String, Object> fields(Object slug, Object title, Object bodyHtml) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("slug", slug);
		m.put("title", title);
		m.put("body_html", bodyHtml);
		return m;
	}

	static String body(Object slug, Object title, Object bodyHtml) {
		return json.writeValueAsString(fields(slug, title, bodyHtml));
	}

	static HttpResponse<String> post(TestHttp http, String slug, String bodyHtml) throws Exception {
		return http.post("/api/admin/pages", body(slug, "제목", bodyHtml));
	}

	static UUID create(TestHttp http, String slug, String bodyHtml) throws Exception {
		HttpResponse<String> r = post(http, slug, bodyHtml);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		return UUID.fromString(json.readTree(r.body()).get("id").asString());
	}

	static void publish(TestHttp http, UUID id) throws Exception {
		HttpResponse<String> r = http.post("/api/admin/pages/" + id + "/publish", "");
		assertThat(r.statusCode()).as(r.body()).isEqualTo(204);
	}

	static HttpResponse<String> unpublish(TestHttp http, UUID id) throws Exception {
		return http.post("/api/admin/pages/" + id + "/unpublish", "");
	}

	static void fieldError(HttpResponse<String> r, String field) {
		assertThat(r.statusCode()).as("%s", r.body()).isEqualTo(422);
		assertThat(json.readTree(r.body()).at("/errors/" + field).isString()).as("%s", r.body()).isTrue();
	}

	static void fieldError(HttpResponse<String> r, String field, String message) {
		fieldError(r, field);
		assertThat(json.readTree(r.body()).at("/errors/" + field).asString()).isEqualTo(message);
	}

	static void refused(HttpResponse<String> r, int status, String detail) {
		assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
		assertThat(r.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		assertThat(json.readTree(r.body()).get("detail").asString()).isEqualTo(detail);
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

	static String text(String sql, Object... params) throws SQLException {
		try (Connection c = f.db(); PreparedStatement s = c.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				s.setObject(i + 1, params[i]);
			}
			try (ResultSet r = s.executeQuery()) {
				return r.next() ? r.getString(1) : null;
			}
		}
	}

	static long rows(String slug) throws SQLException {
		return one("SELECT count(*) FROM pages WHERE slug = ?", slug);
	}

	static String stored(UUID id) throws SQLException {
		return text("SELECT body_html FROM pages WHERE id = ?", id);
	}

	/** 그 계정의 작업 로그(로그인 빼고). */
	static long logs(UUID actor) throws SQLException {
		return one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action <> 'auth.login'", actor);
	}

	/** 앱이 보낸 질의 중 like 에 맞는 것의 수(이 시험의 통계 질의는 뺀다). */
	static long queries(String like) throws SQLException {
		return one("SELECT coalesce(sum(calls), 0) FROM pg_stat_statements WHERE query ILIKE ? "
				+ "AND query NOT ILIKE '%pg_stat_statements%'", like);
	}

	static HttpResponse<String> view(TestHttp http, String slug) throws Exception {
		return http.get("/api/pages/" + slug);
	}

	// ---- 첫 Red ----

	@Test
	void 작성하고_발행하면_익명이_정화된_본문을_받는다() throws Exception {
		TestHttp editor = login("editor").http();
		String s = slug();
		UUID id = create(editor, s, "<p><strong>a</strong></p><script>alert(1)</script>");
		publish(editor, id);
		HttpResponse<String> r = view(f.http(), s);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		JsonNode page = json.readTree(r.body());
		assertThat(page.get("body_html").asString()).isEqualTo("<p><strong>a</strong></p>");
		assertThat(page.get("title").asString()).isEqualTo("제목");
		assertThat(page.get("slug").asString()).isEqualTo(s);
		assertThat(page.propertyNames()).containsExactlyInAnyOrder("slug", "title", "body_html");
	}

	// ---- 공개 조회 ----

	@Test
	void 초안은_익명_로그인_모두_404() throws Exception {
		TestHttp editor = login("editor").http();
		String s = slug();
		create(editor, s, "<p>초안</p>");
		for (TestHttp http : List.of(f.http(), editor)) {
			HttpResponse<String> r = view(http, s);
			refused(r, 404, NOT_FOUND);
			// 초안의 내용이 새지 않는다(instance 의 요청 경로는 모든 Problem Details 의 관례 — 요청자가 보낸 값이다)
			assertThat(r.body()).doesNotContain("초안").doesNotContain("제목");
		}
	}

	@Test
	void 형식이_틀린_슬러그는_404_이고_질의가_0() throws Exception {
		TestHttp anon = f.http();
		for (String bad : List.of("Foo", "a.b", "-a", "a_b", "a".repeat(101))) {
			long before = queries("%pages%");
			refused(view(anon, bad), 404, NOT_FOUND);
			assertThat(queries("%pages%") - before).as(bad).isZero();
		}
	}

	@Test
	void 발행_취소하면_404() throws Exception {
		TestHttp editor = login("editor").http();
		String s = slug();
		UUID id = create(editor, s, "<p>a</p>");
		publish(editor, id);
		assertThat(view(f.http(), s).statusCode()).isEqualTo(200);
		assertThat(unpublish(editor, id).statusCode()).isEqualTo(204);
		refused(view(f.http(), s), 404, NOT_FOUND);
	}

	@Test
	void 쿠키가_있어도_없어도_본문이_바이트까지_같다() throws Exception {
		TestHttp editor = login("editor").http();
		TestHttp member = login().http();
		String s = slug();
		publish(editor, create(editor, s, "<h2>t</h2><p>a <a href=\"/about\">b</a></p>"));
		HttpResponse<String> anon = view(f.http(), s);
		assertThat(anon.statusCode()).isEqualTo(200);
		for (TestHttp http : List.of(editor, member)) {
			HttpResponse<String> r = view(http, s);
			assertThat(r.statusCode()).isEqualTo(200);
			assertThat(r.body()).isEqualTo(anon.body());
		}
	}

	@Test
	void Cache_Control_에_no_store() throws Exception {
		TestHttp editor = login("editor").http();
		String s = slug();
		publish(editor, create(editor, s, "<p>a</p>"));
		for (HttpResponse<String> r : List.of(view(f.http(), s), view(editor, s), view(f.http(), slug()))) {
			assertThat(r.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
		}
	}

	@Test
	void 익명_요청의_질의는_하나() throws Exception {
		TestHttp editor = login("editor").http();
		String s = slug();
		publish(editor, create(editor, s, "<p>a</p>"));
		TestHttp anon = f.http();
		long before = queries("%pages%");
		assertThat(view(anon, s).statusCode()).isEqualTo(200);
		assertThat(queries("%pages%") - before).isEqualTo(1);
		before = queries("%pages%");
		assertThat(view(anon, slug()).statusCode()).isEqualTo(404);
		assertThat(queries("%pages%") - before).isEqualTo(1);
	}

	// ---- 권한 ----

	@Test
	void 편집자는_작성_발행_발행취소가_된다() throws Exception {
		Login editor = login("editor");
		UUID id = create(editor.http(), slug(), "<p>a</p>");
		publish(editor.http(), id);
		assertThat(text("SELECT status FROM pages WHERE id = ?", id)).isEqualTo("published");
		assertThat(unpublish(editor.http(), id).statusCode()).isEqualTo(204);
		assertThat(text("SELECT status FROM pages WHERE id = ?", id)).isEqualTo("draft");
		for (String action : List.of("page.create", "page.publish", "page.unpublish")) {
			assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action = ? "
					+ "AND target_type = 'page' AND target_id = ? AND summary = ''", editor.id(), action, id.toString()))
				.as(action).isEqualTo(1);
		}
		assertThat(logs(editor.id())).isEqualTo(3);
	}

	@Test
	void 운영자도_작성_발행이_된다() throws Exception {
		TestHttp op = login("operator").http();
		publish(op, create(op, slug(), "<p>a</p>"));
	}

	@Test
	void page_update_만_가진_역할은_작성_발행_발행취소가_403이고_기록이_없다() throws Exception {
		Login probe = login(role("admin.access", "page.update"));
		TestHttp editor = login("editor").http();
		UUID draft = create(editor, slug(), "<p>a</p>");
		UUID published = create(editor, slug(), "<p>b</p>");
		publish(editor, published);
		String s = slug();
		assertThat(post(probe.http(), s, "<p>a</p>").statusCode()).isEqualTo(403);
		assertThat(rows(s)).isZero();
		assertThat(probe.http().post("/api/admin/pages/" + draft + "/publish", "").statusCode()).isEqualTo(403);
		assertThat(unpublish(probe.http(), published).statusCode()).isEqualTo(403);
		assertThat(text("SELECT status FROM pages WHERE id = ?", draft)).isEqualTo("draft");
		assertThat(text("SELECT status FROM pages WHERE id = ?", published)).isEqualTo("published");
		assertThat(logs(probe.id())).isZero();
	}

	@Test
	void page_create_만_있고_admin_access_가_없으면_403() throws Exception {
		Login probe = login(role("page.create", "page.publish"));
		String s = slug();
		assertThat(post(probe.http(), s, "<p>a</p>").statusCode()).isEqualTo(403);
		assertThat(rows(s)).isZero();
	}

	@Test
	void 익명은_401() throws Exception {
		TestHttp editor = login("editor").http();
		UUID id = create(editor, slug(), "<p>a</p>");
		TestHttp anon = f.http();
		String s = slug();
		assertThat(post(anon, s, "<p>a</p>").statusCode()).isEqualTo(401);
		assertThat(anon.post("/api/admin/pages/" + id + "/publish", "").statusCode()).isEqualTo(401);
		assertThat(unpublish(anon, id).statusCode()).isEqualTo(401);
		assertThat(rows(s)).isZero();
		assertThat(text("SELECT status FROM pages WHERE id = ?", id)).isEqualTo("draft");
	}

	@Test
	void CSRF_없으면_403() throws Exception {
		Login editor = login("editor");
		UUID id = create(editor.http(), slug(), "<p>a</p>");
		String s = slug();
		assertThat(editor.http().postRaw("/api/admin/pages", body(s, "제목", "<p>a</p>")).statusCode()).isEqualTo(403);
		assertThat(editor.http().postRaw("/api/admin/pages/" + id + "/publish", "").statusCode()).isEqualTo(403);
		assertThat(rows(s)).isZero();
		assertThat(text("SELECT status FROM pages WHERE id = ?", id)).isEqualTo("draft");
		assertThat(logs(editor.id())).isEqualTo(1);
	}

	// ---- 작성 칸 ----

	@Test
	void status_가_실리면_422_이고_다른_모르는_칸은_무시() throws Exception {
		TestHttp editor = login("editor").http();
		for (Object status : new Object[] { "published", "draft", true, Map.of() }) {
			String s = slug();
			Map<String, Object> m = fields(s, "제목", "<p>a</p>");
			m.put("status", status);
			fieldError(editor.post("/api/admin/pages", json.writeValueAsString(m)), "status", "발행 상태는 발행 단추로 바꿉니다");
			assertThat(rows(s)).isZero();
		}
		// null 은 실리지 않은 것과 같다. 작성자·시각 같은 다른 칸은 읽지 않는다
		String s = slug();
		Map<String, Object> m = fields(s, "제목", "<p>a</p>");
		m.put("status", null);
		m.put("author", "x");
		m.put("created_at", "2000-01-01T00:00:00Z");
		m.put("id", U);
		HttpResponse<String> r = editor.post("/api/admin/pages", json.writeValueAsString(m));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		assertThat(json.readTree(r.body()).get("id").asString()).isNotEqualTo(U);
		assertThat(one("SELECT count(*) FROM pages WHERE slug = ? AND status = 'draft' "
				+ "AND created_at > now() - interval '1 hour'", s)).isEqualTo(1);
	}

	@Test
	void 칸_오류는_한_번에_모은다() throws Exception {
		HttpResponse<String> r = login("editor").http().post("/api/admin/pages", "{\"status\": \"published\"}");
		assertThat(r.statusCode()).isEqualTo(422);
		assertThat(json.readTree(r.body()).get("errors").propertyNames()).containsExactlyInAnyOrder("slug", "title",
				"body_html", "status");
	}

	@Test
	void 슬러그_검사() throws Exception {
		TestHttp editor = login("editor").http();
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("admin", "사용할 수 없는 주소입니다");
		cases.put("api", "사용할 수 없는 주소입니다");
		cases.put("assets", "사용할 수 없는 주소입니다");
		cases.put("error", "사용할 수 없는 주소입니다");
		cases.put("login", "사용할 수 없는 주소입니다");
		for (String bad : List.of("Foo", "-a", "a/b", "a.b", "a_b", "한글", "about\n", " about", "about ")) {
			cases.put(bad, "소문자·숫자·하이픈만 쓸 수 있습니다");
		}
		cases.put("a".repeat(101), "100자 이하");
		cases.put("", "필수입니다");
		for (Map.Entry<String, String> c : cases.entrySet()) {
			HttpResponse<String> r = post(editor, c.getKey(), "<p>a</p>");
			fieldError(r, "slug", c.getValue());
			assertThat(rows(c.getKey())).as(c.getKey()).isZero();
		}
		fieldError(editor.post("/api/admin/pages", body(null, "제목", "<p>a</p>")), "slug", "필수입니다");
		String hundred = "b" + SEQ.incrementAndGet() + "-";
		hundred = hundred + "c".repeat(100 - hundred.length());
		create(editor, hundred, "<p>a</p>");
		assertThat(rows(hundred)).isEqualTo(1);
	}

	@Test
	void 제목_검사() throws Exception {
		TestHttp editor = login("editor").http();
		for (Object bad : new Object[] { null, "", "   ", " ", "​", "a\u0000b", "a\nb", "가".repeat(201) }) {
			String s = slug();
			fieldError(editor.post("/api/admin/pages", body(s, bad, "<p>a</p>")), "title");
			assertThat(rows(s)).isZero();
		}
		String s = slug();
		HttpResponse<String> r = editor.post("/api/admin/pages", body(s, "  " + "가".repeat(200) + " ", "<p>a</p>"));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		assertThat(text("SELECT title FROM pages WHERE slug = ?", s)).isEqualTo("가".repeat(200));
	}

	/** 스펙 Review Focus 4: 전역 Jackson 설정은 바꾸지 않는다. 실제 동작을 고정한다. */
	@Test
	void body_html_이_문자열이_아니면_500_이_아니다() throws Exception {
		TestHttp editor = login("editor").http();
		for (String raw : List.of("[]", "{}")) {
			String s = slug();
			HttpResponse<String> r = editor.post("/api/admin/pages",
					"{\"slug\": \"" + s + "\", \"title\": \"제목\", \"body_html\": " + raw + "}");
			assertThat(r.statusCode()).as(raw + " " + r.body()).isEqualTo(400);
			assertThat(rows(s)).isZero();
		}
		// Jackson 은 스칼라를 문자열로 바꿔 준다 — 저장값은 forSave 를 지난 값이다
		for (String raw : List.of("123", "true")) {
			String s = slug();
			HttpResponse<String> r = editor.post("/api/admin/pages",
					"{\"slug\": \"" + s + "\", \"title\": \"제목\", \"body_html\": " + raw + "}");
			assertThat(r.statusCode()).as(raw + " " + r.body()).isEqualTo(201);
			assertThat(text("SELECT body_html FROM pages WHERE slug = ?", s))
				.isEqualTo(((RichText.Ok) RichText.forSave(raw)).html());
		}
	}

	// ---- 본문 ----

	@Test
	void 본문_길이() throws Exception {
		TestHttp editor = login("editor").http();
		String max = "<p>" + "a".repeat(RichText.MAX - 7) + "</p>";
		UUID id = create(editor, slug(), max);
		assertThat(stored(id)).isEqualTo(max);
		String s = slug();
		fieldError(post(editor, s, max + "a"), "body_html", "200,000자 이하");
		// 정화 뒤 &lt; 로 늘어난다 — DB CHECK 위반 500 이 아니다
		fieldError(post(editor, s, "<".repeat(RichText.MAX)), "body_html", "200,000자 이하");
		assertThat(rows(s)).isZero();
	}

	@Test
	void 본문_제어_문자() throws Exception {
		TestHttp editor = login("editor").http();
		for (String bad : List.of("<p>a\u0000b</p>", "<p>a\u0008b</p>")) {
			String s = slug();
			fieldError(post(editor, s, bad), "body_html", "쓸 수 없는 문자가 있습니다");
			assertThat(rows(s)).isZero();
		}
		UUID id = create(editor, slug(), "<p>a\nb\r\tc</p>");
		assertThat(stored(id)).contains("a\nb").contains("\tc");
	}

	/** 엔티티로 쓴 제어 문자는 jsoup 이 푼 뒤에야 제어 문자가 된다(Task 2 Ruling). 실제 DB 왕복으로 본다. */
	@Test
	void 엔티티_제어_문자는_422_이거나_무해한_치환이고_저장값에_제어_문자가_없다() throws Exception {
		TestHttp editor = login("editor").http();
		String s = slug();
		fieldError(post(editor, s, "<p>&#8;</p>"), "body_html", "쓸 수 없는 문자가 있습니다");
		assertThat(rows(s)).isZero();

		UUID nul = create(editor, slug(), "<p>&#0;</p>");
		assertThat(stored(nul)).isEqualTo("<p>�</p>");
		UUID alt = create(editor, slug(), "<img src=\"/api/media/" + U + "\" alt=\"&#0;\">");
		for (UUID id : List.of(nul, alt)) {
			String v = stored(id);
			assertThat(v.indexOf('\0')).isEqualTo(-1);
			assertThat(Parser.unescapeEntities(v, true)).doesNotContainPattern("[\\p{Cc}&&[^\\n\\r\\t]]");
		}
	}

	@Test
	void 빈_본문은_422_사진만은_201() throws Exception {
		TestHttp editor = login("editor").http();
		for (String empty : List.of("<p> </p>", "<p>&nbsp;</p>", "<p>​</p>", "<script>x</script>", "")) {
			String s = slug();
			fieldError(post(editor, s, empty), "body_html", "본문을 입력하세요");
			assertThat(rows(s)).isZero();
		}
		fieldError(editor.post("/api/admin/pages", body(slug(), "제목", null)), "body_html", "필수입니다");
		String img = "<img src=\"/api/media/" + U + "\">";
		assertThat(stored(create(editor, slug(), img))).isEqualTo(img);
	}

	@Test
	@Timeout(60)
	void 깊은_중첩_200000자도_500_없이_끝난다() throws Exception {
		TestHttp editor = login("editor").http();
		String s = slug();
		long t = System.nanoTime();
		HttpResponse<String> r = post(editor, s, "<em>".repeat(RichText.MAX / 4));
		System.out.printf("깊은 중첩 200000자: %d ms, %d%n", (System.nanoTime() - t) / 1_000_000, r.statusCode());
		fieldError(r, "body_html", "200,000자 이하");
		assertThat(rows(s)).isZero();
	}

	record Vector(String id, String input, String server) {
	}

	/** 0절 완료 기준 1: 저장값과 공개 응답이 고정 파일의 server 와 바이트까지 같다. */
	@Test
	void 벡터_입력을_저장하면_저장값과_공개_응답이_server_와_같다() throws Exception {
		List<Vector> vectors;
		try (InputStream in = PagesApiTest.class.getResourceAsStream("/richtext/vectors.json")) {
			vectors = List.of(json.readValue(in, Vector[].class));
		}
		assertThat(vectors).hasSizeGreaterThan(80);
		TestHttp editor = login("editor").http();
		List<String> failed = new ArrayList<>();
		for (Vector v : vectors) {
			// 본문이 비지 않게 앞에 붙인다 — 뒤에 붙이면 닫히지 않은 요소(S1 <strong>a 등)가 붙인 문단을 삼킨다
			String s = slug();
			UUID id = create(editor, s, "<p>z</p>" + v.input());
			publish(editor, id);
			String want = "<p>z</p>" + v.server();
			String pub = json.readTree(view(f.http(), s).body()).get("body_html").asString();
			if (!want.equals(stored(id)) || !want.equals(pub)) {
				failed.add(v.id() + ": " + stored(id));
			}
		}
		assertThat(failed).isEmpty();
	}

	// ---- 슬러그 충돌 ----

	@Test
	void 작성_슬러그_중복은_409() throws Exception {
		Login editor = login("editor");
		String s = slug();
		create(editor.http(), s, "<p>a</p>");
		HttpResponse<String> dup = post(editor.http(), s, "<p>b</p>");
		refused(dup, 409, "이미 사용 중인 주소입니다");
		assertThat(dup.body()).doesNotContain(s);
		assertThat(rows(s)).isEqualTo(1);
		assertThat(logs(editor.id())).isEqualTo(1);
	}

	@Test
	@Timeout(90)
	void 같은_슬러그_동시_작성은_201_하나_409_하나() throws Exception {
		TestHttp editor = login("editor").http();
		String s = slug();
		String b = body(s, "제목", "<p>a</p>");
		CompletableFuture<HttpResponse<String>> a = editor.postAsync("/api/admin/pages", b, Duration.ofSeconds(30));
		CompletableFuture<HttpResponse<String>> c = editor.postAsync("/api/admin/pages", b, Duration.ofSeconds(30));
		List<Integer> codes = new ArrayList<>(
				List.of(a.get(30, TimeUnit.SECONDS).statusCode(), c.get(30, TimeUnit.SECONDS).statusCode()));
		codes.sort(null);
		assertThat(codes).containsExactly(201, 409);
		assertThat(rows(s)).isEqualTo(1);
	}

	// ---- 발행 ----

	@Test
	void 첫_발행은_204_와_행_하나_다시_발행은_204_행_그대로() throws Exception {
		Login editor = login("editor");
		UUID id = create(editor.http(), slug(), "<p>a</p>");
		publish(editor.http(), id);
		publish(editor.http(), id);
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action = 'page.publish'",
				editor.id())).isEqualTo(1);
		assertThat(unpublish(editor.http(), id).statusCode()).isEqualTo(204);
		assertThat(unpublish(editor.http(), id).statusCode()).isEqualTo(204);
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action = 'page.unpublish'",
				editor.id())).isEqualTo(1);
		assertThat(logs(editor.id())).isEqualTo(3);
	}

	/** 예약어는 릴리스마다 는다 — 발행 때 다시 본다. 발행 취소는 보지 않는다(언제든 내릴 수 있게). */
	@Test
	void DB_에_넣은_예약어_슬러그_초안의_발행은_409_발행된_그_행의_재발행도_409_발행_취소는_204() throws Exception {
		Login editor = login("editor");
		f.sql("INSERT INTO pages (slug, title, body_html) VALUES ('admin', '옛 페이지', '<p>a</p>')");
		UUID id = UUID.fromString(text("SELECT id::text FROM pages WHERE slug = 'admin'"));
		refused(editor.http().post("/api/admin/pages/" + id + "/publish", ""), 409, "이 주소는 시스템 경로와 충돌합니다");
		assertThat(text("SELECT status FROM pages WHERE id = ?", id)).isEqualTo("draft");
		f.sql("UPDATE pages SET status = 'published' WHERE id = ?", id);
		refused(editor.http().post("/api/admin/pages/" + id + "/publish", ""), 409, "이 주소는 시스템 경로와 충돌합니다");
		assertThat(unpublish(editor.http(), id).statusCode()).isEqualTo(204);
		assertThat(text("SELECT status FROM pages WHERE id = ?", id)).isEqualTo("draft");
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action <> 'auth.login'",
				editor.id())).isEqualTo(1);
	}

	@Test
	void 없는_id_와_UUID_가_아닌_경로는_404() throws Exception {
		TestHttp editor = login("editor").http();
		UUID ghost = UUID.randomUUID();
		refused(editor.post("/api/admin/pages/" + ghost + "/publish", ""), 404, NOT_FOUND);
		refused(unpublish(editor, ghost), 404, NOT_FOUND);
		for (String bad : List.of("abc", "%20", "1")) {
			assertThat(editor.post("/api/admin/pages/" + bad + "/publish", "").statusCode()).as(bad).isEqualTo(404);
			assertThat(editor.post("/api/admin/pages/" + bad + "/unpublish", "").statusCode()).as(bad).isEqualTo(404);
		}
	}

	// ---- 작업 로그 ----

	@Test
	void 작성_발행_발행취소_뒤_그_계정_행에_슬러그_제목_본문이_없다() throws Exception {
		Login editor = login("editor");
		int n = SEQ.incrementAndGet();
		String s = "secret-slug-" + n;
		String title = "비밀제목" + n;
		String text = "비밀본문" + n;
		HttpResponse<String> r = editor.http().post("/api/admin/pages", body(s, title, "<p>" + text + "</p>"));
		assertThat(r.statusCode()).isEqualTo(201);
		UUID id = UUID.fromString(json.readTree(r.body()).get("id").asString());
		publish(editor.http(), id);
		assertThat(unpublish(editor.http(), id).statusCode()).isEqualTo(204);
		String all = text("SELECT string_agg(row_to_json(o)::text, ',') FROM operation_logs o WHERE actor_user_id = ?",
				editor.id());
		assertThat(all).contains(id.toString()).doesNotContain(s).doesNotContain(title).doesNotContain(text);
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action LIKE 'page.%' "
				+ "AND summary <> ''", editor.id())).isZero();
	}

	@Test
	void 거절은_기록하지_않는다() throws Exception {
		Login editor = login("editor");
		Login probe = login(role("admin.access", "page.update"));
		String s = slug();
		UUID id = create(editor.http(), s, "<p>a</p>");
		long base = logs(editor.id());
		assertThat(post(probe.http(), slug(), "<p>a</p>").statusCode()).isEqualTo(403);
		assertThat(probe.http().post("/api/admin/pages/" + id + "/publish", "").statusCode()).isEqualTo(403);
		assertThat(editor.http().post("/api/admin/pages/" + UUID.randomUUID() + "/publish", "").statusCode())
			.isEqualTo(404);
		assertThat(post(editor.http(), s, "<p>b</p>").statusCode()).isEqualTo(409);
		assertThat(post(editor.http(), "Bad", "<p>b</p>").statusCode()).isEqualTo(422);
		assertThat(logs(probe.id())).isZero();
		assertThat(logs(editor.id())).isEqualTo(base);
	}
}
