package dev.ondolithj.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * 권한 목록. 코드가 정하고 마이그레이션이 심는다(ondolith D15 P1) — 관리 UI 로 늘리지 않는다.
 * 권한 검사에 쓰는 authority 문자열은 enum 이름(ADMIN_ACCESS)이고, DB·API 의 키는 key()(admin.access)다.
 */
public enum Permission {

	ADMIN_ACCESS("admin.access", false);

	private final String key;

	private final boolean visitor;

	Permission(String key, boolean visitor) {
		this.key = key;
		this.visitor = visitor;
	}

	/**
	 * 방문자 권한(ondolith D15 R9: post.read·post.write·comment.write). 모두가 갖는 역할(anonymous·member)은 이것만 받는다 —
	 * 거기 관리 권한이 들어가면 가입한 누구나 그 권한을 갖는다. 1-A 에는 방문자 권한이 없다(게시판은 Phase 2).
	 */
	public boolean visitor() {
		return visitor;
	}

	public String key() {
		return key;
	}

	public static Optional<Permission> ofKey(String key) {
		return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
	}
}
