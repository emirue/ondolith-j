package dev.ondolithj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MigrationTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine");

	Flyway flyway(String... locations) {
		return Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).locations(locations)
			.cleanDisabled(false).load();
	}

	List<String> rows(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				ResultSet r = c.createStatement().executeQuery(sql)) {
			List<String> out = new ArrayList<>();
			while (r.next()) {
				out.add(r.getString(1));
			}
			return out;
		}
	}

	void exec(String sql, Object... params) throws SQLException {
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				PreparedStatement s = c.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				s.setObject(i + 1, params[i]);
			}
			s.execute();
		}
	}

	@Test
	void V1_이_네_표와_내장_역할을_만든다() throws SQLException {
		Flyway f = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
			.locations("classpath:db/migration").target("1").cleanDisabled(false).load();
		f.clean();
		f.migrate();
		assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'"))
			.containsExactlyInAnyOrder("users", "roles", "user_roles", "settings");
		assertThat(rows("SELECT key FROM roles ORDER BY key")).containsExactly("admin", "anonymous", "editor", "member", "operator");
		assertThat(rows("SELECT key FROM roles WHERE is_superuser")).containsExactly("admin");
	}

	@Test
	void 실패한_마이그레이션은_원복된다() throws SQLException {
		Flyway f = flyway("classpath:db/migration", "classpath:db/failing");
		f.clean();
		assertThatThrownBy(f::migrate).isInstanceOf(FlywayException.class);
		assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_name = 'half_done'")).isEmpty();
		assertThat(rows("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")).containsExactly("1", "2", "3", "4");
	}

	@Test
	void V2_가_권한과_세션_표를_만들고_admin_access_를_심는다() throws SQLException {
		Flyway f = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
			.locations("classpath:db/migration").target("2").cleanDisabled(false).load();
		f.clean();
		f.migrate();
		assertThat(rows("SELECT key FROM permissions")).containsExactly("admin.access");
		assertThat(rows("SELECT r.key FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
				+ "JOIN permissions p ON p.id = rp.permission_id WHERE p.key = 'admin.access' ORDER BY r.key"))
			.containsExactly("editor", "operator");
		assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
				+ "AND table_name IN ('spring_session', 'spring_session_attributes') ORDER BY table_name"))
			.containsExactly("spring_session", "spring_session_attributes");
		assertThatThrownBy(() -> rows("INSERT INTO permissions (key) VALUES ('Admin') RETURNING key"))
			.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> rows("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id "
				+ "FROM roles r, permissions p WHERE r.key = 'editor' AND p.key = 'admin.access' RETURNING id"))
			.isInstanceOf(SQLException.class);
	}

	String permsOf(String role) {
		return "SELECT p.key FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
				+ "JOIN permissions p ON p.id = rp.permission_id WHERE r.key = '" + role + "'";
	}

	@Test
	void V3_가_관리자_권한_셋을_심고_operator_에만_준다() throws SQLException {
		Flyway f = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
			.locations("classpath:db/migration").target("3").cleanDisabled(false).load();
		f.clean();
		f.migrate();
		assertThat(rows("SELECT key FROM permissions"))
			.containsExactlyInAnyOrder("admin.access", "settings.view", "settings.update", "log.view");
		assertThat(rows(permsOf("operator")))
			.containsExactlyInAnyOrder("admin.access", "settings.view", "settings.update", "log.view");
		assertThat(rows(permsOf("editor"))).containsExactly("admin.access");
		for (String role : List.of("admin", "member", "anonymous")) {
			assertThat(rows(permsOf(role))).as(role).isEmpty();
		}
	}

	@Test
	void V4_가_사용자_역할_권한_여섯을_심고_operator_에는_보기_둘만_준다() throws SQLException {
		Flyway f = flyway("classpath:db/migration");
		f.clean();
		f.migrate();
		assertThat(rows("SELECT key FROM permissions")).containsExactlyInAnyOrder("admin.access", "settings.view",
				"settings.update", "log.view", "user.view", "user.create", "user.update", "role.view", "role.assign",
				"role.manage");
		assertThat(rows(permsOf("operator"))).containsExactlyInAnyOrder("admin.access", "settings.view",
				"settings.update", "log.view", "user.view", "role.view");
		assertThat(rows(permsOf("editor"))).containsExactly("admin.access");
		for (String role : List.of("admin", "member", "anonymous")) {
			assertThat(rows(permsOf(role))).as(role).isEmpty();
		}
	}

	@Test
	void V3_의_작업_로그는_고치거나_지울_수_없다_actor_만_NULL_로_바꾸는_UPDATE_는_사용자가_있어도_통과한다_원문과_같은_한계()
			throws SQLException {
		Flyway f = flyway("classpath:db/migration");
		f.clean();
		f.migrate();
		UUID user = UUID.fromString(rows("INSERT INTO users (email, password_hash) VALUES ('oplog@ondolith.test', 'x') RETURNING id").get(0));
		exec("INSERT INTO operation_logs (actor_user_id, actor_email, action, target_type, target_id) "
				+ "VALUES (?, 'oplog@ondolith.test', 'auth.login', 'user', ?)", user, user.toString());
		exec("INSERT INTO operation_logs (actor_user_id, actor_email, action, target_type, target_id) "
				+ "VALUES (?, 'oplog@ondolith.test', 'auth.login', 'user', 'limit')", user);
		assertThatThrownBy(() -> exec("UPDATE operation_logs SET summary = 'x'")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> exec("DELETE FROM operation_logs")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> exec("TRUNCATE operation_logs")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> exec("UPDATE operation_logs SET actor_user_id = NULL, summary = 'x'"))
			.isInstanceOf(SQLException.class);
		// 사용자가 남아 있는데도 actor 만 비우는 문장은 WHEN 을 지난다(M-5). 이메일 스냅샷은 그대로다
		exec("UPDATE operation_logs SET actor_user_id = NULL WHERE target_id = 'limit'");
		assertThat(rows("SELECT actor_user_id FROM operation_logs WHERE target_id = 'limit'")).singleElement().isNull();
		assertThat(rows("SELECT actor_email FROM operation_logs WHERE target_id = 'limit'"))
			.containsExactly("oplog@ondolith.test");

		exec("DELETE FROM users WHERE id = ?", user);
		assertThat(rows("SELECT actor_user_id FROM operation_logs WHERE target_id <> 'limit'")).singleElement().isNull();
		assertThat(rows("SELECT actor_email FROM operation_logs WHERE target_id <> 'limit'"))
			.containsExactly("oplog@ondolith.test");
		assertThatThrownBy(() -> exec("UPDATE operation_logs SET summary = 'x'")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> exec("UPDATE operation_logs SET actor_user_id = NULL")).isInstanceOf(SQLException.class);
	}

	@Test
	void V3_의_동작과_대상_이름은_소문자_형식만_받는다() throws SQLException {
		Flyway f = flyway("classpath:db/migration");
		f.clean();
		f.migrate();
		exec("INSERT INTO operation_logs (action, target_type) VALUES ('auth.login', 'user')");
		assertThatThrownBy(() -> exec("INSERT INTO operation_logs (action, target_type) VALUES ('Auth.Login', 'user')"))
			.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> exec("INSERT INTO operation_logs (action, target_type) VALUES ('auth.login', 'User')"))
			.isInstanceOf(SQLException.class);
	}
}
