package dev.ondolithj.install;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InstallServiceTest {

	static InstallRequest withDbPassword(String pw) {
		return new InstallRequest("127.0.0.1", 5432, "app", pw, "db", "disable", "사이트", "cms", "http://site.ondolith.test",
				"admin@ondolith.test", "admin-pass-1", "admin-pass-1");
	}

	@Test
	void 드라이버_메시지에_되풀이된_DB_비밀번호를_모두_가린다() {
		String msg = "FATAL: password=pg-pass-1 rejected (pg-pass-1)";
		assertThat(InstallService.scrub(msg, withDbPassword("pg-pass-1"))).isEqualTo("FATAL: password=*** rejected (***)");
	}

	@Test
	void 비밀번호가_비어_있으면_메시지를_그대로_둔다() {
		assertThat(InstallService.scrub("FATAL: no password", withDbPassword(null))).isEqualTo("FATAL: no password");
	}

	@Test
	void 메시지가_없으면_빈_문자열() {
		assertThat(InstallService.scrub(null, withDbPassword("pg-pass-1"))).isEmpty();
	}
}
