package dev.ondolithj.auth;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static dev.ondolithj.auth.AuthFixture.loginBody;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET /api/me — 화면 표시용 로그인 상태(1-A 스펙 6절). 보안 판정은 서버가 한다. */
class MeTest {

	static AuthFixture f;

	static final JsonMapper json = JsonMapper.builder().build();

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
		f.user("admin@ondolith.test", "admin");
		f.user("member@ondolith.test");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static JsonNode me(TestHttp http) throws Exception {
		var r = http.get("/api/me");
		assertThat(r.statusCode()).isEqualTo(200);
		return json.readTree(r.body());
	}

	static TestHttp as(String email) throws Exception {
		TestHttp http = f.http();
		assertThat(http.post("/api/auth/login", loginBody(email, PASSWORD)).statusCode()).isEqualTo(204);
		return http;
	}

	@Test
	void 익명은_user_가_null_이고_권한이_모두_false_이며_세션을_만들지_않는다() throws Exception {
		f.sql("DELETE FROM spring_session");
		JsonNode me = me(f.http());
		assertThat(me.get("user").isNull()).isTrue();
		assertThat(me.at("/can/admin.access").asBoolean(true)).isFalse();
		assertThat(f.sessions()).isZero();
	}

	@Test
	void 관리자는_이메일과_권한이_보인다() throws Exception {
		JsonNode me = me(as("admin@ondolith.test"));
		assertThat(me.at("/user/email").asString()).isEqualTo("admin@ondolith.test");
		assertThat(me.at("/user").has("display_name")).isTrue();
		assertThat(me.at("/can/admin.access").asBoolean()).isTrue();
	}

	@Test
	void 회원은_관리자_권한이_false_이다() throws Exception {
		assertThat(me(as("member@ondolith.test")).at("/can/admin.access").asBoolean(true)).isFalse();
	}
}
