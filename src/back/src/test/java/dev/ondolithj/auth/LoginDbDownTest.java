package dev.ondolithj.auth;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static dev.ondolithj.auth.AuthFixture.loginBody;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 로그인 중 DB 를 읽지 못하면 「비밀번호가 맞지 않습니다」가 아니라 500 이고 ERROR 로그가 남는다(운영 진단). */
@ExtendWith(OutputCaptureExtension.class)
class LoginDbDownTest {

	@Test
	void DB_가_죽으면_로그인은_500_이고_ERROR_로그가_남는다(CapturedOutput out) throws Exception {
		try (AuthFixture f = new AuthFixture().startWith(false, "--spring.datasource.hikari.connection-timeout=1000")) {
			f.user("admin@ondolith.test", "admin");
			TestHttp http = f.http();
			http.get("/api/health"); // CSRF 쿠키를 먼저 받는다
			f.pg.stop();
			HttpResponse<String> r = http.post("/api/auth/login", loginBody("admin@ondolith.test", PASSWORD));
			assertThat(r.statusCode()).isEqualTo(500);
			assertThat(r.body()).doesNotContain("비밀번호가 맞지 않습니다").doesNotContain("Exception");
			assertThat(out.getAll()).contains("처리하지 못한 예외");
		}
	}
}
