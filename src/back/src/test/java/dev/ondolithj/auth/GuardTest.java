package dev.ondolithj.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.Launcher;
import dev.ondolithj.TestHttp;
import dev.ondolithj.config.ConfigFile;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 운영 컨텍스트의 권한 검사(스펙 5·6절). */
@ExtendWith(OutputCaptureExtension.class)
class GuardTest {

	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine");

	static ConfigurableApplicationContext ctx;

	@BeforeAll
	static void start(@TempDir Path dir) {
		pg.start();
		ConfigFile config = new ConfigFile(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(),
				URI.create("http://site.ondolith.test"), false, ConfigFile.newSealKey(), dir.resolve("u"), dir.resolve("b"),
				Instant.now());
		ctx = Launcher.startRunning(config, "--server.port=0", "--server.address=127.0.0.1");
	}

	@AfterAll
	static void stop() {
		ctx.close();
		pg.stop();
	}

	@Test
	void 익명의_관리자_API_는_401_Problem_Details_이고_로그인_창을_띄우지_않는다(CapturedOutput out) throws Exception {
		for (String accept : new String[] { "application/json", "text/html" }) {
			HttpResponse<String> r = TestHttp.of(ctx).get("/api/admin", accept);
			assertThat(r.statusCode()).as(accept).isEqualTo(401);
			assertThat(r.headers().firstValue("Content-Type")).as(accept)
				.hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
			assertThat(r.headers().firstValue("WWW-Authenticate")).as(accept).isEmpty();
			assertThat(r.body()).as(accept).contains("\"status\":401");
		}
		// 거부가 catch-all 예외 처리기에 잡혀 500·ERROR 로그가 되지 않는다
		assertThat(out.getAll()).doesNotContain("처리하지 못한 예외");
	}
}
