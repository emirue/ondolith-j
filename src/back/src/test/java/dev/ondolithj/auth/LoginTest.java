package dev.ondolithj.auth;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static dev.ondolithj.auth.AuthFixture.loginBody;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/** 로그인·로그아웃·DB 세션(1-A 스펙 3절). */
@ExtendWith(OutputCaptureExtension.class)
class LoginTest {

	static final String ADMIN = "admin@ondolith.test";

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
		f.user(ADMIN, "admin");
		f.user("inactive@ondolith.test", "admin");
		f.sql("UPDATE users SET is_active = false WHERE email = 'inactive@ondolith.test'");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	@BeforeEach
	void clean() throws Exception {
		f.sql("DELETE FROM spring_session");
	}

	static HttpResponse<String> login(TestHttp http, String email, String password) throws Exception {
		return http.post("/api/auth/login", loginBody(email, password));
	}

	@Test
	void 올바른_로그인은_204_이고_관리자_API_가_열린다() throws Exception {
		TestHttp http = f.http();
		assertThat(login(http, ADMIN, PASSWORD).statusCode()).isEqualTo(204);
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(200);
	}

	@Test
	void 이메일은_대소문자와_앞뒤_공백을_무시한다() throws Exception {
		assertThat(login(f.http(), "  Admin@Ondolith.test ", PASSWORD).statusCode()).isEqualTo(204);
	}

	@Test
	void 실패는_원인과_관계없이_같은_401_이다() throws Exception {
		String tooLong = "가".repeat(30); // 90바이트 — BCrypt 72바이트 상한을 넘는다
		List<HttpResponse<String>> rs = List.of(login(f.http(), "nobody@ondolith.test", PASSWORD),
				login(f.http(), ADMIN, "wrong-pass-0000"), login(f.http(), "inactive@ondolith.test", PASSWORD),
				login(f.http(), ADMIN, tooLong));
		for (HttpResponse<String> r : rs) {
			assertThat(r.statusCode()).isEqualTo(401);
			assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
			assertThat(r.body()).isEqualTo(rs.get(0).body()).contains("이메일 또는 비밀번호가 맞지 않습니다");
		}
	}

	@Test
	void 입력이_너무_길면_422() throws Exception {
		assertThat(login(f.http(), ADMIN, "x".repeat(1001)).statusCode()).isEqualTo(422);
		assertThat(login(f.http(), "a".repeat(250) + "@ondolith.test", PASSWORD).statusCode()).isEqualTo(422);
	}

	@Test
	void 로그인_성공_전에는_세션_행이_생기지_않는다() throws Exception {
		TestHttp http = f.http();
		http.get("/api/admin", "application/json");
		http.get("/api/admin", "text/html");
		http.get("/api/health");
		login(http, ADMIN, "wrong-pass-0000");
		http.postRaw("/api/auth/login", loginBody(ADMIN, PASSWORD)); // CSRF 없음 → 403
		assertThat(f.sessions()).isZero();
	}

