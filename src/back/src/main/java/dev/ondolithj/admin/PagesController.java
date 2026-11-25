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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-301 목록, A-302 작성·상세·수정, 삭제, A-303 발행·발행 취소(1-C1 스펙 5절, 17절 1). 기록기를 부르므로 @Transactional 을 달지 않는다.
 * 재인증 대상이 아니다(D15:545 일상 편집). 작업 로그에는 슬러그·제목·본문을 넣지 않는다 — id 만(7절).
 */
@RestController
class PagesController {

	private static final String PROBLEM = "application/problem+json";

	static final String NOT_FOUND = "페이지를 찾을 수 없습니다";

	private static final String EXISTS = "SELECT EXISTS (SELECT 1 FROM pages WHERE id = ?)";

	private static final int PAGE_SIZE = 50;

	// 1-B2 사용자 목록과 같다 — 전체 건수 없이 한 행 더 읽는다. 본문은 읽지 않는다(SC-4, 초안 본문이 목록 캐시에 남지 않는다)
	private static final String LIST = "SELECT id, slug, title, status, updated_at FROM pages "
			+ "ORDER BY updated_at DESC, id LIMIT 51 OFFSET ?";

	private static final String DELETE = "DELETE FROM pages WHERE id = ?";

	private static final String UPDATE = "UPDATE pages SET slug = ?, title = ?, body_html = ?, updated_at = now() WHERE id = ?";

	/**
	 * 고정 레코드 — 작성자·시각·id·정화 생략 칸이 없다(D86 2.5). status 는 받으려는 칸이 아니라 실리면 422 로 거절하려는 칸이다(Q8).
	 * 그 밖의 모르는 칸은 읽지 않는다.
	 */
	record PageInput(String slug, String title, String bodyHtml, @Schema(hidden = true) Object status) {
	}

	/** 승인자가 읽은 상세의 updated_at(불투명 값 — 화면은 응답 문자열을 그대로 되돌린다, 1-C2 스펙 6.1). 다른 칸은 읽지 않는다. */
	record PublishInput(Instant updatedAt) {
	}

	record PageItem(UUID id, String slug, String title, String status, Instant updatedAt) {
	}

	record PageList(List<PageItem> items, int page, int pageSize, boolean hasNext) {
	}

