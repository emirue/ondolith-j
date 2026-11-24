package dev.ondolithj.ops;

import dev.ondolithj.auth.PublicApi;
import dev.ondolithj.shared.Refused;
import dev.ondolithj.shared.RichText;
import dev.ondolithj.shared.Slug;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * P-202 GET /api/pages/{slug} — 발행된 페이지(1-C1 스펙 5.5). 세션·권한을 읽지 않는다: 로그인 쿠키가 있든 없든 응답이 같다.
 * 초안·없는 주소는 누구에게나 404 다(403 은 존재를 알려 준다).
 */
@RestController
class PageController {

	record PublicPage(String slug, String title, String bodyHtml) {
	}

	private final JdbcClient jdbc;

	PageController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@PublicApi
	@GetMapping("/api/pages/{slug}")
	@ApiResponse(responseCode = "200", description = "발행된 페이지. body_html 은 내보낼 때 다시 정화한 값")
	@ApiResponse(responseCode = "404", description = "없는 주소·초안·형식이 틀린 슬러그", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	PublicPage page(@PathVariable String slug) {
		// 형식이 틀리면 DB 에 가지 않는다
		if (!Slug.valid(slug)) {
			throw new Refused(HttpStatus.NOT_FOUND, "페이지를 찾을 수 없습니다");
		}
		// 초안은 조건으로 거른다 — 읽은 뒤 비교하지 않아 초안이 DB 밖으로 나오지 않는다
		return jdbc.sql("SELECT slug, title, body_html FROM pages WHERE slug = ? AND status = 'published'").param(slug)
			.query((rs, n) -> new PublicPage(rs.getString("slug"), rs.getString("title"),
					RichText.clean(rs.getString("body_html"))))
			.optional()
			.orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, "페이지를 찾을 수 없습니다"));
	}
}
