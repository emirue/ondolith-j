package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.Launcher;
import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.AuthFixture;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.multipart.MultipartResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 모든 요청 본문 1 MiB(1-C1 스펙 17절 2). 두 컨텍스트, 보안 체인보다 앞. */
class RequestSizeLimitTest {

	static final int MAX = 1_048_576;

	static final String JSON = "application/json";

	static final String FORM = "application/x-www-form-urlencoded";

	static final JsonMapper json = JsonMapper.builder().build();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false, "--spring.main.sources=fixture.size.FormProbeConfig");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	static byte[] bytes(int n) {
		byte[] b = new byte[n];
		Arrays.fill(b, (byte) 'a');
		return b;
	}

	/** {"name":"aaa…"} 가 정확히 n 바이트. */
	static byte[] nameJson(int n) {
		return ("{\"name\":\"" + "a".repeat(n - 11) + "\"}").getBytes(StandardCharsets.UTF_8);
	}

	/** x=aaa… 가 정확히 n 바이트. */
	static byte[] form(int n) {
		return ("x=" + "a".repeat(n - 2)).getBytes(StandardCharsets.US_ASCII);
	}

	static long oplogs() throws Exception {
		try (Connection c = f.db(); ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM operation_logs")) {
			r.next();
			return r.getLong(1);
		}
	}

	static void tooLarge(HttpResponse<String> r) throws Exception {
		assertThat(r.statusCode()).isEqualTo(413);
		assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
		JsonNode body = json.readTree(r.body());
		assertThat(body.path("detail").asString()).isEqualTo("요청 본문이 너무 큽니다");
		assertThat(body.path("title").asString()).isEqualTo("Content Too Large");
		assertThat(body.path("status").asInt()).isEqualTo(413);
		// 요청 값(경로·본문)을 되비추지 않는다
		assertThat(r.body()).doesNotContain("/api/").doesNotContain("aaaa");
	}

	/** chunked 초과의 실제 값(instance 의 경로는 다른 400 과 같다 — 본문 값은 되비추지 않는다): Jackson 이 세는 스트림의 예외를 읽기 실패로 감싸 ApiErrors 의 400 이 된다(계획 「다르게 정한 것」 2). */
	static void chunkedRejected(HttpResponse<String> r) throws Exception {
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
		assertThat(r.body()).doesNotContain("aaaa");
	}

	@Test
	void 익명_1MiB_넘는_Content_Length_는_처리기_전에_413() throws Exception {
		long before = oplogs();
		TestHttp anon = f.http();
		tooLarge(anon.send("POST", "/api/admin/settings", JSON, nameJson(MAX + 1), false, false));
		tooLarge(anon.send("POST", "/api/auth/login", JSON, nameJson(MAX + 1), false, false));
		tooLarge(anon.send("PUT", "/api/no-such-path", JSON, bytes(MAX + 1), false, false));
		assertThat(oplogs()).isEqualTo(before);
	}

	@Test
	void 정확히_1MiB_는_막지_않는다() throws Exception {
		f.user("size-admin@ondolith.test", "admin");
		TestHttp admin = f.loginAs("size-admin@ondolith.test");
		for (boolean chunked : List.of(false, true)) {
			HttpResponse<String> r = admin.send("POST", "/api/admin/settings", JSON, nameJson(MAX), chunked, true);
			assertThat(r.statusCode()).as("chunked=%s", chunked).isEqualTo(422);
			assertThat(json.readTree(r.body()).path("errors").has("name")).isTrue();
		}
		// 한 바이트 더: 길이가 있으면 413, chunked 면 세는 스트림이 끊는다
		tooLarge(admin.send("POST", "/api/admin/settings", JSON, nameJson(MAX + 1), false, true));
		chunkedRejected(admin.send("POST", "/api/admin/settings", JSON, nameJson(MAX + 1), true, true));
	}

	@Test
	void chunked_1MiB_초과도_끊기고_500_이_아니다() throws Exception {
		chunkedRejected(f.http().send("POST", "/api/admin/settings", JSON, nameJson(2 * MAX), true, true));
	}

	@Test
	void 폼_Content_Length_초과는_413() throws Exception {
		tooLarge(f.http().send("POST", "/api/admin/settings", FORM, form(MAX + 1), false, false));
		tooLarge(f.http().send("POST", "/api/form-probe", FORM, form(MAX + 1), false, false));
	}

	@Test
	void 폼_chunked_초과는_1MiB_넘게_해석되지_않는다() throws Exception {
		// 탐침이 실제로 chunked 를 받고 매개변수를 읽는다
		HttpResponse<String> small = f.http().send("POST", "/api/form-probe", FORM, form(512 * 1024), true, true);
		assertThat(small.statusCode()).isEqualTo(200);
		assertThat(json.readTree(small.body()).path("length").asInt()).isEqualTo(524_286);
		assertThat(json.readTree(small.body()).path("content_length").asLong()).isEqualTo(-1);

		// Tomcat 11 은 폼 상한을 넘으면 getParameter 에서 던진다 — 탐침이 잡아 -2
		HttpResponse<String> big = f.http().send("POST", "/api/form-probe", FORM, form(1_500_000), true, true);
		assertThat(big.statusCode()).isEqualTo(200);
		assertThat(json.readTree(big.body()).path("length").asInt()).isEqualTo(-2);
		assertThat(json.readTree(big.body()).path("content_length").asLong()).isEqualTo(-1);
		// CSRF 헤더가 없으면 CsrfFilter 가 _csrf 매개변수를 찾느라 폼을 해석한다 — 컨테이너 오류 본문(path)이 아니라 413
		tooLarge(f.http().send("POST", "/api/form-probe", FORM, form(1_500_000), true, false));
	}

	/** PUT·PATCH·DELETE 폼은 FormContentFilter(보안 체인보다 앞)가 MVC 밖에서 읽는다. */
	@Test
	void POST_가_아닌_chunked_폼_초과도_500_이_아니라_413() throws Exception {
		for (boolean csrf : List.of(true, false)) {
			tooLarge(f.http().send("PUT", "/api/admin/settings", FORM, form(2 * MAX), true, csrf));
		}
	}

	@Test
	void multipart_해석기가_없다() {
		assertThat(f.ctx.getBeanNamesForType(MultipartResolver.class)).isEmpty();
	}

	/** 실제 Tomcat 필터 순서: 세션·폼·보안 필터보다 앞. */
	@Test
	void 필터가_세션과_보안_체인보다_앞이다() {
		Context web = (Context) ((TomcatWebServer) ((WebServerApplicationContext) f.ctx).getWebServer()).getTomcat()
			.getHost().findChildren()[0];
		List<String> names = Arrays.stream(web.findFilterMaps()).map(FilterMap::getFilterName).toList();
		assertThat(names).contains("requestSizeLimit", "springSessionRepositoryFilter", "springSecurityFilterChain");
		assertThat(names.indexOf("requestSizeLimit")).isLessThan(names.indexOf("springSessionRepositoryFilter"))
			.isLessThan(names.indexOf("springSecurityFilterChain"))
			.isLessThan(names.indexOf("formContentFilter"));
	}

	@Test
	void 설치_컨텍스트에도_걸린다() throws Exception {
		ConfigurableApplicationContext install = Launcher.startInstall(
				Files.createTempDirectory("size").resolve("conf/ondolith-j.properties"), cfg -> {
				}, "--server.port=0", "--server.address=127.0.0.1");
		try {
			TestHttp http = TestHttp.of(install);
			tooLarge(http.send("POST", "/api/install", JSON, bytes(MAX + 1), false, false));
			chunkedRejected(http.send("POST", "/api/install", JSON, nameJson(2 * MAX), true, true));
		}
		finally {
			install.close();
		}
	}
}
