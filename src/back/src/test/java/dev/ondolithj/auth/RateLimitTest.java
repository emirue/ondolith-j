package dev.ondolithj.auth;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static dev.ondolithj.auth.AuthFixture.loginBody;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** 로그인·관리자 경로 횟수 제한(1-A 스펙 4절). 시험마다 새 컨텍스트라 셈이 섞이지 않는다. */
class RateLimitTest {

	static void problem429(HttpResponse<String> r) {
		assertThat(r.statusCode()).isEqualTo(429);
		assertThat(r.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Integer.parseInt(v)).isBetween(1, 60));
		assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
	}

	@Test
	void 같은_IP_의_로그인은_10번까지이고_11번째는_429() throws Exception {
		try (AuthFixture f = new AuthFixture().startWith(false, "--ondolithj.rate-limit.login-account=100000")) {
			TestHttp http = f.http();
			for (int i = 0; i < 10; i++) {
				assertThat(http.post("/api/auth/login", loginBody("n" + i + "@ondolith.test", PASSWORD)).statusCode())
					.as("시도 %d", i + 1).isEqualTo(401);
			}
			problem429(http.post("/api/auth/login", loginBody("n10@ondolith.test", PASSWORD)));
		}
	}

	@Test
	void 같은_계정의_로그인은_5번까지이고_없는_계정도_똑같이_센다() throws Exception {
		try (AuthFixture f = new AuthFixture().startWith(false, "--ondolithj.rate-limit.login-ip=100000")) {
			f.user("real@ondolith.test", "admin");
			for (String email : new String[] { "real@ondolith.test", "ghost@ondolith.test" }) {
				for (int i = 0; i < 5; i++) {
					assertThat(f.http().post("/api/auth/login", loginBody(email, "wrong-pass-0000")).statusCode())
						.as("%s 시도 %d", email, i + 1).isEqualTo(401);
				}
				problem429(f.http().post("/api/auth/login", loginBody(email, PASSWORD)));
			}
			// 다른 계정은 영향이 없다
			assertThat(f.http().post("/api/auth/login", loginBody("other@ondolith.test", PASSWORD)).statusCode()).isEqualTo(401);
		}
	}

	@Test
	void 관리자_경로는_IP_당_60번까지이고_한_번만_센다() throws Exception {
		try (AuthFixture f = new AuthFixture().startWith(false)) {
			TestHttp http = f.http();
			for (int i = 0; i < 60; i++) {
				// 두 번 세면 31번째에 막힌다
				assertThat(http.get("/api/admin").statusCode()).as("요청 %d", i + 1).isEqualTo(401);
			}
			problem429(http.get("/api/admin"));
			// 관리자 경로 밖은 영향이 없다
			assertThat(http.get("/api/health").statusCode()).isEqualTo(200);
		}
	}
}