	@Test
	void 미리_심은_세션_쿠키로_로그인해도_새_세션이다() throws Exception {
		TestHttp http = f.http();
		String planted = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
		http.plant("SESSION", planted);
		HttpResponse<String> r = login(http, ADMIN, PASSWORD);
		assertThat(r.statusCode()).isEqualTo(204);
		// 서버가 새 세션 ID 를 낸다
		String issued = r.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith("SESSION="))
			.map(v -> v.substring("SESSION=".length(), v.indexOf(';'))).findFirst().orElseThrow();
		assertThat(issued).isNotEqualTo(planted);
		// 공격자가 정한 ID 는 로그인 상태가 아니다
		TestHttp attacker = f.http();
		attacker.plant("SESSION", planted);
		assertThat(attacker.get("/api/admin").statusCode()).isEqualTo(401);
		TestHttp victim = f.http();
		victim.plant("SESSION", issued);
		assertThat(victim.get("/api/admin").statusCode()).isEqualTo(200);
	}

	@Test
	void 다시_로그인하면_세션_ID_와_CSRF_토큰이_바뀌고_옛_ID_는_무효다() throws Exception {
		TestHttp http = f.http();
		login(http, ADMIN, PASSWORD);
		String first = http.cookie("SESSION");
		http.get("/api/health");
		String xsrf = http.xsrf();
		assertThat(login(http, ADMIN, PASSWORD).statusCode()).isEqualTo(204);
		assertThat(http.cookie("SESSION")).isNotEqualTo(first);
		http.get("/api/health");
		assertThat(http.xsrf()).isNotEqualTo(xsrf);
		TestHttp old = f.http();
		old.plant("SESSION", first);
		assertThat(old.get("/api/admin").statusCode()).isEqualTo(401);
	}

	@Test
	void 세션에는_사용자_ID_기준_시각_재인증_시각만_담긴다() throws Exception {
		TestHttp http = f.http();
		Instant before = Instant.now();
		login(http, ADMIN, PASSWORD);
		Instant after = Instant.now();
		String id = new String(Base64.getDecoder().decode(http.cookie("SESSION")), StandardCharsets.UTF_8);
		@SuppressWarnings("unchecked")
		SessionRepository<Session> repo = f.ctx.getBean(SessionRepository.class);
		Session s = repo.findById(id);
		Instant validFrom;
		UUID userId;
		try (Connection c = f.db();
				ResultSet r = c.createStatement()
					.executeQuery("SELECT id, sessions_valid_from FROM users WHERE email = '" + ADMIN + "'")) {
			r.next();
			userId = r.getObject(1, UUID.class);
			validFrom = r.getObject(2, OffsetDateTime.class).toInstant();
		}
		assertThat(s.getAttributeNames()).contains("ondolithj.user_id", "ondolithj.auth_at", "ondolithj.reauth_at")
			.noneMatch(n -> n.contains("SPRING_SECURITY_CONTEXT"));
		// 재인증 창은 로그인 때 앱 시계로 연다(1-B2 스펙 3.1)
		assertThat((Object) s.getAttribute("ondolithj.reauth_at")).isInstanceOfSatisfying(Instant.class,
				t -> assertThat(t).isBetween(before, after));
		assertThat((Object) s.getAttribute("ondolithj.user_id")).isEqualTo(userId.toString());
		assertThat((Object) s.getAttribute("ondolithj.auth_at")).isEqualTo(validFrom);
		// 수명 12시간(D60 NFR-204, server.servlet.session.timeout)이 DB 세션에 닿는다
		assertThat(s.getMaxInactiveInterval()).isEqualTo(java.time.Duration.ofHours(12));
	}

	@Test
	void 로그아웃하면_세션_행이_지워지고_관리자_API_가_닫힌다() throws Exception {
		TestHttp http = f.http();
		login(http, ADMIN, PASSWORD);
		assertThat(f.sessions()).isEqualTo(1);
		assertThat(http.post("/api/auth/logout", "{}").statusCode()).isEqualTo(204);
		assertThat(f.sessions()).isZero();
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(401);
	}

	@Test
	void 세션_쿠키는_HttpOnly_SameSite_Lax_이다() throws Exception {
		HttpResponse<String> r = login(f.http(), ADMIN, PASSWORD);
		String cookie = r.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith("SESSION=")).findFirst()
			.orElseThrow();
		assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").doesNotContain("Secure");
	}

	@Test
	void 설정이_secure_면_세션_쿠키에_Secure_가_붙는다() throws Exception {
		try (AuthFixture secure = new AuthFixture().start(true)) {
			secure.user(ADMIN, "admin");
			HttpResponse<String> r = login(secure.http(), ADMIN, PASSWORD);
			assertThat(r.headers().allValues("Set-Cookie")).anyMatch(v -> v.startsWith("SESSION=") && v.contains("Secure"));
		}
	}

	@Test
	void 로그에_이메일과_비밀번호가_남지_않는다(CapturedOutput out) throws Exception {
		login(f.http(), ADMIN, PASSWORD);
		login(f.http(), ADMIN, "wrong-pass-0000");
		assertThat(out.getAll()).doesNotContain(ADMIN).doesNotContain(PASSWORD).doesNotContain("wrong-pass-0000");
	}
}
