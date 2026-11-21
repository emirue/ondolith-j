package dev.ondolithj.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.userdetails.User;

/** 비밀번호 대조용 계정. 같은 질의로 읽은 sessions_valid_from 이 로그인 세션의 authAt 이 된다(D15 5.4). */
class LoginUser extends User {

	private final UUID id;

	private final Instant validFrom;

	LoginUser(UUID id, String email, String passwordHash, Instant validFrom) {
		super(email, passwordHash, List.of());
		this.id = id;
		this.validFrom = validFrom;
	}

	UUID id() {
		return id;
	}

	Instant validFrom() {
		return validFrom;
	}
}
