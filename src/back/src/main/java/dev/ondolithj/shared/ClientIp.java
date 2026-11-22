package dev.ondolithj.shared;

import jakarta.servlet.http.HttpServletRequest;

/** 클라이언트 IP 한 곳(1-B1 스펙 4.4). 로그인 횟수 제한·관리자 횟수 제한·작업 로그가 모두 이것을 부른다. */
public final class ClientIp {

	private ClientIp() {
	}

	// ponytail: CloudFront 뒤의 실제 IP 는 Phase 4 배포 전에 믿을 프록시를 정해 여기 한 곳만 고친다. X-Forwarded-For 는 믿지 않는다.
	public static String of(HttpServletRequest request) {
		return request.getRemoteAddr();
	}
}
