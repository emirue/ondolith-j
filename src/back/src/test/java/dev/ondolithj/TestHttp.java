package dev.ondolithj;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
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
		// localhost 는 ::1 로 풀릴 수 있어 다른 프로세스로 갈 수 있다. 루프백 IPv4 로 고정한다
		return new TestHttp("http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port"));
	}

	public HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
	}

	public HttpResponse<String> get(String path, String accept) throws IOException, InterruptedException {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Accept", accept).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	/** CSRF 없이 보낸다. */
	public HttpResponse<String> postRaw(String path, String json) throws IOException, InterruptedException {
		return client.send(json(path, json).build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 먼저 GET /api/health 로 토큰 쿠키를 받고, 그 값을 헤더로 붙여 보낸다. */
	public HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
		return client.send(withToken(path, json).build(), HttpResponse.BodyHandlers.ofString());
	}

	/** post 와 같다. 토큰은 보내기 전에 받아 두고, 요청마다 시한을 건다(동시 요청 시험이 멈추지 않게). */
	public CompletableFuture<HttpResponse<String>> postAsync(String path, String json, Duration timeout)
			throws IOException, InterruptedException {
		return client.sendAsync(withToken(path, json).timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest.Builder withToken(String path, String json) throws IOException, InterruptedException {
		String token = xsrf();
		if (token == null) {
			get("/api/health");
			token = xsrf();
		}
		HttpRequest.Builder b = json(path, json);
		if (token != null) {
			b.header("X-XSRF-TOKEN", token);
		}
		return b;
	}

	/**
	 * 본문 바이트를 그대로 보낸다. chunked 면 길이를 모르는 게시자라 Transfer-Encoding: chunked 가 된다(HTTP/1.1 고정).
	 * csrf 면 post 와 같은 토큰 헤더를 붙인다.
	 */
	public HttpResponse<String> send(String method, String path, String contentType, byte[] body, boolean chunked,
			boolean csrf) throws IOException, InterruptedException {
		HttpRequest.BodyPublisher p = chunked
				? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body))
				: HttpRequest.BodyPublishers.ofByteArray(body);
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).version(HttpClient.Version.HTTP_1_1)
			.header("Content-Type", contentType).method(method, p);
		if (csrf) {
			if (xsrf() == null) {
				get("/api/health");
			}
			b.header("X-XSRF-TOKEN", xsrf());
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 이름이 같은 쿠키 값. 없으면 null. */
	public String cookie(String name) {
		return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name))
			.map(HttpCookie::getValue).findFirst().orElse(null);
	}

	/** 쿠키를 직접 심는다(세션 고정 시험: 공격자가 정한 값). */
	public void plant(String name, String value) {
		HttpCookie c = new HttpCookie(name, value);
		c.setPath("/");
		c.setVersion(0);
		cookies.getCookieStore().add(URI.create(base), c);
	}

	public void forget(String name) {
		cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name)).toList()
			.forEach(c -> cookies.getCookieStore().remove(null, c));
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
