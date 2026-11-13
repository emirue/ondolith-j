package dev.ondolithj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

	@Test
	void V1_이_네_표와_내장_역할을_만든다() throws SQLException {
		Flyway f = flyway("classpath:db/migration");
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
		assertThat(rows("SELECT version FROM flyway_schema_history WHERE success")).containsExactly("1");
	}
}
