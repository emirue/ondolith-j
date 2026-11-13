package dev.ondolithj.install;

import dev.ondolithj.shared.FieldErrors;
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

	private final InstallService service;

	InstallController(InstallService service) {
		this.service = service;
	}

	@PostMapping("/api/install")
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
