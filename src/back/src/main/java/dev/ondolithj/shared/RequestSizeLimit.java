package dev.ondolithj.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모든 요청 본문을 1 MiB 로 묶는다(1-C1 스펙 17절 2). 서블릿 필터의 맨 앞이라 세션·보안 체인보다 먼저 돈다 —
 * URL 층이 permitAll 이라 @RequestBody 해석이 권한 검사보다 앞이기 때문이다. 운영은 shared 스캔, 설치는 @Import 로 얻는다.
 * Content-Length 초과는 읽기 전에 413, 길이가 없으면(chunked) 읽는 바이트를 세어 MAX + 1 바이트째에 끊는다.
 * ponytail: 1-C3 업로드는 「POST 이고 원본 getRequestURI() 가 정확히 /api/admin/pages/media 일 때만 10 MiB」 한 행을 더한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimit extends OncePerRequestFilter {

	public static final long MAX = 1_048_576;

	/** 세는 스트림이 상한을 넘었다. */
	public static class TooLarge extends IOException {

		TooLarge() {
			super("요청 본문이 너무 큽니다");
		}
	}

	private final JsonMapper json;

	public RequestSizeLimit(JsonMapper json) {
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX) {
			refuse(response);
			return;
		}
		try {
			chain.doFilter(new Counted(request), response);
		}
		catch (IOException | ServletException | RuntimeException e) {
			// MVC 밖에서 본문을 읽는 곳의 초과는 여기까지 올라온다 — 500·컨테이너 오류 본문이 아니라 413 Problem Details.
			// PUT·PATCH·DELETE 폼은 FormContentFilter 가 세는 스트림으로 읽고, CSRF 헤더 없는 POST 폼은 CsrfFilter 의
			// getParameter 가 Tomcat 폼 해석(max-http-form-post-size)을 열어 InvalidParameterException(413)을 낸다
			if (response.isCommitted() || !tooLarge(e)) {
				throw e;
			}
			response.reset();
			refuse(response);
		}
	}

	/**
	 * 413 을 쓰는 한 곳. 보안 체인보다 앞이라(또는 reset 이 지워서) 체인의 헤더 필터가 단 헤더가 없다 — 보통 응답의 넷을 직접 단다(1-C2 스펙 6.4).
	 * Cache-Control 은 Spring Security 의 CacheControlHeadersWriter 값과 같다(RequestSizeLimitTest 가 보통 응답과 비교한다).
	 */
	private void refuse(HttpServletResponse response) throws IOException {
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Content-Security-Policy", SecurityConfig.API_CSP);
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
		SecurityConfig.problem(response, json, HttpStatus.CONTENT_TOO_LARGE, "요청 본문이 너무 큽니다");
	}

	private static boolean tooLarge(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof TooLarge || t instanceof InvalidParameterException p && p.getErrorCode() == 413) {
				return true;
			}
		}
		return false;
	}

	private static final class Counted extends HttpServletRequestWrapper {

		private ServletInputStream in;

		private BufferedReader reader;

		Counted(HttpServletRequest request) {
			super(request);
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			return stream();
		}

		@Override
		public BufferedReader getReader() throws IOException {
			if (reader == null) {
				// 인코딩은 부를 때 읽는다 — CharacterEncodingFilter 와 순서가 같아 선후가 정해지지 않는다
				String enc = getCharacterEncoding();
				reader = new BufferedReader(
						new InputStreamReader(stream(), enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc)));
			}
			return reader;
		}

		private ServletInputStream stream() throws IOException {
			if (in == null) {
				in = new CountingStream(super.getInputStream());
			}
			return in;
		}
	}

	private static final class CountingStream extends ServletInputStream {

		private final ServletInputStream in;

		private long count;

		CountingStream(ServletInputStream in) {
			this.in = in;
		}

		private int counted(int n) throws TooLarge {
			if (n > 0 && (count += n) > MAX) {
				throw new TooLarge();
			}
			return n;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) {
				counted(1);
			}
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return counted(in.read(b, off, len));
		}

		@Override
		public boolean isFinished() {
			return in.isFinished();
		}

		@Override
		public boolean isReady() {
			return in.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			in.setReadListener(listener);
		}
	}
}
