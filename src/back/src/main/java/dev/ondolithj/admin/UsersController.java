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
import dev.ondolithj.shared.Password;
import dev.ondolithj.shared.Refused;
import dev.ondolithj.shared.SiteName;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-401 사용자 목록, A-402 생성·상세·비활성·활성(1-B2 스펙 4절). 기록기를 부르므로 @Transactional 을 달지 않는다.
 * 비활성·활성은 재인증 대상이다 — 본문 첫 줄이 reauth.require 이고, 창이 닫힌 요청은 아무것도 바꾸지 않고 기록하지 않는다.
 */
@RestController
class UsersController {

	private static final String PROBLEM = "application/problem+json";

	private static final String NOT_FOUND = "사용자를 찾을 수 없습니다";

	// R6·R2 거절의 detail
	private static final String DENIED = "권한이 없습니다";

	private static final int PAGE_SIZE = 50;

	// 전체 건수는 세지 않고 한 행 더 읽어 다음 쪽을 안다(원문 코드, D86 7.13). 역할 키는 같은 질의의 집계 — 질의는 행 수와 관계없이 하나다.
	// LIKE 의 기본 이스케이프 문자는 \ 다
	private static final String LIST = """
			SELECT u.id, u.email, u.display_name, u.is_active, u.created_at,
			       array_remove(array_agg(r.key ORDER BY r.key), NULL) AS roles
			FROM users u
			LEFT JOIN user_roles ur ON ur.user_id = u.id
			LEFT JOIN roles r ON r.id = ur.role_id
			WHERE :pattern = '' OR u.email ILIKE :pattern OR u.display_name ILIKE :pattern
			GROUP BY u.id
			ORDER BY u.created_at DESC, u.id
			LIMIT 51 OFFSET :offset""";

	private static final String ROLE_COLUMNS = "r.id, r.key, r.name, " + ActorQuery.SUPERUSER_ROLE + " AS is_superuser";

	// 기준 시각을 앞으로만 옮긴다 — 재활성화가 옛 세션을 살리지 않는다(스펙 4절, Q11)
	private static final String DEACTIVATE = """
			UPDATE users SET is_active = false,
			    sessions_valid_from = GREATEST(now(), sessions_valid_from + interval '1 microsecond'), updated_at = now()
			WHERE id = ?""";

	/** 고정 레코드 — 역할·활성 상태·기준 시각은 받지 않는다(D19, D86 2.5). 이메일은 검사 전에 앞뒤 공백을 뺀다. */
	record CreateUser(
			@NotBlank(message = "필수입니다") @Email(message = "이메일 형식이 아닙니다") @Size(max = 254, message = "254자 이하") String email,
			String displayName, String password) {

		CreateUser {
			email = email == null ? null : email.strip();
		}

		/** 비밀번호가 로그(DEBUG 의 요청 본문 등)에 새지 않게 가린다. */
		@Override
		public String toString() {
			return "CreateUser[email=" + email + ", displayName=" + displayName + "]";
		}
	}

	record Created(UUID id) {
	}

	record UserItem(UUID id, String email, String displayName, boolean isActive, List<String> roles, Instant createdAt) {
	}

	record UserPage(List<UserItem> items, int page, int pageSize, boolean hasNext) {
	}

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

	private final LastAdmin lastAdmin;

	private final ActorQuery actorQuery;

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

	UsersController(JdbcClient jdbc, OperationLog operationLog, Reauth reauth, LastAdmin lastAdmin, ActorQuery actorQuery) {
		this.jdbc = jdbc;
		this.operationLog = operationLog;
		this.reauth = reauth;
		this.lastAdmin = lastAdmin;
		this.actorQuery = actorQuery;
	}

