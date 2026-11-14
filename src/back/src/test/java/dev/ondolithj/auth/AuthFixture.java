package dev.ondolithj.auth;

import dev.ondolithj.Launcher;
import dev.ondolithj.TestHttp;
import dev.ondolithj.config.ConfigFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** 인증 시험 공통: 운영 컨텍스트 하나와 그 DB 에 계정을 만드는 도우미. 비밀번호는 누가 봐도 가짜다. */
final class AuthFixture implements AutoCloseable {

	static final String PASSWORD = "login-pass-4b2e";

	final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine");

	ConfigurableApplicationContext ctx;

	private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

	AuthFixture start(boolean secureCookies, String... args) throws Exception {
		pg.start();
		Path dir = Files.createTempDirectory("auth");
		ConfigFile config = new ConfigFile(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(),
				URI.create("http://site.ondolith.test"), secureCookies, ConfigFile.newSealKey(), dir.resolve("u"),
				dir.resolve("b"), Instant.now());
		List<String> all = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1"));
		all.addAll(List.of(args));
		ctx = Launcher.startRunning(config, all.toArray(String[]::new));
		return this;
	}

	TestHttp http() {
		return TestHttp.of(ctx);
	}

	Connection db() throws SQLException {
		return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
	}

	/** 계정을 만들고 역할을 준다(역할 키). */
	UUID user(String email, String... roles) throws SQLException {
		try (Connection c = db();
				PreparedStatement u = c.prepareStatement(
						"INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id")) {
			u.setString(1, email);
			u.setString(2, bcrypt.encode(PASSWORD));
			UUID id;
			try (ResultSet r = u.executeQuery()) {
				r.next();
				id = r.getObject(1, UUID.class);
			}
			for (String role : roles) {
				try (PreparedStatement g = c.prepareStatement(
						"INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE key = ?")) {
					g.setObject(1, id);
					g.setString(2, role);
					g.executeUpdate();
				}
			}
			return id;
		}
	}

	void sql(String sql, Object... params) throws SQLException {
		try (Connection c = db(); PreparedStatement s = c.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				s.setObject(i + 1, params[i]);
			}
			s.execute();
		}
	}

	long sessions() throws SQLException {
		try (Connection c = db(); ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM spring_session")) {
			r.next();
			return r.getLong(1);
		}
	}

	static String loginBody(String email, String password) {
		return JsonMapper.builder().build().writeValueAsString(java.util.Map.of("email", email, "password", password));
	}

	@Override
	public void close() {
		if (ctx != null) {
			ctx.close();
		}
		pg.stop();
	}
}
