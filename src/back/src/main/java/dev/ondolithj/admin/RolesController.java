package dev.ondolithj.admin;

import dev.ondolithj.auth.Actor;
import dev.ondolithj.auth.ActorQuery;
import dev.ondolithj.auth.AdminPermission;
import dev.ondolithj.auth.Permission;
import dev.ondolithj.auth.Reauth;
import dev.ondolithj.shared.ClientIp;
import dev.ondolithj.shared.FieldErrors;
import dev.ondolithj.shared.OperationLog;
import dev.ondolithj.shared.OperationLog.Action;
import dev.ondolithj.shared.Refused;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-403 역할 목록, A-404 역할 권한 부여·빼기, A-405 사용자 역할 부여·빼기(1-B2 스펙 6절). 기록기를 부르므로 @Transactional 을 달지
 * 않는다. 부여·빼기 넷은 재인증 대상이라 본문 첫 줄이 reauth.require 이고 @Valid 를 쓰지 않는다(칸 검사는 본문에서).
 * 작업 로그 summary 에는 enum 의 권한 키와 DB 에서 읽은 역할 키만 넣는다 — 요청 본문 문자열은 넣지 않는다(스펙 8절).
 */
@RestController
class RolesController {

	private static final String PROBLEM = "application/problem+json";

	private static final String NOT_FOUND = "찾을 수 없습니다";

	private static final String REQUIRED = "필수입니다";

	/** 모두가 갖는 역할. 방문자 권한만 효력이 있다(D15 R9). */
	private static final Set<String> EVERYONE = Set.of("anonymous", "member");

	// 역할+부여 집계 하나. 행 수와 관계없이 문장 하나다
	private static final String ROLES = """
			SELECT r.id, r.key, r.name, r.description, r.is_builtin, r.is_assignable, %s AS superuser,
			       array_remove(array_agg(p.key ORDER BY p.key), NULL) AS perms
			FROM roles r
			LEFT JOIN role_permissions rp ON rp.role_id = r.id
			LEFT JOIN permissions p ON p.id = rp.permission_id
			%s
			GROUP BY r.id
			ORDER BY r.key""";

	private static final String LIST = ROLES.formatted(ActorQuery.SUPERUSER_ROLE, "");

	private static final String ONE = ROLES.formatted(ActorQuery.SUPERUSER_ROLE, "WHERE r.id = ?");

	record Grant(String key, boolean effective) {
	}

	record RoleView(UUID id, String key, String name, String description, boolean isBuiltin, boolean isSuperuser,
			boolean isAssignable, List<Grant> permissions) {
	}

	record PermissionView(String key, String description) {
	}

	record RolesView(List<RoleView> roles, List<PermissionView> permissions) {
	}

	record PermissionRequest(String permission) {
	}

	record RoleRequest(UUID roleId) {
	}

	/** DB 에서 읽은 역할 한 행. superuser 는 ActorQuery 의 술어 값이다(anonymous·member 의 표시는 false). */
	private record Role(UUID id, String key, String name, String description, boolean builtin, boolean assignable,
			boolean superuser, List<String> perms) {
	}

	private final JdbcClient jdbc;

	private final OperationLog operationLog;

	private final Reauth reauth;

	private final LastAdmin lastAdmin;

	RolesController(JdbcClient jdbc, OperationLog operationLog, Reauth reauth, LastAdmin lastAdmin) {
		this.jdbc = jdbc;
		this.operationLog = operationLog;
		this.reauth = reauth;
		this.lastAdmin = lastAdmin;
	}

