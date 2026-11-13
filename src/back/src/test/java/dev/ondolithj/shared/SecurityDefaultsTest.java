package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.Launcher;
import dev.ondolithj.TestHttp;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class SecurityDefaultsTest {

	@TempDir
	Path dir;

	ConfigurableApplicationContext ctx;

	TestHttp http;

	@BeforeEach
	void start() {
		ctx = Launcher.startInstall(dir.resolve("ondolith-j.properties"), "--server.port=0");
		http = TestHttp.of(ctx);
	}

	@AfterEach
	void stop() {
		ctx.close();
	}

	@Test
	void 헬스_응답에_보안_헤더() throws Exception {
		HttpResponse<String> r = http.get("/api/health");
		assertThat(r.headers().firstValue("X-Frame-Options")).hasValue("DENY");
		assertThat(r.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
		assertThat(r.headers().firstValue("Referrer-Policy")).hasValue("strict-origin-when-cross-origin");
		assertThat(r.headers().firstValue("Content-Security-Policy"))
			.hasValue("default-src 'none'; frame-ancestors 'none'");
	}

	@Test
	void GET_응답이_XSRF_TOKEN_쿠키를_준다() throws Exception {
		http.get("/api/health");
		assertThat(http.xsrf()).isNotBlank();
	}

	@Test
	void CSRF_헤더_없는_POST_는_403() throws Exception {
		assertThat(http.postRaw("/api/install", "{}").statusCode()).isEqualTo(403);
	}

	@Test
	void 없는_경로는_404_이고_내부_정보가_없다() throws Exception {
		HttpResponse<String> get = http.get("/api/site");
		assertThat(get.statusCode()).isEqualTo(404);
		assertThat(get.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
		assertThat(get.body()).doesNotContain("Exception").doesNotContain("at dev.").doesNotContain("trace");
		assertThat(http.post("/api/nothing", "{}").statusCode()).isEqualTo(404);
	}

	@Test
	void Spring_Security_의_기본_logout_경로도_없다() throws Exception {
		assertThat(http.post("/logout", "{}").statusCode()).isEqualTo(404);
	}

	@Test
	void 생성_비밀번호를_로그에_찍지_않는다(CapturedOutput out) {
		assertThat(out.getAll()).doesNotContainIgnoringCase("generated security password");
	}
}
