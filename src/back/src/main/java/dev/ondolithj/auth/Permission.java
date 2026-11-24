package dev.ondolithj.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * 권한 목록. 코드가 정하고 마이그레이션이 심는다(ondolith D15 P1) — 관리 UI 로 늘리지 않는다.
 * 권한 검사에 쓰는 authority 문자열은 enum 이름(ADMIN_ACCESS)이고, DB·API 의 키는 key()(admin.access)다.
 */
public enum Permission {

	ADMIN_ACCESS("admin.access", false),

	/** 시스템 정보 조회(A-602). */
	SETTINGS_VIEW("settings.view", false),

	/** 사이트 설정 변경(A-201). */
	SETTINGS_UPDATE("settings.update", false),

	/** 작업 로그 열람(A-601). */
	LOG_VIEW("log.view", false),

	/** 사용자 목록(A-401). */
	USER_VIEW("user.view", false),

	/** 사용자 생성(A-402). */
	USER_CREATE("user.create", false),

	/** 사용자 상세·비활성·활성(A-402). */
	USER_UPDATE("user.update", false),

	/** 역할과 권한 구성 보기(A-403). */
	ROLE_VIEW("role.view", false),

	/** 사용자 역할 부여·빼기(A-405). */
	ROLE_ASSIGN("role.assign", false),

	/** 역할 권한 부여·빼기(A-404). */
	ROLE_MANAGE("role.manage", false),

	/** 페이지 목록(A-301). */
	PAGE_VIEW("page.view", false),

	/** 페이지 작성(A-302). */
	PAGE_CREATE("page.create", false),

	/** 페이지 상세·수정(A-302). */
	PAGE_UPDATE("page.update", false),

	/** 페이지 삭제(A-302). */
	PAGE_DELETE("page.delete", false),

	/** 페이지 발행·발행 취소(A-303). 발행된 페이지의 수정에도 요구한다(1-C1 스펙 17절 1). */
	PAGE_PUBLISH("page.publish", false);

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