	/**
	 * 역할이 주는 권한. superuser 역할은 role_permissions 행이 없어도 코드의 권한 전부다 — 빈 집합으로 보면 R2 를 지나간다.
	 * 코드에 없는 키는 버리지 않고 던진다(버리면 필요한 집합이 줄어 열린 쪽으로 실패한다. 기동 점검이 막으므로 나지 않는다).
	 */
	static Set<Permission> permissionsOf(boolean superuserRole, Collection<String> keys) {
		if (superuserRole) {
			return EnumSet.allOf(Permission.class);
		}
		Set<Permission> out = EnumSet.noneOf(Permission.class);
		for (String k : keys) {
			out.add(Permission.ofKey(k).orElseThrow(() -> new IllegalStateException("코드에 없는 권한 키")));
		}
		return out;
	}

	/** R2·R4: 요청자의 유효 권한이 needed 를 모두 갖는가. */
	static boolean covers(Actor actor, Set<Permission> needed) {
		return actor.superuser() || actor.permissions().containsAll(needed);
	}

	@AdminPermission(Permission.ROLE_VIEW)
	@GetMapping("/api/admin/roles")
	@ApiResponse(responseCode = "200", description = "역할(키 순)과 A-404 가 고를 권한 목록. superuser 역할은 permissions 가 비어 있다")
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "role.view 권한 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	RolesView list() {
		List<RoleView> roles = jdbc.sql(LIST).query((rs, n) -> role(rs)).list().stream().map(r -> new RoleView(r.id(),
				r.key(), r.name(), r.description(), r.builtin(), r.superuser(), r.assignable(),
				r.superuser() ? List.of() : r.perms().stream().map(k -> new Grant(k, effective(r.key(), k))).toList()))
			.toList();
		List<PermissionView> perms = jdbc.sql("SELECT key, description FROM permissions ORDER BY key")
			.query(PermissionView.class).list();
		return new RolesView(roles, perms);
	}

	/** anonymous·member 의 방문자 권한 밖 부여는 효력이 없다(R9, ActorQuery 와 같은 규칙). */
	private static boolean effective(String roleKey, String permKey) {
		return !EVERYONE.contains(roleKey) || Permission.ofKey(permKey).map(Permission::visitor).orElse(false);
	}

	@AdminPermission(Permission.ROLE_MANAGE)
	@PostMapping("/api/admin/roles/{id}/permissions")
	@ApiResponse(responseCode = "204", description = "부여함(이미 있어도)", content = @Content)
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "권한 없음·재인증 필요·superuser 역할(R5)·보유하지 않은 권한(R4)·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 역할·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "칸이 비었음·모르는 권한·모두가 갖는 역할에 방문자 권한 밖(R9). errors.permission", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> grantPermission(@PathVariable UUID id, @RequestBody PermissionRequest req,
			@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
		reauth.require(request);
		Permission p = permission(id, req, actor, Action.ROLE_GRANT_PERMISSION, request);
		jdbc.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT ?, id FROM permissions WHERE key = ? "
				+ "ON CONFLICT DO NOTHING").params(id, p.key()).update();
		operationLog.record(actor, Action.ROLE_GRANT_PERMISSION, id.toString(), p.key(), ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	@AdminPermission(Permission.ROLE_MANAGE)
	@PostMapping("/api/admin/roles/{id}/permissions/revoke")
	@ApiResponse(responseCode = "204", description = "뺌", content = @Content)
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "권한 없음·재인증 필요·superuser 역할(R5)·보유하지 않은 권한(R4)·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 역할·그런 부여가 없음·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "칸이 비었음·모르는 권한. errors.permission", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> revokePermission(@PathVariable UUID id, @RequestBody PermissionRequest req,
			@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
		reauth.require(request);
		Permission p = permission(id, req, actor, Action.ROLE_REVOKE_PERMISSION, request);
		int n = jdbc.sql("DELETE FROM role_permissions WHERE role_id = ? "
				+ "AND permission_id = (SELECT id FROM permissions WHERE key = ?)").params(id, p.key()).update();
		if (n == 0) {
			throw new Refused(HttpStatus.NOT_FOUND, "그런 부여가 없습니다");
		}
		operationLog.record(actor, Action.ROLE_REVOKE_PERMISSION, id.toString(), p.key(), ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	/**
	 * A-404 순서(스펙 6절): 칸 없음 422 → 역할 없음 404(둘 다 남기지 않음) → 모르는 키 422 → R5 403 → (부여만) R9 422 → R4 403(넷은
	 * 남김). 모르는 키 거절 행에 그 키를 넣지 않는다 — 요청 본문 문자열이다.
	 */
	private Permission permission(UUID id, PermissionRequest req, Actor actor, Action action, HttpServletRequest request) {
		if (req.permission() == null) {
			throw new FieldErrors(Map.of("permission", REQUIRED));
		}
		Role role = role(id);
		Permission p = Permission.ofKey(req.permission()).orElseThrow(() -> deny(actor, Action.ROLE_DENIED, id,
				action.key() + " unknown_permission", request, new FieldErrors(Map.of("permission", "알 수 없는 권한입니다"))));
		String tried = action.key() + " " + p.key();
		if (role.superuser()) {
			throw deny(actor, Action.ROLE_DENIED, id, tried + " r5", request,
					new Refused(HttpStatus.FORBIDDEN, "이 역할의 권한은 변경할 수 없습니다"));
		}
		// 빼기에는 걸지 않는다 — 잘못 들어간 것을 빼는 길이다(D13)
		if (action == Action.ROLE_GRANT_PERMISSION && EVERYONE.contains(role.key()) && !p.visitor()) {
			throw deny(actor, Action.ROLE_DENIED, id, tried + " r9", request,
					new FieldErrors(Map.of("permission", "모두가 갖는 역할에는 방문자 권한만 줄 수 있습니다")));
		}
		if (!covers(actor, EnumSet.of(p))) {
			throw deny(actor, Action.ROLE_DENIED, id, tried + " r4", request,
					new Refused(HttpStatus.FORBIDDEN, "보유하지 않은 권한은 부여할 수 없습니다"));
		}
		return p;
	}

	@AdminPermission(Permission.ROLE_ASSIGN)
	@PostMapping("/api/admin/users/{id}/roles")
	@ApiResponse(responseCode = "204", description = "부여함(이미 있어도). 비활성 계정에도 준다", content = @Content)
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님·role_id 가 UUID 가 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "권한 없음·재인증 필요·자기 계정(R1)·superuser 역할(R3)·보유하지 않은 권한이 든 역할(R2)·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 사용자·역할·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "칸이 비었음·줄 수 없는 역할. errors.role_id", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> grantRole(@PathVariable UUID id, @RequestBody RoleRequest req, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		reauth.require(request);
		Role role = target(id, req, actor, Action.USER_GRANT_ROLE, request);
		jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING").params(id, role.id())
			.update();
		operationLog.record(actor, Action.USER_GRANT_ROLE, id.toString(), role.key(), ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	@AdminPermission(Permission.ROLE_ASSIGN)
	@PostMapping("/api/admin/users/{id}/roles/revoke")
	@ApiResponse(responseCode = "204", description = "뺌", content = @Content)
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님·role_id 가 UUID 가 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "권한 없음·재인증 필요·자기 계정(R1)·superuser 역할(R3)·보유하지 않은 권한이 든 역할(R2)·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 사용자·역할·그 역할을 갖고 있지 않음·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "마지막 활성 관리자의 superuser 역할", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "칸이 비었음. errors.role_id", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> revokeRole(@PathVariable UUID id, @RequestBody RoleRequest req, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		reauth.require(request);
		Role role = target(id, req, actor, Action.USER_REVOKE_ROLE, request);
		String notHeld = "그 사용자는 이 역할을 갖고 있지 않습니다";
		IntSupplier delete = () -> jdbc.sql("DELETE FROM user_roles WHERE user_id = ? AND role_id = ?")
			.params(id, role.id()).update();
		if (role.superuser()) {
			// 미리 「갖지 않음」을 읽지 않는다 — 잠금 아래 쓰기가 0행이면 GONE 이다(스펙 5절). 기록은 커밋·롤백 뒤
			switch (lastAdmin.run(id, delete)) {
				case REFUSED -> {
					operationLog.record(actor, Action.USER_DENIED, id.toString(),
							Action.USER_REVOKE_ROLE.key() + " " + role.key() + " last_admin", ClientIp.of(request));
					throw new Refused(HttpStatus.CONFLICT, "마지막 관리자의 역할은 회수할 수 없습니다");
				}
				case GONE -> throw new Refused(HttpStatus.NOT_FOUND, notHeld);
				// R3 을 지난 요청자는 superuser 라 닿지 않는다. 닿으면 성공으로 떨어지지 않고 500 으로 멈춘다(1-B2 최종 검토 M-1)
				case R6 -> throw new IllegalStateException("역할 빼기에서 R6 이 나왔다");
				case DONE -> {
				}
			}
		}
		else if (delete.getAsInt() == 0) {
			throw new Refused(HttpStatus.NOT_FOUND, notHeld);
		}
		operationLog.record(actor, Action.USER_REVOKE_ROLE, id.toString(), role.key(), ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	/**
	 * A-405 순서(스펙 6절): 칸 없음 422 → 사용자·역할 없음 404 → (부여만) 줄 수 없는 역할 422(셋 다 남기지 않음) → R1 → R3 → R2(셋은
	 * 남김). R3 은 R2 와 독립으로 먼저 본다. 대상이 비활성이어도 거부하지 않는다(Q18). 비교는 UUID 값끼리다.
	 */
	private Role target(UUID id, RoleRequest req, Actor actor, Action action, HttpServletRequest request) {
		if (req.roleId() == null) {
			throw new FieldErrors(Map.of("role_id", REQUIRED));
		}
		if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)").param(id).query(Boolean.class).single()) {
			throw new Refused(HttpStatus.NOT_FOUND, NOT_FOUND);
		}
		Role role = role(req.roleId());
		if (action == Action.USER_GRANT_ROLE && !role.assignable()) {
			throw new FieldErrors(Map.of("role_id", "줄 수 없는 역할입니다"));
		}
		String tried = action.key() + " " + role.key();
		if (id.equals(actor.id())) {
			throw deny(actor, Action.USER_DENIED, id, tried + " r1", request,
					new Refused(HttpStatus.FORBIDDEN, "자기 계정의 역할은 변경할 수 없습니다"));
		}
		if (role.superuser() && !actor.superuser()) {
			throw deny(actor, Action.USER_DENIED, id, tried + " r3", request,
					new Refused(HttpStatus.FORBIDDEN, "이 역할은 관리자만 부여할 수 있습니다"));
		}
		if (!covers(actor, permissionsOf(role.superuser(), role.perms()))) {
			throw deny(actor, Action.USER_DENIED, id, tried + " r2", request,
					new Refused(HttpStatus.FORBIDDEN, "보유하지 않은 권한이 포함된 역할입니다"));
		}
		return role;
	}

	private Role role(UUID id) {
		return jdbc.sql(ONE).param(id).query((rs, n) -> role(rs)).optional()
			.orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_FOUND));
	}

	private static Role role(ResultSet rs) throws SQLException {
		return new Role(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"),
				rs.getString("description"), rs.getBoolean("is_builtin"), rs.getBoolean("is_assignable"),
				rs.getBoolean("superuser"), List.of((String[]) rs.getArray("perms").getArray()));
	}

	/** 거절을 남기고 던질 예외를 돌려준다. summary 는 코드 상수·enum 키·DB 의 역할 키로만 만든다. */
	private RuntimeException deny(Actor actor, Action denied, UUID target, String summary, HttpServletRequest request,
			RuntimeException refusal) {
		operationLog.record(actor, denied, target.toString(), summary, ClientIp.of(request));
		return refusal;
	}
}