	record PageDetail(UUID id, String slug, String title, String bodyHtml, String status, Instant createdAt,
			Instant updatedAt) {
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

	@AdminPermission(Permission.PAGE_VIEW)
	@GetMapping("/api/admin/pages")
	@ApiResponse(responseCode = "200", description = "한 쪽(50행), 수정 시각 내림차순. 본문은 싣지 않는다. 끝을 넘으면 빈 items")
	@ApiResponse(responseCode = "400", description = "page 가 정수가 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "page.view 권한 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "page 가 1 미만. errors.page", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	PageList list(@RequestParam(defaultValue = "1") int page) {
		if (page < 1) {
			throw new FieldErrors(Map.of("page", "1 이상"));
		}
		List<PageItem> rows = jdbc.sql(LIST).param((page - 1L) * PAGE_SIZE) // int 곱은 큰 쪽 번호에서 넘친다
			.query((rs, n) -> new PageItem(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"),
					rs.getString("status"), rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
			.list();
		boolean hasNext = rows.size() > PAGE_SIZE;
		return new PageList(hasNext ? rows.subList(0, PAGE_SIZE) : rows, page, PAGE_SIZE, hasNext);
	}

	@AdminPermission(Permission.PAGE_VIEW)
	@GetMapping("/api/admin/pages/{id}")
	@ApiResponse(responseCode = "200", description = "편집 폼에 넣을 페이지(초안 포함). body_html 은 내보낼 때 다시 정화한 값")
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "page.view 권한 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 페이지·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	PageDetail detail(@PathVariable UUID id) {
		// 정화 규칙이 바뀌기 전에 저장된 값도 나갈 때 다시 거른다(스펙 3.3)
		return jdbc.sql("SELECT id, slug, title, body_html, status, created_at, updated_at FROM pages WHERE id = ?").param(id)
			.query((rs, n) -> new PageDetail(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"),
					RichText.clean(rs.getString("body_html")), rs.getString("status"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
			.optional()
			.orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_FOUND));
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

	@AdminPermission(Permission.PAGE_UPDATE)
	@PostMapping("/api/admin/pages/{id}")
	@ApiResponse(responseCode = "204", description = "고침. 상태는 바꾸지 않는다", content = @Content)
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님·칸의 형이 다름", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "page.update 권한 없음·CSRF 토큰 없음·발행본인데 page.publish 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 페이지·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "이미 사용 중인 주소", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "입력 오류(대상 조회보다 앞). errors.slug·errors.title·errors.body_html, 발행 상태가 실리면 errors.status", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody PageInput in, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		String html = validate(in);
		// 17절 1: 발행본은 page.publish 도 있어야 고친다. 조회 뒤 비교가 아니라 한 문장 — 그 사이 발행되는 경쟁이 없다.
		// superuser 는 ActorQuery 가 권한 전부로 채운다.
		// ponytail: 동시 편집은 나중 저장이 이긴다. 1-C2 는 자동 저장 없음(1-C2 스펙 6.3) — 자동 저장이나 편집이 사라졌다는 보고가 생기면 읽은 updated_at 으로 409
		boolean publisher = actor.permissions().contains(Permission.PAGE_PUBLISH);
		int n;
		try {
			n = jdbc.sql(publisher ? UPDATE : UPDATE + " AND status = 'draft'")
				.params(in.slug(), SiteName.strip(in.title()), html, id)
				.update();
		}
		catch (DuplicateKeyException e) {
			throw new Refused(HttpStatus.CONFLICT, "이미 사용 중인 주소입니다");
		}
		if (n == 0) {
			if (jdbc.sql(EXISTS).param(id).query(Boolean.class).single()) {
				throw new Refused(HttpStatus.FORBIDDEN, "발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다");
			}
			throw new Refused(HttpStatus.NOT_FOUND, NOT_FOUND);
		}
		operationLog.record(actor, Action.PAGE_UPDATE, id.toString(), "", ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	@AdminPermission(Permission.PAGE_DELETE)
	@PostMapping("/api/admin/pages/{id}/delete")
	@ApiResponse(responseCode = "204", description = "지움. 메뉴는 건드리지 않는다", content = @Content)
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "page.delete 권한 없음·CSRF 토큰 없음·발행본인데 page.publish 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 페이지·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Actor actor, HttpServletRequest request) {
		// 1-C2 스펙 6.1-3: 지우기는 공개를 끝내는 동작이라 발행본은 page.publish 도 있어야 지운다. 수정과 같이 한 문장으로 판정한다
		boolean publisher = actor.permissions().contains(Permission.PAGE_PUBLISH);
		if (jdbc.sql(publisher ? DELETE : DELETE + " AND status = 'draft'").param(id).update() == 0) {
			if (jdbc.sql(EXISTS).param(id).query(Boolean.class).single()) {
				throw new Refused(HttpStatus.FORBIDDEN, "발행된 페이지는 발행 권한이 있어야 지울 수 있습니다");
			}
			throw new Refused(HttpStatus.NOT_FOUND, NOT_FOUND);
		}
		operationLog.record(actor, Action.PAGE_DELETE, id.toString(), "", ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	@AdminPermission(Permission.PAGE_PUBLISH)
	@PostMapping("/api/admin/pages/{id}/publish")
	@ApiResponse(responseCode = "204", description = "발행함(이미 발행됐어도 — 그때는 updated_at 을 보지 않고 기록하지 않는다)", content = @Content)
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님·형식 불량", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "page.publish 권한 없음·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 페이지·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "예약어(슬러그가 시스템 경로와 충돌함)·낡은 updated_at(읽은 뒤 초안이 바뀜)", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "읽은 상세의 updated_at 이 없음(칸 검사가 대상 조회보다 앞). errors.updated_at", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> publish(@PathVariable UUID id, @RequestBody(required = false) PublishInput in,
			@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
		if (in == null || in.updatedAt() == null) {
			throw new FieldErrors(Map.of("updated_at", "필수입니다"));
		}
		String slug = jdbc.sql("SELECT slug FROM pages WHERE id = ?").param(id).query(String.class).optional()
			.orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_FOUND));
		// 예약어 목록은 릴리스마다 는다 — 저장 때 지났어도 발행 때 다시 본다(D19:2687). 이미 발행된 페이지도.
		// 내용 비교보다 앞이다 — 다시 읽어도 풀리지 않는다
		if (Slug.reserved(slug)) {
			throw new Refused(HttpStatus.CONFLICT, "이 주소는 시스템 경로와 충돌합니다");
		}
		// I-2: 승인자가 읽은 뒤 초안이 바뀌었으면 발행하지 않는다. 조회 뒤 비교가 아니라 한 문장
		int n = jdbc.sql("UPDATE pages SET status = 'published', updated_at = now() WHERE id = ? AND status = 'draft' AND updated_at = ?")
			// pgjdbc 42.7.14 는 Instant 의 SQL 형을 추론하지 못한다(500) — timestamptz 로 가는 OffsetDateTime(UTC)
			.params(id, OffsetDateTime.ofInstant(in.updatedAt(), ZoneOffset.UTC))
			.update();
		if (n == 1) {
			operationLog.record(actor, Action.PAGE_PUBLISH, id.toString(), "", ClientIp.of(request));
			return ResponseEntity.noContent().build();
		}
		// 이미 발행됐으면 updated_at 을 보지 않는다 — 발행이 updated_at 을 바꾸므로 응답을 잃고 다시 누른 요청이 409 가 되지 않게(Q7)
		String status = jdbc.sql("SELECT status FROM pages WHERE id = ?").param(id).query(String.class).optional()
			.orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_FOUND));
		if (status.equals("draft")) {
			throw new Refused(HttpStatus.CONFLICT, "그사이 페이지가 바뀌었습니다. 바뀐 내용을 확인한 뒤 발행하세요");
		}
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
