package dev.ondolithj;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.context.ConfigurableApplicationContext;

/** 시험용 HTTP 도우미. 쿠키를 기억하고, 요청하면 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 붙인다(프런트와 같은 방식). */
public final class TestHttp {

	private final String base;
	private final CookieManager cookies = new CookieManager();
	private final HttpClient client;

	public TestHttp(String base) {
		this.base = base;
		this.client = HttpClient.newBuilder().cookieHandler(cookies).build();
	}

	public static TestHttp of(ConfigurableApplicationContext ctx) {
		return new TestHttp("http://localhost:" + ctx.getEnvironment().getProperty("local.server.port"));
	}

	public HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
	}

	/** CSRF 없이 보낸다. */
	public HttpResponse<String> postRaw(String path, String json) throws IOException, InterruptedException {
		return client.send(json(path, json).build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 먼저 GET /api/health 로 토큰 쿠키를 받고, 그 값을 헤더로 붙여 보낸다. */
	public HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
		String token = xsrf();
		if (token == null) {
			get("/api/health");
			token = xsrf();
		}
		HttpRequest.Builder b = json(path, json);
		if (token != null) {
			b.header("X-XSRF-TOKEN", token);
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	public String xsrf() {
		return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
			.map(HttpCookie::getValue).findFirst().orElse(null);
	}

	private HttpRequest.Builder json(String path, String json) {
		return HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json));
	}
}
