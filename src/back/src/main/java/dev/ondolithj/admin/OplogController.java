package dev.ondolithj.admin;

import dev.ondolithj.auth.AdminPermission;
import dev.ondolithj.auth.Permission;
import dev.ondolithj.shared.FieldErrors;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-601 작업 로그 목록(1-B1 스펙 5절). 질의는 행 수와 관계없이 목록·셈 둘이다(이메일은 스냅샷이라 조인이 없다).
 * ponytail: OFFSET 쪽 나누기 — 깊은 쪽은 표를 훑어 느리고 새 행이 들면 쪽이 밀린다(스펙 「알려진 한계」 3). 기간 조회(FR-712)가 오면 커서로 바꾼다.
 */
@RestController
class OplogController {

	private static final String PROBLEM = "application/problem+json";

	private static final int PAGE_SIZE = 100;

	record OplogItem(UUID id, Instant createdAt, UUID actorUserId, String actorEmail, String action, String targetType,
			String targetId, String summary, String ip) {
	}

	record OplogPage(List<OplogItem> items, int page, int pageSize, long total) {
	}

	private final JdbcClient jdbc;

	OplogController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@AdminPermission(Permission.LOG_VIEW)
	@GetMapping("/api/admin/oplog")
	@ApiResponse(responseCode = "200", description = "한 쪽(100행). 끝을 넘으면 빈 items")
	@ApiResponse(responseCode = "400", description = "page 가 정수가 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "log.view 권한 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "page 가 1 미만. errors.page", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	OplogPage list(@RequestParam(defaultValue = "1") int page) {
		if (page < 1) {
			throw new FieldErrors(Map.of("page", "1 이상"));
		}
		List<OplogItem> items = jdbc.sql("""
				SELECT id, created_at, actor_user_id, actor_email, action, target_type, target_id, summary, host(ip) AS ip
				FROM operation_logs ORDER BY created_at DESC, id DESC LIMIT 100 OFFSET ?""")
			.param((page - 1L) * PAGE_SIZE) // int 곱은 큰 쪽 번호에서 넘친다
			.query(OplogItem.class)
			.list();
		long total = jdbc.sql("SELECT count(*) FROM operation_logs").query(Long.class).single();
		return new OplogPage(items, page, PAGE_SIZE, total);
	}
}
