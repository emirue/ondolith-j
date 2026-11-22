package dev.ondolithj.admin;

import dev.ondolithj.auth.AdminPermission;
import dev.ondolithj.auth.Permission;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-602 시스템 정보(1-B1 스펙 5절). 칸은 이것뿐이다 — Flyway 정보 객체를 그대로 내지 않는다(installed_by 는 DB 계정 이름).
 * DSN·호스트·포트·DB 계정은 어디에도 없다. DB 연결은 server_version 으로 보인다.
 */
@RestController
class SystemController {

	private static final String PROBLEM = "application/problem+json";

	record MigrationRow(String version, String description, Instant installedOn, boolean success) {
	}

	record Database(String serverVersion) {
	}

	record SystemInfo(String version, List<MigrationRow> migrations, int pendingCount, Database database) {
	}

	private final Flyway flyway;

	private final JdbcClient jdbc;

	private final String version;

	SystemController(Flyway flyway, JdbcClient jdbc, ObjectProvider<BuildProperties> build) {
		this.flyway = flyway;
		this.jdbc = jdbc;
		BuildProperties b = build.getIfAvailable();
		this.version = b == null ? "dev" : b.getVersion();
	}

	@AdminPermission(Permission.SETTINGS_VIEW)
	@GetMapping("/api/admin/system")
	@ApiResponse(responseCode = "200", description = "jar 버전·적용된 마이그레이션·대기 수·DB 서버 판")
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "settings.view 권한 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	SystemInfo info() {
		MigrationInfoService info = flyway.info();
		// 반복 마이그레이션(R__)은 버전이 없다 — 지금은 쓰지 않는다
		List<MigrationRow> rows = Arrays.stream(info.applied())
			.map(m -> new MigrationRow(m.getVersion().getVersion(), m.getDescription(), m.getInstalledOn().toInstant(),
					!m.getState().isFailed()))
			.toList();
		String server = jdbc.sql("SHOW server_version").query(String.class).single();
		return new SystemInfo(version, rows, info.pending().length, new Database(server));
	}
}
