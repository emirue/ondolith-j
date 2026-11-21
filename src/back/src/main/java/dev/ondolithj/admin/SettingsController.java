package dev.ondolithj.admin;

import dev.ondolithj.auth.Actor;
import dev.ondolithj.auth.AdminPermission;
import dev.ondolithj.auth.Permission;
import dev.ondolithj.shared.ClientIp;
import dev.ondolithj.shared.FieldErrors;
import dev.ondolithj.shared.OperationLog;
import dev.ondolithj.shared.SiteName;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-201 사이트 설정 저장(1-B1 스펙 5절). 기록기를 부르므로 @Transactional 을 달지 않는다 — 기록 실패가 저장을 되돌리지 않게.
 * ponytail: A-201 이 공개 칸(site.name)만 가져 조회 API 를 따로 두지 않는다(폼은 GET /api/site 를 읽는다) —
 * 비공개 칸(사업자·Turnstile)이 생기면 GET /api/admin/settings(settings.update, D19)를 더한다.
 */
@RestController
class SettingsController {

	private static final String PROBLEM = "application/problem+json";

	// 값이 실제로 바뀌었을 때만 행을 돌려준다 — 문장 하나라 동시 저장에도 기록 수가 맞다
	private static final String UPSERT = """
			INSERT INTO settings (key, value) VALUES ('site.name', ?)
			ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
			WHERE settings.value IS DISTINCT FROM EXCLUDED.value
			RETURNING key
			""";

	/** 고정 레코드 — 다른 칸(type 등)은 읽지 않는다(D86 2.5). */
	record SettingsRequest(String name) {
	}

	private final JdbcClient jdbc;

	private final OperationLog operationLog;

	SettingsController(JdbcClient jdbc, OperationLog operationLog) {
		this.jdbc = jdbc;
		this.operationLog = operationLog;
	}

	@AdminPermission(Permission.SETTINGS_UPDATE)
	@PostMapping("/api/admin/settings")
	@ApiResponse(responseCode = "204", description = "저장됨(값이 같아도)", content = @Content)
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "settings.update 권한 없음·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "입력 오류. errors.name", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> save(@RequestBody SettingsRequest req, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		SiteName.error(req.name()).ifPresent(m -> {
			throw new FieldErrors(Map.of("name", m));
		});
		boolean changed = jdbc.sql(UPSERT).param(SiteName.strip(req.name())).query(String.class).optional().isPresent();
		if (changed) {
			operationLog.record(actor, OperationLog.Action.SETTINGS_UPDATE, null, "site.name", ClientIp.of(request));
		}
		return ResponseEntity.noContent().build();
	}
}
