package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A-602 GET /api/admin/system(1-B1 스펙 5절·8절). */
class SystemApiTest {

	static final AtomicInteger SEQ = new AtomicInteger();

	static final JsonMapper json = JsonMapper.builder().build();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static TestHttp as(String... roles) throws Exception {
		String email = "system" + SEQ.incrementAndGet() + "@ondolith.test";
		f.user(email, roles);
		return f.loginAs(email);
	}

	@Test
	void 운영자는_200_편집자는_403() throws Exception {
		assertThat(as("operator").get("/api/admin/system").statusCode()).isEqualTo(200);
		assertThat(as("editor").get("/api/admin/system").statusCode()).isEqualTo(403);
	}

	@Test
	void 버전_적용_목록_대기_수_DB_판() throws Exception {
		JsonNode s = json.readTree(as("operator").get("/api/admin/system").body());
		String health = json.readTree(f.http().get("/api/health").body()).get("version").asString();
		assertThat(s.get("version").asString()).isEqualTo(health);
		List<String> versions = new ArrayList<>();
		for (JsonNode m : s.get("migrations")) {
			versions.add(m.get("version").asString());
			assertThat(m.get("success").asBoolean()).isTrue();
			assertThat(m.get("description").asString()).isNotBlank();
			OffsetDateTime.parse(m.get("installed_on").asString());
		}
		assertThat(versions).containsExactly("1", "2", "3");
		assertThat(s.get("pending_count").asInt()).isZero();
		assertThat(s.at("/database/server_version").asString()).isNotBlank();
	}

	@Test
	void 본문에_DSN_호스트_포트_DB_계정_비밀번호가_없다() throws Exception {
		HttpResponse<String> r = as("operator").get("/api/admin/system");
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(r.body()).doesNotContain(f.pg.getJdbcUrl())
			.doesNotContain("jdbc:")
			.doesNotContain(f.pg.getHost() + ":" + f.pg.getMappedPort(5432))
			.doesNotContain(f.pg.getUsername())
			.doesNotContain(f.pg.getPassword())
			.doesNotContain("installed_by");
	}
}