	@AdminPermission(Permission.USER_VIEW)
	@GetMapping("/api/admin/users")
	@ApiResponse(responseCode = "200", description = "한 쪽(50행). q 는 이메일·표시 이름의 일부. 끝을 넘으면 빈 items")
	@ApiResponse(responseCode = "400", description = "page 가 정수가 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "user.view 권한 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "page 가 1 미만·q 가 200자 초과이거나 NUL 이 있음. errors.page·errors.q", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	UserPage list(@RequestParam(defaultValue = "1") int page, @RequestParam(required = false) String q) {
		String term = q == null ? "" : q.strip();
		if (page < 1) {
			throw new FieldErrors(Map.of("page", "1 이상"));
		}
		if (term.length() > 200) {
			throw new FieldErrors(Map.of("q", "200자 이하"));
		}
		// PostgreSQL text 는 NUL 을 받지 않아 바인드가 500 이 된다(1-B1 의 사이트 이름과 같은 교훈)
		if (term.indexOf('\0') >= 0) {
			throw new FieldErrors(Map.of("q", "쓸 수 없는 문자가 있습니다"));
		}
		String pattern = term.isEmpty() ? "" : "%" + term.replaceAll("[\\\\%_]", "\\\\$0") + "%";
		List<UserItem> rows = jdbc.sql(LIST).param("pattern", pattern)
			.param("offset", (page - 1L) * PAGE_SIZE) // int 곱은 큰 쪽 번호에서 넘친다
			.query((rs, n) -> new UserItem(rs.getObject("id", UUID.class), rs.getString("email"),
					rs.getString("display_name"), rs.getBoolean("is_active"),
					List.of((String[]) rs.getArray("roles").getArray()),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.list();
		boolean hasNext = rows.size() > PAGE_SIZE;
		return new UserPage(hasNext ? rows.subList(0, PAGE_SIZE) : rows, page, PAGE_SIZE, hasNext);
	}

	@AdminPermission(Permission.USER_CREATE)
	@PostMapping("/api/admin/users")
	@ApiResponse(responseCode = "201", description = "만듦. 역할 없음, 이메일은 인증된 것으로 둔다")
	@ApiResponse(responseCode = "400", description = "본문이 JSON 이 아님", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "user.create 권한 없음·superuser 아님(1-E 전, Q21)·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "입력 오류. errors.email·errors.display_name·errors.password", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Created> create(@Valid @RequestBody CreateUser req, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		// Q21: 만든 사람이 초기 비밀번호를 안다 — 더 넓은 부여자가 그 계정에 역할을 주면 만든 사람의 권한이 오른다.
		// ponytail: 1-E 에서 계정 주인이 메일 링크로 비밀번호를 정하게 되면 이 제한을 풀고 user.create 권한으로 돌린다
		if (!actor.superuser()) {
			operationLog.record(actor, Action.USER_DENIED, null, Action.USER_CREATE.key() + " superuser_only",
					ClientIp.of(request));
			throw new Refused(HttpStatus.FORBIDDEN, "1-E 전에는 관리자만 계정을 만들 수 있습니다");
		}
		Map<String, String> errors = new HashMap<>();
		SiteName.error(req.displayName(), 50).ifPresent(m -> errors.put("display_name", m));
		Password.error(req.password()).ifPresent(m -> errors.put("password", m));
		if (!errors.isEmpty()) {
			throw new FieldErrors(errors);
		}
		UUID id;
		// 먼저 세지 않는다 — 동시 생성 둘이 다 지나간다. UNIQUE 가 막는다
		try {
			id = jdbc.sql("INSERT INTO users (email, display_name, password_hash, email_verified_at) VALUES (?, ?, ?, now()) RETURNING id")
				.params(req.email().toLowerCase(Locale.ROOT), SiteName.strip(req.displayName()), encoder.encode(req.password()))
				.query(UUID.class)
				.single();
		}
		catch (DuplicateKeyException e) {
			throw new Refused(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다");
		}
		operationLog.record(actor, Action.USER_CREATE, id.toString(), "", ClientIp.of(request));
		return ResponseEntity.status(HttpStatus.CREATED).body(new Created(id));
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
		// 위에서 읽은 보유 여부로 건너뛰지 않는다 — 잠금 아래서 다시 읽는다(스펙 5절). 기록은 커밋·롤백 뒤
		switch (lastAdmin.run(id, actor.superuser(), () -> jdbc.sql(DEACTIVATE).param(id).update())) {
			case R6 -> deny(actor, Action.USER_DEACTIVATE, id, "r6", DENIED, request);
			case REFUSED -> {
				operationLog.record(actor, Action.USER_DENIED, id.toString(),
						Action.USER_DEACTIVATE.key() + " last_admin", ClientIp.of(request));
				throw new Refused(HttpStatus.CONFLICT, "마지막 관리자는 비활성화·삭제할 수 없습니다");
			}
			case GONE -> throw new Refused(HttpStatus.NOT_FOUND, NOT_FOUND);
			case DONE -> {
			}
		}
		operationLog.record(actor, Action.USER_DEACTIVATE, id.toString(), "", ClientIp.of(request));
		return ResponseEntity.noContent().build();
	}

	@AdminPermission(Permission.USER_UPDATE)
	@PostMapping("/api/admin/users/{id}/activate")
	@ApiResponse(responseCode = "204", description = "활성화함(이미 활성이어도). 옛 세션은 살아나지 않는다", content = @Content)
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "권한 없음·재인증 필요·자기 계정·superuser 보유자(R6)·요청자가 덮지 못하는 권한을 가진 계정(R2, Q20)·CSRF 토큰 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "없는 사용자·UUID 가 아닌 경로", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<Void> activate(@PathVariable UUID id, @AuthenticationPrincipal Actor actor,
			HttpServletRequest request) {
		reauth.require(request);
		refuse(id, actor, Action.USER_ACTIVATE, request);
		// R2(Q20): 요청자가 대상의 유효 권한을 모두 덮어야 다시 연다. 비활성 계정이라 활성 여부를 보지 않고 읽는다.
		// 이 판정 뒤 대상이 역할을 받는 경쟁은 「활성화 먼저, 부여 나중」과 끝 상태가 같아 WHERE 에 넣지 않는다
		if (!RolesController.covers(actor, actorQuery.permissions(id)
			.orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_FOUND)))) {
			deny(actor, Action.USER_ACTIVATE, id, "r2", DENIED, request);
		}
		// R6 을 쓰기 문장 안에서 다시 본다 — 위의 판정 뒤에 대상이 보유자가 되는 틈을 닫는다(잠금이 없는 쪽)
		int n = jdbc.sql("UPDATE users u SET is_active = true, updated_at = now() WHERE u.id = ? AND (? OR NOT "
				+ ActorQuery.HOLDS_SUPERUSER + ")").params(id, actor.superuser()).update();
		if (n == 0) {
			if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)").param(id).query(Boolean.class).single()) {
				deny(actor, Action.USER_ACTIVATE, id, "r6", DENIED, request);
			}
			throw new Refused(HttpStatus.NOT_FOUND, NOT_FOUND);
		}
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
		// R6: 비활성·활성 둘 다(Q19) — 막아 둔 superuser 보유자를 비-superuser 가 다시 열지 못하게.
		// 이 판정 뒤에 대상이 보유자가 될 수 있어 쓰기 때 한 번 더 본다(비활성은 LastAdmin 잠금 아래, 활성은 UPDATE 의 WHERE)
		if (holder && !actor.superuser()) {
			deny(actor, action, id, "r6", DENIED, request);
		}
	}

	private void deny(Actor actor, Action action, UUID id, String rule, String detail, HttpServletRequest request) {
		operationLog.record(actor, Action.USER_DENIED, id.toString(), action.key() + " " + rule, ClientIp.of(request));
		throw new Refused(HttpStatus.FORBIDDEN, detail);
	}
}
