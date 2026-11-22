package dev.ondolithj.admin;

import dev.ondolithj.auth.Actor;
import dev.ondolithj.auth.ActorQuery;
import dev.ondolithj.auth.AdminPermission;
import dev.ondolithj.auth.Permission;
import dev.ondolithj.auth.Reauth;
import dev.ondolithj.shared.ClientIp;
import dev.ondolithj.shared.OperationLog;
import dev.ondolithj.shared.OperationLog.Action;
import dev.ondolithj.shared.Refused;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-402 사용자 상세·비활성·활성(1-B2 스펙 4절). 기록기를 부르므로 @Transactional 을 달지 않는다.
 * 비활성·활성은 재인증 대상이다 — 본문 첫 줄이 reauth.require 이고, 창이 닫힌 요청은 아무것도 바꾸지 않고 기록하지 않는다.
 */
@RestController
class UsersController {

	private static final String PROBLEM = "application/problem+json";

	private static final String NOT_FOUND = "사용자를 찾을 수 없습니다";

	private static final String ROLE_COLUMNS = "r.id, r.key, r.name, " + ActorQuery.SUPERUSER_ROLE + " AS is_superuser";

	// 기준 시각을 앞으로만 옮긴다 — 재활성화가 옛 세션을 살리지 않는다(스펙 4절, Q11)
	private static final String DEACTIVATE = """
			UPDATE users SET is_active = false,
			    sessions_valid_from = GREATEST(now(), sessions_valid_from + interval '1 microsecond'), updated_at = now()
			WHERE id = ?""";

	record RoleRef(UUID id, String key, String name, boolean isSuperuser) {
	}

	record UserDetail(UUID id, String email, String displayName, boolean isActive, Instant createdAt, List<RoleRef> roles,
			List<RoleRef> assignableRoles) {
	}

	record UserRow(UUID id, String email, String displayName, boolean isActive, Instant createdAt) {
	}

	private final JdbcClient jdbc;

	private final OperationLog operationLog;

	private final Reauth reauth;

	UsersController(JdbcClient jdbc, OperationLog operationLog, Reauth reauth) {
		this.jdbc = jdbc;
		this.operationLog = operationLog;
		this.reauth = reauth;
	}

	@AdminPermission(Permission.USER_UPDATE)
	@GetMapping("/api/admin/users/{id}")
	@ApiResponse(responseCode = "200", description = "사용자와 가진 역할, 줄 수 있는 역할")
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "user.update 권한 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 사용자·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	UserDetail detail(@PathVariable UUID id) {
		UserRow u = jdbc.sql("SELECT id, email, display_name, is_active, created_at FROM users WHERE id = ?").param(id)
			.query(UserRow.class).optional().orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_FOUND));
		List<RoleRef> roles = jdbc.sql("SELECT " + ROLE_COLUMNS
				+ " FROM roles r JOIN user_roles ur ON ur.role_id = r.id WHERE ur.user_id = ? ORDER BY r.key")
			.param(id).query(RoleRef.class).list();
		List<RoleRef> assignable = jdbc.sql("SELECT " + ROLE_COLUMNS + " FROM roles r WHERE r.is_assignable ORDER BY r.key")
			.query(RoleRef.class).list();
		return new UserDetail(u.id(), u.email(), u.displayName(), u.isActive(), u.createdAt(), roles, assignable);
	}

	@AdminPermission(Permission.USER_UPDATE)
	@PostMapping("/api/admin/users/{id}/deactivate")
	@ApiResponse(responseCode = "204", description = "비활성화함(이미 비활성이어도). 그 계정의 세션은 모두 끝난다", content = @Content)
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "권한 없음·재인증 필요·자기 계정·superuser 보유자(R6)·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 사용자·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "마지막 활성 관리자", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> deactivate(@PathVariable UUID id, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		reauth.require(request);
		refuse(id, actor, Action.USER_DEACTIVATE, request);
		write(DEACTIVATE, id);
		operationLog.record(actor, Action.USER_DEACTIVATE, id.toString(), "", ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	@AdminPermission(Permission.USER_UPDATE)
	@PostMapping("/api/admin/users/{id}/activate")
	@ApiResponse(responseCode = "204", description = "활성화함(이미 활성이어도). 옛 세션은 살아나지 않는다", content = @Content)
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "권한 없음·재인증 필요·자기 계정·superuser 보유자(R6)·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 사용자·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> activate(@PathVariable UUID id, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		reauth.require(request);
		refuse(id, actor, Action.USER_ACTIVATE, request);
		write("UPDATE users SET is_active = true, updated_at = now() WHERE id = ?", id);
		operationLog.record(actor, Action.USER_ACTIVATE, id.toString(), "", ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	/** 없음 404(남기지 않음) → 자기 계정 403 → R6 403(둘 다 남김). 비교는 UUID 값끼리라 대문자 경로도 같은 사람이다. */
	private void refuse(UUID id, Actor actor, Action action, HttpServletRequest request) {
		boolean holder = jdbc.sql("SELECT " + ActorQuery.HOLDS_SUPERUSER + " FROM users u WHERE u.id = ?").param(id)
			.query(Boolean.class).optional().orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_FOUND));
		if (id.equals(actor.id())) {
			deny(actor, action, id, "self", "자기 계정에는 이 작업을 할 수 없습니다", request);
		}
		// R6: 비활성·활성 둘 다(Q19) — 막아 둔 superuser 보유자를 비-superuser 가 다시 열지 못하게
		if (holder && !actor.superuser()) {
			deny(actor, action, id, "r6", "권한이 없습니다", request);
		}
	}

	private void deny(Actor actor, Action action, UUID id, String rule, String detail, HttpServletRequest request) {
		operationLog.record(actor, Action.USER_DENIED, id.toString(), action.key() + " " + rule, ClientIp.of(request));
		throw new Refused(HttpStatus.FORBIDDEN, detail);
	}

	/** 조회와 쓰기 사이에 사라졌으면 404. */
	private void write(String sql, UUID id) {
		if (jdbc.sql(sql).param(id).update() == 0) {
			throw new Refused(HttpStatus.NOT_FOUND, NOT_FOUND);
		}
	}
}
