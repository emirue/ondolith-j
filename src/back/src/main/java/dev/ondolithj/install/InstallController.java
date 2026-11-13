package dev.ondolithj.install;

import dev.ondolithj.shared.FieldErrors;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/install — 설치 컨텍스트에만 있다. */
@RestController
class InstallController {

	private static final String PROBLEM = "application/problem+json";

	private final InstallService service;

	InstallController(InstallService service) {
		this.service = service;
	}

	/** 응답 코드는 아래 switch 와 같아야 한다(OpenApiFileTest 가 문서를 확인한다). 애너테이션은 실행 jar 에 없어도 무시된다. */
	@PostMapping("/api/install")
	@ApiResponse(responseCode = "202", description = "설치 완료. 운영 모드로 바뀐다", content = @Content)
	@ApiResponse(responseCode = "400", description = "DB 접속 실패·이미 사용 중인 DB·마이그레이션 실패",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	// ponytail: CSRF 거부는 보안 필터가 내서 Boot 기본 오류 JSON 이다. Phase 1 에서 보안 설정을 바꿀 때 Problem Details 로 맞춘다
	@ApiResponse(responseCode = "403", description = "CSRF 토큰 없음", content = @Content)
	@ApiResponse(responseCode = "404", description = "이미 설치됨",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "다른 설치 요청이 진행 중",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "입력 오류. errors 에 칸(snake_case)별 메시지",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "500", description = "설정 파일을 쓸 수 없음 등",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<?> install(@Valid @RequestBody InstallRequest req) {
		Map<String, String> errors = req.check();
		if (!errors.isEmpty()) {
			throw new FieldErrors(errors);
		}
		return switch (service.install(req)) {
			case InstallOutcome.Done d -> ResponseEntity.accepted().build();
			case InstallOutcome.ConfigNotWritable c -> problem(HttpStatus.INTERNAL_SERVER_ERROR, c.detail());
			case InstallOutcome.DbFailed f -> problem(HttpStatus.BAD_REQUEST, f.detail());
			case InstallOutcome.DbInUse u -> problem(HttpStatus.BAD_REQUEST, u.evidence());
			case InstallOutcome.MigrationFailed m -> problem(HttpStatus.BAD_REQUEST, m.detail());
			case InstallOutcome.Busy b -> problem(HttpStatus.CONFLICT, "다른 설치 요청이 진행 중입니다");
			case InstallOutcome.AlreadyInstalled a -> problem(HttpStatus.NOT_FOUND, "없는 경로입니다");
		};
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
		return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
	}
}
