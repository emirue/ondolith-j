package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A-201 POST /api/admin/settings(1-B1 스펙 5절·8절). operation_logs 는 비울 수 없으니 시험마다 새 계정으로 센다. */
@ExtendWith(OutputCaptureExtension.class)
class SettingsApiTest {

	static final AtomicInteger SEQ = new AtomicInteger();

	static final JsonMapper json = JsonMapper.builder().build();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
		f.sql("INSERT INTO settings (key, value) VALUES ('site.type', 'cms')");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static String email() {
		return "settings" + SEQ.incrementAndGet() + "@ondolith.test";
	}

	/** 새 계정을 만들고 로그인한 클라이언트. */
	static TestHttp as(String email, String... roles) throws Exception {
		f.user(email, roles);
		return f.loginAs(email);
	}

	static HttpResponse<String> save(TestHttp http, String name) throws Exception {
		return http.post("/api/admin/settings", json.writeValueAsString(Map.of("name", name)));
	}

	static JsonNode site() throws Exception {
		return json.readTree(f.http().get("/api/site").body());
	}

	static long settingsRows(UUID actor) throws SQLException {
		try (Connection c = f.db();
				PreparedStatement s = c.prepareStatement(
						"SELECT count(*) FROM operation_logs WHERE action = 'settings.update' AND actor_user_id = ?")) {
			s.setObject(1, actor);
			try (ResultSet r = s.executeQuery()) {
				r.next();
				return r.getLong(1);
			}
		}
	}

	@Test
	void 운영자와_관리자는_204_편집자는_403_익명은_401_CSRF_없으면_403() throws Exception {
		assertThat(save(as(email(), "operator"), "운영자 이름").statusCode()).isEqualTo(204);
		assertThat(save(as(email(), "admin"), "관리자 이름").statusCode()).isEqualTo(204);
		assertThat(save(as(email(), "editor"), "편집자 이름").statusCode()).isEqualTo(403);
		assertThat(save(f.http(), "익명 이름").statusCode()).isEqualTo(401);
		TestHttp op = as(email(), "operator");
		assertThat(op.postRaw("/api/admin/settings", json.writeValueAsString(Map.of("name", "x"))).statusCode())
			.isEqualTo(403);
	}

	@Test
	void 길이는_1자에서_100자까지_앞뒤_공백은_빼고_잰다() throws Exception {
		TestHttp http = as(email(), "operator");
		for (String ok : new String[] { "가", "가".repeat(100), "  " + "가".repeat(100) + "  " }) {
			assertThat(save(http, ok).statusCode()).as("[%s]", ok).isEqualTo(204);
		}
		for (String bad : new String[] { "가".repeat(101), "", "   ", " ", "​" }) {
			HttpResponse<String> r = save(http, bad);
			assertThat(r.statusCode()).as("[%s]", bad).isEqualTo(422);
			assertThat(json.readTree(r.body()).at("/errors/name").isString()).as("[%s]", bad).isTrue();
		}
	}

	@Test
	void JSON_이_아니면_400() throws Exception {
		assertThat(as(email(), "operator").post("/api/admin/settings", "not json").statusCode()).isEqualTo(400);
	}

	@Test
	void 앞뒤_공백을_뺀_값이_공개_사이트_정보에_보인다() throws Exception {
		assertThat(save(as(email(), "operator"), "  새 이름 ").statusCode()).isEqualTo(204);
		assertThat(site().get("name").asString()).isEqualTo("새 이름");
	}

