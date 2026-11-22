package dev.ondolithj.shared;

import dev.ondolithj.auth.Reauth;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 오류 응답은 Problem Details. 폼 칸 오류는 422 + errors{칸(snake_case): 메시지}.
 * 처리하지 못한 예외는 500 이고 본문에 예외 이름·메시지·스택을 내보내지 않는다(스펙 3절).
 */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

	private static final URI REAUTH_REQUIRED = URI.create("urn:ondolithj:problem:reauth-required");

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError f : ex.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(snake(f.getField()), f.getDefaultMessage());
		}
		return ResponseEntity.unprocessableContent().body(unprocessable(errors));
	}

	@ExceptionHandler(FieldErrors.class)
	ResponseEntity<ProblemDetail> fieldErrors(FieldErrors ex) {
		return ResponseEntity.unprocessableContent().body(unprocessable(ex.errors()));
	}

	/** 경로 변수가 형식에 맞지 않으면(UUID 가 아님) 그런 자원이 없는 것이다 — 404(1-B2 스펙 7절, D86 2.7). 질의 매개변수는 그대로 400. */
	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (ex instanceof MethodArgumentTypeMismatchException m && m.getParameter().hasParameterAnnotation(PathVariable.class)) {
			return notFound();
		}
		return super.handleTypeMismatch(ex, headers, status, request);
	}

	/** 공백뿐인 경로 마디(%20)는 UUID 변환이 null 을 내 「변환 뒤 없음」이 된다 — 이것도 형식이 틀린 경로다. */
	@Override
	protected ResponseEntity<Object> handleMissingPathVariable(MissingPathVariableException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (ex.isMissingAfterConversion()) {
			return notFound();
		}
		return super.handleMissingPathVariable(ex, headers, status, request);
	}

	private static ResponseEntity<Object> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "찾을 수 없습니다"));
	}

	@ExceptionHandler(Refused.class)
	ResponseEntity<ProblemDetail> refused(Refused ex) {
		return ResponseEntity.status(ex.status()).body(ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage()));
	}

	/** 재인증 전용 403(1-B2 스펙 3.2). 프런트는 type 만 보고 가른다. 다른 403 은 about:blank 그대로. */
	@ExceptionHandler(Reauth.Required.class)
	ResponseEntity<ProblemDetail> reauthRequired() {
		ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "비밀번호를 다시 입력하세요");
		p.setType(REAUTH_REQUIRED);
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(p);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> unexpected(Exception ex) throws Exception {
		// 메서드 보안의 거부는 컨트롤러 호출 안에서 예외로 나온다. 같은 예외를 다시 던지면 Spring 이 조용히 넘기고,
		// 보안 필터가 401(익명)·403(권한 없음)으로 바꾼다(1-A 스펙 9절)
		if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
			throw ex;
		}
		log.error("처리하지 못한 예외", ex);
		return ResponseEntity.internalServerError()
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 났습니다. 서버 로그를 확인하세요"));
	}

	private static ProblemDetail unprocessable(Map<String, String> errors) {
		ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "입력이 올바르지 않습니다");
		p.setProperty("errors", errors);
		return p;
	}

	static String snake(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
	}
}
