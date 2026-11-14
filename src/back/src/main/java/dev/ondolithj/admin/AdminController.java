package dev.ondolithj.admin;

import org.springframework.http.ProblemDetail;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import dev.ondolithj.auth.Permission;
import dev.ondolithj.auth.RequiresPermission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 트리의 문(스펙 6절). 1-B 가 셸 내용을 채운다. /api/admin/** 는 모두 admin.access 를 요구한다. */
@RestController
@RequiresPermission(Permission.ADMIN_ACCESS)
class AdminController {

	private static final String PROBLEM = "application/problem+json";

	@GetMapping("/api/admin")
	@ApiResponse(responseCode = "200", description = "관리자 셸(1-B 가 채운다)")
	@ApiResponse(responseCode = "401", description = "로그인이 필요함", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "admin.access 권한 없음", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "관리자 경로 요청이 너무 많음. Retry-After", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	Map<String, Object> shell() {
		return Map.of();
	}
}
