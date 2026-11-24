package dev.ondolithj.auth;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /api/admin/reauth(1-B2 스펙 3.3). 재인증 한도는 기본 5 — 계정은 시험마다 새로 만든다. */
@ExtendWith(OutputCaptureExtension.class)
class ReauthApiTest {

	static final String URN = "urn:ondolithj:problem:reauth-required";

	static final AtomicInteger SEQ = new AtomicInteger();

	static final JsonMapper JSON = JsonMapper.builder().build();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static String email() {
		return "reauth" + SEQ.incrementAndGet() + "@ondolith.test";
	}

	static HttpResponse<String> reauth(TestHttp http, String password) throws Exception {
		return http.post("/api/admin/reauth", JSON.writeValueAsString(Map.of("password", password)));
	}

	static void reauthRequired(HttpResponse<String> r) {
		assertThat(r.statusCode()).isEqualTo(403);
		JsonNode body = JSON.readTree(r.body());
		assertThat(body.get("type").asString()).isEqualTo(URN);
		assertThat(body.get("detail").asString()).isEqualTo("비밀번호를 다시 입력하세요");
	}

	static Session session(TestHttp http) {
		@SuppressWarnings("unchecked")
		SessionRepository<Session> repo = f.ctx.getBean(SessionRepository.class);
		return repo.findById(new String(Base64.getDecoder().decode(http.cookie("SESSION")), StandardCharsets.UTF_8));
	}

	@Test
	void 맞는_비밀번호는_204_이고_세션_ID_가_그대로다() throws Exception {
		String email = email();
		f.user(email, "admin");
		TestHttp http = f.loginAs(email);
		String before = http.cookie("SESSION");
		f.reauthAt(http, null);
		assertThat((Object) session(http).getAttribute(Reauth.AT)).isNull();
		HttpResponse<String> r = reauth(http, PASSWORD);
		assertThat(r.statusCode()).isEqualTo(204);
		assertThat(r.headers().allValues("Set-Cookie")).noneMatch(v -> v.startsWith("SESSION="));
		assertThat(http.cookie("SESSION")).isEqualTo(before);
		assertThat((Object) session(http).getAttribute(Reauth.AT)).isInstanceOf(Instant.class);
	}

	@Test
	void 틀린_비밀번호는_재인증_403() throws Exception {
		String email = email();
		f.user(email, "admin");
		reauthRequired(reauth(f.loginAs(email), "wrong-pass-0000"));
	}

	@Test
	void 계정당_5번까지이고_6번째는_429_Retry_After_다른_계정은_따로_센다() throws Exception {
		RateLimitTest.withinOneWindow(() -> {
			String x = email();
			String y = email();
			f.user(x, "admin");
			f.user(y, "admin");
			TestHttp hx = f.loginAs(x);
			TestHttp hy = f.loginAs(y);
			for (int i = 0; i < 5; i++) {
				reauthRequired(reauth(hx, "wrong-pass-0000"));
			}
			// 맞는 비밀번호로도 막힌다 — 대조 전에 거른다
			RateLimitTest.problem429(reauth(hx, PASSWORD));
			assertThat(reauth(hy, PASSWORD).statusCode()).isEqualTo(204);
		});
	}

	@Test
	void CSRF_없으면_403_익명은_401() throws Exception {
		String email = email();
		f.user(email, "admin");
		String body = JSON.writeValueAsString(Map.of("password", PASSWORD));
		assertThat(f.loginAs(email).postRaw("/api/admin/reauth", body).statusCode()).isEqualTo(403);
		assertThat(f.http().post("/api/admin/reauth", body).statusCode()).isEqualTo(401);
	}

	@Test
	void 천_자를_넘거나_비면_422() throws Exception {
		String email = email();
		f.user(email, "admin");
		TestHttp http = f.loginAs(email);
		for (HttpResponse<String> r : List.of(reauth(http, "a".repeat(1001)), reauth(http, ""),
				http.post("/api/admin/reauth", "{}"))) {
			assertThat(r.statusCode()).isEqualTo(422);
			assertThat(JSON.readTree(r.body()).at("/errors/password").isString()).isTrue();
		}
	}

	@Test
	void 칠십삼_바이트_비밀번호는_500_이_아니라_403() throws Exception {
		String email = email();
		f.user(email, "admin");
		reauthRequired(reauth(f.loginAs(email), "a".repeat(73)));
	}

	@Test
	void 비밀번호가_앱_로그와_작업_로그에_없다(CapturedOutput out) throws Exception {
		String email = email();
		UUID id = f.user(email, "admin");
		TestHttp http = f.loginAs(email);
		reauthRequired(reauth(http, "reauth-wrong-7c1d"));
		assertThat(reauth(http, PASSWORD).statusCode()).isEqualTo(204);
		assertThat(out.getAll()).contains("재인증 실패: 사용자 " + id).contains("재인증 성공: 사용자 " + id)
			.doesNotContain("reauth-wrong-7c1d").doesNotContain(PASSWORD).doesNotContain(email);
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
		assertThat(rows).hasSize(1); // auth.login 뿐 — 재인증은 작업 로그에 남기지 않는다
		assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContain("reauth-wrong-7c1d").doesNotContain(PASSWORD));
	}
}
