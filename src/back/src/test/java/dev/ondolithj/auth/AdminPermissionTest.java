package dev.ondolithj.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** @AdminPermission(X) 는 admin.access 와 X 를 함께 요구한다(스펙 3절). 처리기는 fixture.guard.AdminProbe. */
class AdminPermissionTest {

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false, "--spring.main.sources=fixture.guard.AdminProbeConfig");
		f.sql("INSERT INTO roles (key, name) VALUES ('probe', '시험')");
		f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r, permissions p "
				+ "WHERE r.key = 'probe' AND p.key = 'settings.view'");
		f.user("probe@ondolith.test", "probe");
		f.user("editor@ondolith.test", "editor");
		f.user("operator@ondolith.test", "operator");
		f.user("admin@ondolith.test", "admin");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	int status(String email) throws Exception {
		return f.loginAs(email).get("/api/admin/probe").statusCode();
	}

	@Test
	void 그_권한만_있고_admin_access_가_없으면_403() throws Exception {
		assertThat(status("probe@ondolith.test")).isEqualTo(403);
	}

	@Test
	void admin_access_만_있고_그_권한이_없으면_403() throws Exception {
		assertThat(status("editor@ondolith.test")).isEqualTo(403);
	}

	@Test
	void 둘_다_있으면_200() throws Exception {
		assertThat(status("operator@ondolith.test")).isEqualTo(200);
		assertThat(status("admin@ondolith.test")).isEqualTo(200);
	}

	@Test
	void 익명은_401() throws Exception {
		assertThat(f.http().get("/api/admin/probe").statusCode()).isEqualTo(401);
	}
}