	@Test
	void 바뀌면_행_하나_같은_값이면_행이_늘지_않는다() throws Exception {
		String email = email();
		UUID id = f.user(email, "operator");
		TestHttp http = f.loginAs(email);
		String name = "이름-" + SEQ.incrementAndGet();
		assertThat(save(http, name).statusCode()).isEqualTo(204);
		assertThat(settingsRows(id)).isEqualTo(1);
		try (Connection c = f.db();
				PreparedStatement s = c.prepareStatement("SELECT summary, target_type, target_id IS NULL, actor_email "
						+ "FROM operation_logs WHERE action = 'settings.update' AND actor_user_id = ?")) {
			s.setObject(1, id);
			try (ResultSet r = s.executeQuery()) {
				r.next();
				assertThat(r.getString(1)).isEqualTo("site.name");
				assertThat(r.getString(2)).isEqualTo("settings");
				assertThat(r.getBoolean(3)).isTrue();
				assertThat(r.getString(4)).isEqualTo(email);
			}
		}
		assertThat(save(http, name).statusCode()).isEqualTo(204);
		assertThat(settingsRows(id)).isEqualTo(1);
	}

	@Test
	void 본문의_다른_칸은_읽지_않는다() throws Exception {
		TestHttp http = as(email(), "operator");
		HttpResponse<String> r = http.post("/api/admin/settings",
				json.writeValueAsString(Map.of("name", "x", "type", "shop")));
		assertThat(r.statusCode()).isEqualTo(204);
		assertThat(site().get("type").asString()).isEqualTo("cms");
	}

	@Test
	void 작업_로그_행에_비밀번호_세션_토큰_DB_비밀번호가_없다() throws Exception {
		String email = email();
		UUID id = f.user(email, "operator");
		TestHttp http = f.loginAs(email);
		assertThat(save(http, "비밀 검사-" + SEQ.incrementAndGet()).statusCode()).isEqualTo(204);
		List<String> rows = new ArrayList<>();
		try (Connection c = f.db();
				PreparedStatement s = c
					.prepareStatement("SELECT row_to_json(o)::text FROM operation_logs o WHERE actor_user_id = ?")) {
			s.setObject(1, id);
			try (ResultSet r = s.executeQuery()) {
				while (r.next()) {
					rows.add(r.getString(1));
				}
			}
		}
		assertThat(rows).hasSize(2); // auth.login, settings.update
		String session = http.cookie("SESSION");
		String xsrf = http.xsrf();
		assertThat(session).isNotBlank();
		assertThat(xsrf).isNotBlank();
		assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContain(AuthFixture.PASSWORD).doesNotContain(session)
			.doesNotContain(xsrf).doesNotContain(f.pg.getPassword()));
	}

	@Test
	void 기록이_실패해도_저장은_204_이고_ERROR_줄에_이메일과_IP_가_없다(CapturedOutput out) throws Exception {
		String email = email();
		TestHttp http = as(email, "operator");
		String name = "기록 실패-" + SEQ.incrementAndGet();
		// 행 값이 메시지에 들어간다 — PostgreSQL 제약 위반의 「Failing row contains (…)」와 같은 위험을 재현한다
		f.sql("""
				CREATE FUNCTION oplog_reject() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN RAISE EXCEPTION 'rejected: %', row_to_json(NEW); END $$""");
		f.sql("CREATE TRIGGER oplog_reject BEFORE INSERT ON operation_logs FOR EACH ROW EXECUTE FUNCTION oplog_reject()");
		try {
			assertThat(save(http, name).statusCode()).isEqualTo(204);
		} finally {
			f.sql("DROP TRIGGER oplog_reject ON operation_logs");
			f.sql("DROP FUNCTION oplog_reject()");
		}
		assertThat(site().get("name").asString()).isEqualTo(name);
		// 예외 메시지는 여러 줄(SQL 본문)이라 행 값이 다음 줄로 밀린다 — 이메일·rejected 는 출력 전체에서 본다
		assertThat(out.getAll()).doesNotContain(email).doesNotContain("rejected");
		// 127.0.0.1 은 다른 줄(기동 로그 등)에도 나오므로 기록 실패 줄만 본다
		List<String> lines = out.getAll().lines().filter(l -> l.contains("작업 로그를 남기지 못했습니다")).toList();
		assertThat(lines).isNotEmpty();
		assertThat(lines).allSatisfy(l -> assertThat(l).contains("settings.update").doesNotContain(email)
			.doesNotContain("127.0.0.1").doesNotContain("rejected"));
	}
}
