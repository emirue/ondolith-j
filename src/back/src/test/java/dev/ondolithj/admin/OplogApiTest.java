package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A-601 GET /api/admin/oplog(1-B1 스펙 5절·8절). operation_logs 는 비울 수 없으니 있는 행을 먼저 세고 채운다. */
class OplogApiTest {

	static final AtomicInteger SEQ = new AtomicInteger();

	static final JsonMapper json = JsonMapper.builder().build();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture();
		// 질의 수를 세려고 pg_stat_statements 를 올린다(postgres:18-alpine 에 들어 있다, 2026-11-21 확인)
		f.pg.withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements");
		f.start(false);
		f.sql("CREATE EXTENSION pg_stat_statements");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static String email() {
		return "oplog" + SEQ.incrementAndGet() + "@ondolith.test";
	}

	static TestHttp as(String email, String... roles) throws Exception {
		f.user(email, roles);
		return f.loginAs(email);
	}

	static long one(String sql) throws SQLException {
		try (Connection c = f.db(); ResultSet r = c.createStatement().executeQuery(sql)) {
			r.next();
			return r.getLong(1);
		}
	}

	/** 앱이 operation_logs 에 보낸 질의 수(이 시험의 통계 질의는 뺀다). */
	static long calls() throws SQLException {
		return one("SELECT coalesce(sum(calls), 0) FROM pg_stat_statements "
				+ "WHERE query ILIKE '%operation_logs%' AND query NOT ILIKE '%pg_stat_statements%'");
	}

	@Test
	void 운영자는_200_편집자는_403() throws Exception {
		assertThat(as(email(), "operator").get("/api/admin/oplog").statusCode()).isEqualTo(200);
		assertThat(as(email(), "editor").get("/api/admin/oplog").statusCode()).isEqualTo(403);
	}

	@Test
	void 쪽마다_100행_최신이_먼저_질의는_행_수와_관계없이_둘() throws Exception {
		String email = email();
		TestHttp http = as(email, "operator");

		long before = calls();
		HttpResponse<String> few = http.get("/api/admin/oplog?page=1");
		long fewCalls = calls() - before;
		assertThat(few.statusCode()).isEqualTo(200);
		// 채운 행은 미래 시각이라 로그인 행이 뒤로 밀린다 — ip 는 채우기 전에 본다
		JsonNode login = null;
		for (JsonNode it : json.readTree(few.body()).get("items")) {
			if (it.get("action").asString().equals("auth.login") && it.get("actor_email").asString().equals(email)) {
				login = it;
			}
		}
		assertThat(login).isNotNull();
		assertThat(login.get("ip").asString()).isEqualTo("127.0.0.1");

		long existing = one("SELECT count(*) FROM operation_logs");
		assertThat(existing).isBetween(1L, 100L);
		f.sql("""
				INSERT INTO operation_logs (action, target_type, summary, created_at)
				SELECT 'test.fill', 'test', 'n' || g, now() + g * interval '1 millisecond' FROM generate_series(1, ?) g""",
				(int) (101 - existing));

		before = calls();
		HttpResponse<String> r1 = http.get("/api/admin/oplog?page=1");
		long manyCalls = calls() - before;
		assertThat(fewCalls).isEqualTo(2);
		assertThat(manyCalls).isEqualTo(2);

		JsonNode p1 = json.readTree(r1.body());
		assertThat(p1.get("total").asLong()).isEqualTo(101);
		assertThat(p1.get("page").asInt()).isEqualTo(1);
		assertThat(p1.get("page_size").asInt()).isEqualTo(100);
		List<OffsetDateTime> times = new ArrayList<>();
		p1.get("items").forEach(it -> times.add(OffsetDateTime.parse(it.get("created_at").asString())));
		assertThat(times).hasSize(100).isSortedAccordingTo(Comparator.reverseOrder());

		JsonNode p2 = json.readTree(http.get("/api/admin/oplog?page=2").body());
		assertThat(p2.get("items")).hasSize(1);
		try (Connection c = f.db(); ResultSet r = c.createStatement()
			.executeQuery("SELECT id::text FROM operation_logs ORDER BY created_at, id LIMIT 1")) {
			r.next();
			assertThat(p2.at("/items/0/id").asString()).isEqualTo(r.getString(1));
		}
		assertThat(json.readTree(http.get("/api/admin/oplog?page=3").body()).get("items")).isEmpty();
	}

	@Test
	void 쪽_번호_검사() throws Exception {
		TestHttp http = as(email(), "operator");
		HttpResponse<String> zero = http.get("/api/admin/oplog?page=0");
		assertThat(zero.statusCode()).isEqualTo(422);
		assertThat(json.readTree(zero.body()).at("/errors/page").isString()).isTrue();

		HttpResponse<String> max = http.get("/api/admin/oplog?page=2147483647");
		assertThat(max.statusCode()).isEqualTo(200);
		assertThat(json.readTree(max.body()).get("items")).isEmpty();

		for (String bad : new String[] { "abc", "2147483648" }) {
			HttpResponse<String> r = http.get("/api/admin/oplog?page=" + bad);
			assertThat(r.statusCode()).as(bad).isEqualTo(400);
			// 처리하지 못한 예외(500)가 아니라 Spring 기본 처리기의 Problem Details
			assertThat(r.headers().firstValue("Content-Type")).as(bad).hasValue("application/problem+json");
			assertThat(json.readTree(r.body()).get("status").asInt()).as(bad).isEqualTo(400);
		}
	}
}
