package dev.ondolithj.install;

import dev.ondolithj.config.ConfigFile;

/** 설치 결과. InstallController 가 HTTP 상태로 바꾼다. */
sealed interface InstallOutcome {

	/** 202. 운영 컨텍스트로 바꿀 설정. 전환은 컨트롤러가 응답을 다 보낸 뒤에 요청한다. */
	record Done(ConfigFile config) implements InstallOutcome {
	}

	/** 500. 설정 파일 자리에 쓸 수 없음(경로 + OS 오류 원문). */
	record ConfigNotWritable(String detail) implements InstallOutcome {
	}

	/** 400. DB 접속·질의 실패(드라이버 원문, 비밀번호 가림). */
	record DbFailed(String detail) implements InstallOutcome {
	}

	/** 400. 이미 쓰는 DB(FR-108). */
	record DbInUse(String evidence) implements InstallOutcome {
	}

	/** 400. 마이그레이션 실패(실패한 파일과 원문). 트랜잭션으로 원복된다. */
	record MigrationFailed(String detail) implements InstallOutcome {
	}

	/** 409. 다른 설치 요청이 진행 중. */
	record Busy() implements InstallOutcome {
	}

	/** 404. 설치가 이미 끝났다. */
	record AlreadyInstalled() implements InstallOutcome {
	}
}
