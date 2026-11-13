package dev.ondolithj.install;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 설치 입력(ondolith D19 P-001). 역할·설정 파일 경로·마이그레이션 버전·콘솔 토큰은 받지 않는다.
 * db_host 는 호스트 이름·IP 문자만 허용한다 — JDBC URL 에 드라이버 옵션을 끼워 넣지 못하게.
 */
public record InstallRequest(
		@NotBlank(message = "필수입니다") @Size(max = 255, message = "255자 이하") @Pattern(regexp = "[A-Za-z0-9.:\\[\\]-]+", message = "호스트 이름 또는 IP 주소만") String dbHost,
		@Min(value = 1, message = "1~65535") @Max(value = 65535, message = "1~65535") Integer dbPort,
		@NotBlank(message = "필수입니다") @Size(max = 63, message = "63자 이하") String dbUser,
		@Size(max = 255, message = "255자 이하") String dbPassword,
		@NotBlank(message = "필수입니다") @Size(max = 63, message = "63자 이하") String dbName,
		@NotNull(message = "필수입니다") @Pattern(regexp = "disable|require|verify-ca|verify-full", message = "disable·require·verify-ca·verify-full 중 하나") String dbSslmode,
		@NotBlank(message = "필수입니다") @Size(max = 100, message = "100자 이하") String siteName,
		@Pattern(regexp = "cms|shop", message = "cms 또는 shop") String siteType,
		@NotBlank(message = "필수입니다") @Size(max = 2048, message = "2048자 이하") @Pattern(regexp = "https?://[^\\s/?#]+[^\\s]*", message = "http·https 절대 주소") String siteUrl,
		@NotBlank(message = "필수입니다") @Email(message = "이메일 형식이 아닙니다") @Size(max = 254, message = "254자 이하") String adminEmail,
		@NotNull(message = "필수입니다") @Size(min = 10, message = "10자 이상") String adminPassword,
		@NotNull(message = "필수입니다") String adminPasswordConfirm) {

	public int port() {
		return dbPort == null ? 5432 : dbPort;
	}

	public String password() {
		return dbPassword == null ? "" : dbPassword;
	}

	public String type() {
		return siteType == null ? "cms" : siteType;
	}

	/** 애너테이션으로 못 하는 검사. 칸 이름(snake_case) → 메시지. */
	Map<String, String> check() {
		Map<String, String> e = new LinkedHashMap<>();
		if (adminPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
			e.put("admin_password", "72바이트 이하(한글은 약 24자)");
		}
		if (!adminPassword.equals(adminPasswordConfirm)) {
			e.put("admin_password_confirm", "비밀번호 확인이 다릅니다");
		}
		return e;
	}
}
