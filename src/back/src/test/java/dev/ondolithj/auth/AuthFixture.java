package dev.ondolithj.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.Launcher;
import dev.ondolithj.TestHttp;
import dev.ondolithj.config.ConfigFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** 인증 시험 공통: 운영 컨텍스트 하나와 그 DB 에 계정을 만드는 도우미. 비밀번호는 누가 봐도 가짜다. */
public final class AuthFixture implements AutoCloseable {

	public static final String PASSWORD = "login-pass-4b2e";

	// 기본 계정·비밀번호 test 는 시험 이메일(…@ondolith.test)에 들어 있어 「DB 계정·비밀번호 없음」 단언이 거짓 실패한다
	public final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine").withUsername("fixture_owner")
		.withPassword("pg-pass-2b7e");

	public ConfigurableApplicationContext ctx;

	private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

	/** 횟수 제한을 보지 않는 시험용: 한도를 크게 준다(같은 계정으로 1분에 여러 번 로그인한다). extra 는 그 뒤에 붙인다. */
	public AuthFixture start(boolean secureCookies, String... extra) throws Exception {
		List<String> args = new ArrayList<>(List.of("--ondolithj.rate-limit.login-ip=100000",
				"--ondolithj.rate-limit.login-account=100000", "--ondolithj.rate-limit.admin-ip=100000"));
		args.addAll(List.of(extra));
		return startWith(secureCookies, args.toArray(String[]::new));
	}

	/** 횟수 제한 시험용: 주어진 인자만(없으면 기본 한도). */
	AuthFixture startWith(boolean secureCookies, String... args) throws Exception {
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

	public TestHttp http() {
		return TestHttp.of(ctx);
	}

	public Connection db() throws SQLException {
		return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
	}

	/** 계정을 만들고 역할을 준다(역할 키). */
	public UUID user(String email, String... roles) throws SQLException {
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

	public void sql(String sql, Object... params) throws SQLException {
		try (Connection c = db(); PreparedStatement s = c.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				s.setObject(i + 1, params[i]);
			}
			s.execute();
		}
	}

	/** 로그인하고(204 를 단언) 그 쿠키를 가진 클라이언트를 돌려준다. */
	public TestHttp loginAs(String email) throws Exception {
		TestHttp http = http();
		assertThat(http.post("/api/auth/login", loginBody(email, PASSWORD)).statusCode()).isEqualTo(204);
		return http;
	}

	/** 쿠키의 세션에 재인증 시각을 직접 넣는다(null 이면 지운다) — 창을 닫거나 다른 타입·미래 값을 시험한다. */
	public void reauthAt(TestHttp http, Object value) {
		@SuppressWarnings("unchecked")
		SessionRepository<Session> repo = ctx.getBean(SessionRepository.class);
		Session s = repo.findById(new String(Base64.getDecoder().decode(http.cookie("SESSION")), StandardCharsets.UTF_8));
		if (value == null) {
			s.removeAttribute(Reauth.AT);
		}
		else {
			s.setAttribute(Reauth.AT, value);
		}
		repo.save(s);
	}

	long sessions() throws SQLException {
		try (Connection c = db(); ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM spring_session")) {
			r.next();
			return r.getLong(1);
		}
	}

	public static String loginBody(String email, String password) {
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
