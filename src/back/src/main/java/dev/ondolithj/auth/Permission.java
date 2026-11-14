package dev.ondolithj.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * 권한 목록. 코드가 정하고 마이그레이션이 심는다(ondolith D15 P1) — 관리 UI 로 늘리지 않는다.
 * 권한 검사에 쓰는 authority 문자열은 enum 이름(ADMIN_ACCESS)이고, DB·API 의 키는 key()(admin.access)다.
 */
public enum Permission {

	ADMIN_ACCESS("admin.access");

	private final String key;

	Permission(String key) {
		this.key = key;
	}

	public String key() {
		return key;
	}

	public static Optional<Permission> ofKey(String key) {
		return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
	}
}
