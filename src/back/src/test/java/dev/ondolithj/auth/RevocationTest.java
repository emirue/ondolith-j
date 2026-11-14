package dev.ondolithj.auth;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static dev.ondolithj.auth.AuthFixture.loginBody;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/** 요청마다 권한을 DB 에서 읽으므로 회수·비활성·기준 시각이 다음 요청에 반영된다(1-A 스펙 3.4, D15 4.3-1·5.4). */
class RevocationTest {

	static final AtomicInteger SEQ = new AtomicInteger();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	/** 새 계정을 만들고 로그인한 클라이언트. */
	static TestHttp member(String email, String... roles) throws Exception {
		f.user(email, roles);
		TestHttp http = f.http();
		assertThat(http.post("/api/auth/login", loginBody(email, PASSWORD)).statusCode()).isEqualTo(204);
		return http;
	}

	static String email() {
		return "m" + SEQ.incrementAndGet() + "@ondolith.test";
	}

	@Test
	void 역할_없는_회원은_관리자_API_에서_403_Problem_Details() throws Exception {
		HttpResponse<String> r = member(email()).get("/api/admin");
		assertThat(r.statusCode()).isEqualTo(403);
		assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
	}

	@Test
	void 역할을_주고_빼면_다음_요청부터_반영된다() throws Exception {
		String email = email();
		TestHttp http = member(email);
		f.sql("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r WHERE u.email = ? AND r.key = 'editor'", email);
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(200);
		f.sql("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE email = ?)", email);
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(403);
	}

	@Test
	void 모두가_갖는_역할에_준_관리_권한은_효력이_없다_R9() throws Exception {
		f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r, permissions p "
				+ "WHERE r.key IN ('member', 'anonymous') AND p.key = 'admin.access'");
		try {
			assertThat(member(email()).get("/api/admin").statusCode()).isEqualTo(403);
		}
		finally {
			f.sql("DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE key IN ('member', 'anonymous'))");
		}
	}

	@Test
	void 모두가_갖는_역할의_슈퍼유저_표시도_효력이_없다_R9() throws Exception {
		f.sql("UPDATE roles SET is_superuser = false WHERE key = 'admin'");
		f.sql("UPDATE roles SET is_superuser = true WHERE key = 'member'");
		try {
			assertThat(member(email()).get("/api/admin").statusCode()).isEqualTo(403);
		}
		finally {
			f.sql("UPDATE roles SET is_superuser = false WHERE key = 'member'");
			f.sql("UPDATE roles SET is_superuser = true WHERE key = 'admin'");
		}
	}

	@Test
	void 비활성화하면_다음_요청이_401_이고_세션이_지워진다() throws Exception {
		String email = email();
		TestHttp http = member(email, "editor");
		String id = sessionId(http);
		f.sql("UPDATE users SET is_active = false WHERE email = ?", email);
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(401);
		assertThat(sessionOf(id)).isNull();
	}

	@Test
	void 기준_시각을_앞으로_옮기면_그_전_세션은_401() throws Exception {
		String email = email();
		TestHttp http = member(email, "editor");
		String id = sessionId(http);
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(200); // 같은 값이면 유효
		f.sql("UPDATE users SET sessions_valid_from = sessions_valid_from + interval '1 microsecond' WHERE email = ?", email);
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(401);
		assertThat(sessionOf(id)).isNull();
	}

	@Test
	void 세션_속성의_타입이_다르면_익명이_되고_세션이_지워진다() throws Exception {
		TestHttp http = member(email(), "editor");
		@SuppressWarnings("unchecked")
		SessionRepository<Session> repo = f.ctx.getBean(SessionRepository.class);
		String id = sessionId(http);
		Session s = sessionOf(id);
		s.setAttribute("ondolithj.auth_at", "2026-11-14T00:00:00Z"); // Instant 가 아니라 문자열
		repo.save(s);
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(401);
		assertThat(sessionOf(id)).isNull();
	}

	@Test
	void 공개_API_는_권한을_읽지_않는다() throws Exception {
		String email = email();
		TestHttp http = member(email, "editor");
		String id = sessionId(http);
		f.sql("UPDATE users SET is_active = false WHERE email = ?", email);
		// 권한을 읽었다면 비활성 계정의 세션이 지워졌을 것이다
		http.get("/api/health");
		http.get("/api/site");
		assertThat(sessionOf(id)).isNotNull();
		assertThat(http.get("/api/admin").statusCode()).isEqualTo(401);
		assertThat(sessionOf(id)).isNull();
	}

	/** 쿠키의 세션 ID(Spring Session 은 base64 로 싣는다). 세션이 지워지면 쿠키도 만료되므로 먼저 잡아 둔다. */
	static String sessionId(TestHttp http) {
		return new String(Base64.getDecoder().decode(http.cookie("SESSION")), StandardCharsets.UTF_8);
	}

	static Session sessionOf(String id) {
		@SuppressWarnings("unchecked")
		SessionRepository<Session> repo = f.ctx.getBean(SessionRepository.class);
		return repo.findById(id);
	}
}
