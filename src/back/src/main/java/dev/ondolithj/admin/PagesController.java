package dev.ondolithj.admin;

import dev.ondolithj.auth.Actor;
import dev.ondolithj.auth.AdminPermission;
import dev.ondolithj.auth.Permission;
import dev.ondolithj.shared.ClientIp;
import dev.ondolithj.shared.FieldErrors;
import dev.ondolithj.shared.OperationLog;
import dev.ondolithj.shared.OperationLog.Action;
import dev.ondolithj.shared.Refused;
import dev.ondolithj.shared.RichText;
import dev.ondolithj.shared.SiteName;
import dev.ondolithj.shared.Slug;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-302 작성, A-303 발행·발행 취소(1-C1 스펙 5절). 기록기를 부르므로 @Transactional 을 달지 않는다.
 * 재인증 대상이 아니다(D15:545 일상 편집). 작업 로그에는 슬러그·제목·본문을 넣지 않는다 — id 만(7절).
 */
@RestController
class PagesController {

	private static final String PROBLEM = "application/problem+json";

	static final String NOT_FOUND = "페이지를 찾을 수 없습니다";

	private static final String EXISTS = "SELECT EXISTS (SELECT 1 FROM pages WHERE id = ?)";

	/**
	 * 고정 레코드 — 작성자·시각·id·정화 생략 칸이 없다(D86 2.5). status 는 받으려는 칸이 아니라 실리면 422 로 거절하려는 칸이다(Q8).
	 * 그 밖의 모르는 칸은 읽지 않는다.
	 */
	record PageInput(String slug, String title, String bodyHtml, @Schema(hidden = true) Object status) {
	}

	private final JdbcClient jdbc;

	private final OperationLog operationLog;

	PagesController(JdbcClient jdbc, OperationLog operationLog) {
		this.jdbc = jdbc;
		this.operationLog = operationLog;
	}

	/** 칸을 한 번에 모아 검사하고 저장할 본문(정화한 값)을 돌려준다. 틀리면 FieldErrors(422). */
	static String validate(PageInput in) {
		Map<String, String> errors = new HashMap<>();
		if (in.status() != null) {
			errors.put("status", "발행 상태는 발행 단추로 바꿉니다");
		}
		Slug.error(in.slug()).ifPresent(m -> errors.put("slug", m));
		SiteName.error(in.title(), 200).ifPresent(m -> errors.put("title", m));
		RichText.Result body = RichText.forSave(in.bodyHtml());
		if (body instanceof RichText.Bad bad) {
			errors.put("body_html", bad.message());
		}
		if (!errors.isEmpty()) {
			throw new FieldErrors(errors);
		}
		return ((RichText.Ok) body).html();
	}

	@AdminPermission(Permission.PAGE_CREATE)
	@PostMapping("/api/admin/pages")
	@ApiResponse(responseCode = "201", description = "초안으로 만듦")
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님·칸의 형이 다름", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "page.create 권한 없음·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "이미 사용 중인 주소", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "입력 오류. errors.slug·errors.title·errors.body_html, 발행 상태가 실리면 errors.status", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<UsersController.Created> create(@RequestBody PageInput in, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		String html = validate(in);
		UUID id;
		// 먼저 세지 않는다 — 동시 작성 둘이 다 지나간다. UNIQUE 가 막는다
		try {
			id = jdbc.sql("INSERT INTO pages (slug, title, body_html) VALUES (?, ?, ?) RETURNING id")
				.params(in.slug(), SiteName.strip(in.title()), html)
				.query(UUID.class)
				.single();
		}
		catch (DuplicateKeyException e) {
			throw new Refused(HttpStatus.CONFLICT, "이미 사용 중인 주소입니다");
		}
		operationLog.record(actor, Action.PAGE_CREATE, id.toString(), "", ClientIp.of(request));
		return ResponseEntity.status(HttpStatus.CREATED).body(new UsersController.Created(id));
	}

	@AdminPermission(Permission.PAGE_PUBLISH)
	@PostMapping("/api/admin/pages/{id}/publish")
	@ApiResponse(responseCode = "204", description = "발행함(이미 발행됐어도 — 그때는 기록하지 않는다)", content = @Content)
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "page.publish 권한 없음·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 페이지·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "슬러그가 시스템 경로(예약어)와 충돌함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> publish(@PathVariable UUID id, @AuthenticationPrincipal Actor actor, HttpServletRequest request) {
		String slug = jdbc.sql("SELECT slug FROM pages WHERE id = ?").param(id).query(String.class).optional()
			.orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_FOUND));
		// 예약어 목록은 릴리스마다 는다 — 저장 때 지났어도 발행 때 다시 본다(D19:2687). 이미 발행된 페이지도
		if (Slug.reserved(slug)) {
			throw new Refused(HttpStatus.CONFLICT, "이 주소는 시스템 경로와 충돌합니다");
		}
		change(id, "published", "draft", Action.PAGE_PUBLISH, actor, request);
		return ResponseEntity.noContent().build();
	}

	@AdminPermission(Permission.PAGE_PUBLISH)
	@PostMapping("/api/admin/pages/{id}/unpublish")
	@ApiResponse(responseCode = "204", description = "발행을 취소함(이미 초안이어도 — 그때는 기록하지 않는다). 예약어는 보지 않는다", content = @Content)
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "page.publish 권한 없음·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 페이지·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> unpublish(@PathVariable UUID id, @AuthenticationPrincipal Actor actor, HttpServletRequest request) {
		// 문제 있는 페이지를 언제든 바로 내릴 수 있게 예약어를 보지 않는다
		change(id, "draft", "published", Action.PAGE_UNPUBLISH, actor, request);
		return ResponseEntity.noContent().build();
	}

	/**
	 * 한 문장이 실제로 바꾼 경우만 기록한다 — 같은 상태 재요청은 2xx·기록 없음, 동시에 눌러도 바꾼 한 사람만 남는다(Q7).
	 * 0행이면 다시 보고 없으면 404(그 사이 지워짐).
	 */
	private void change(UUID id, String to, String from, Action action, Actor actor, HttpServletRequest request) {
		int n = jdbc.sql("UPDATE pages SET status = ?, updated_at = now() WHERE id = ? AND status = ?")
			.params(to, id, from).update();
		if (n == 1) {
			operationLog.record(actor, action, id.toString(), "", ClientIp.of(request));
		}
		else if (!jdbc.sql(EXISTS).param(id).query(Boolean.class).single()) {
			throw new Refused(HttpStatus.NOT_FOUND, NOT_FOUND);
		}
	}
}
