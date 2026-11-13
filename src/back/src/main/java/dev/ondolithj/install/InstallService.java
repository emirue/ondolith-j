package dev.ondolithj.install;

import dev.ondolithj.config.ConfigFile;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 설치 처리(스펙 2.2 「처리 순서」). DB 를 바꾸기 전에 거부할 것은 모두 거부한다.
 * 설치 컨텍스트에는 JPA 가 없으므로 JDBC 로 직접 다룬다.
 */
@Service
class InstallService {

	private static final Logger log = LoggerFactory.getLogger(InstallService.class);

	private final InstallTarget target;

	private final ContextSwitch contextSwitch;

	private final ReentrantLock lock = new ReentrantLock();

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

	private volatile boolean done;

	InstallService(InstallTarget target, ContextSwitch contextSwitch) {
		this.target = target;
		this.contextSwitch = contextSwitch;
	}

	InstallOutcome install(InstallRequest req) {
		if (done) {
			return new InstallOutcome.AlreadyInstalled();
		}
		if (!lock.tryLock()) {
			return new InstallOutcome.Busy();
		}
		Path reserved = null;
		try {
			if (done) {
				return new InstallOutcome.AlreadyInstalled();
			}
			Path file = target.configFile();
			// 2. 설정 파일 자리에 먼저 써 본다
			try {
				reserved = ConfigFile.reserve(file);
			} catch (IOException e) {
				return new InstallOutcome.ConfigNotWritable("설정 파일을 쓸 수 없습니다: " + file.getParent() + ": " + describe(e));
			}
			PGSimpleDataSource ds = dataSource(req);
			String email = req.adminEmail().strip().toLowerCase(Locale.ROOT);
			// 3·4. 접속과 사용 중 감지
			try (Connection c = ds.getConnection()) {
				String evidence = inUse(c, email);
				if (evidence != null) {
					return new InstallOutcome.DbInUse("이미 사용 중인 데이터베이스입니다: " + evidence);
				}
			} catch (SQLException e) {
				return new InstallOutcome.DbFailed("데이터베이스에 접속하지 못했습니다: " + scrub(e.getMessage(), req));
			}
			// 5. 마이그레이션(실패하면 트랜잭션으로 원복된다)
			try {
				Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
			} catch (FlywayException e) {
				return new InstallOutcome.MigrationFailed("마이그레이션이 실패했습니다: " + scrub(e.getMessage(), req));
			}
			// 6. 기본 설정과 관리자를 한 트랜잭션으로
			try {
				createAdmin(ds, req, email);
			} catch (SQLException e) {
				return new InstallOutcome.DbFailed("관리자를 만들지 못했습니다: " + scrub(e.getMessage(), req));
			}
			// 7. 설정 파일 교체 = 설치 완료
			ConfigFile config = new ConfigFile(jdbcUrl(req), req.dbUser(), req.password(), URI.create(req.siteUrl()),
					req.siteUrl().startsWith("https://"), ConfigFile.newSealKey(), file.getParent().resolve("uploads"),
					file.getParent().resolve("backups"), Instant.now());
			try {
				config.commit(reserved, file);
				reserved = null;
			} catch (IOException e) {
				removeAdmin(ds, email);
				return new InstallOutcome.ConfigNotWritable("설정 파일을 쓸 수 없습니다: " + file + ": " + describe(e));
			}
			done = true;
			log.info("설치 완료. 설정 파일: {}", file);
			contextSwitch.requestRunning(config);
			return new InstallOutcome.Done();
		} finally {
			if (reserved != null) {
				try {
					Files.deleteIfExists(reserved);
				} catch (IOException e) {
					log.warn("임시 설정 파일을 지우지 못했습니다: {}", reserved);
				}
			}
			lock.unlock();
		}
	}

	/** 값을 하나씩 넘긴다 — URL 문자열을 이어 붙이면 db_name 으로 드라이버 옵션을 주입할 수 있다. */
	private static PGSimpleDataSource dataSource(InstallRequest req) {
		PGSimpleDataSource ds = new PGSimpleDataSource();
		ds.setServerNames(new String[] { req.dbHost() });
		ds.setPortNumbers(new int[] { req.port() });
		ds.setDatabaseName(req.dbName());
		ds.setUser(req.dbUser());
		ds.setPassword(req.password());
		ds.setSslMode(req.dbSslmode());
		ds.setLoginTimeout(10);
		ds.setConnectTimeout(10);
		return ds;
	}

	/** 운영 컨텍스트가 쓸 URL. DB 이름은 인코딩한다(호스트는 검증에서 문자를 제한했다). */
	static String jdbcUrl(InstallRequest req) {
		String host = req.dbHost().contains(":") && !req.dbHost().startsWith("[") ? "[" + req.dbHost() + "]" : req.dbHost();
		return "jdbc:postgresql://" + host + ":" + req.port() + "/"
				+ URLEncoder.encode(req.dbName(), StandardCharsets.UTF_8).replace("+", "%20") + "?sslmode=" + req.dbSslmode();
	}

	/** 사용 중이면 근거, 아니면 null(FR-108). */
	private static String inUse(Connection c, String email) throws SQLException {
		boolean history = exists(c, "flyway_schema_history");
		if (!history) {
			try (ResultSet r = c.createStatement()
				.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()")) {
				r.next();
				int n = r.getInt(1);
				return n > 0 ? "마이그레이션 이력 표 없이 표가 " + n + "개 있습니다" : null;
			}
		}
		if (exists(c, "users")) {
			try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM users WHERE email = ?")) {
				s.setString(1, email);
				try (ResultSet r = s.executeQuery()) {
					if (r.next()) {
						return "같은 이메일의 계정이 있습니다";
					}
				}
			}
		}
		return null;
	}

	private static boolean exists(Connection c, String table) throws SQLException {
		try (PreparedStatement s = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
			s.setString(1, table);
			try (ResultSet r = s.executeQuery()) {
				r.next();
				return r.getBoolean(1);
			}
		}
	}

	private void createAdmin(PGSimpleDataSource ds, InstallRequest req, String email) throws SQLException {
		try (Connection c = ds.getConnection()) {
			c.setAutoCommit(false);
			try (PreparedStatement settings = c.prepareStatement(
					"INSERT INTO settings (key, value) VALUES ('site.name', ?), ('site.type', ?) "
							+ "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()");
					PreparedStatement user = c.prepareStatement(
							"INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id");
					PreparedStatement role = c.prepareStatement(
							"INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE key = 'admin'")) {
				settings.setString(1, req.siteName().strip());
				settings.setString(2, req.type());
				settings.executeUpdate();
				user.setString(1, email);
				user.setString(2, encoder.encode(req.adminPassword()));
				UUID id;
				try (ResultSet r = user.executeQuery()) {
					r.next();
					id = r.getObject(1, UUID.class);
				}
				role.setObject(1, id);
				if (role.executeUpdate() != 1) {
					throw new SQLException("admin 역할이 없습니다");
				}
				c.commit();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}
		}
	}

	/** 설정 파일 교체가 실패했을 때 6단계를 되돌려 같은 입력으로 다시 낼 수 있게 한다. */
	private static void removeAdmin(PGSimpleDataSource ds, String email) {
		try (Connection c = ds.getConnection()) {
			c.setAutoCommit(false);
			try (PreparedStatement u = c.prepareStatement("DELETE FROM users WHERE email = ?");
					PreparedStatement s = c.prepareStatement("DELETE FROM settings WHERE key IN ('site.name', 'site.type')")) {
				u.setString(1, email);
				u.executeUpdate();
				s.executeUpdate();
				c.commit();
			}
		} catch (SQLException e) {
			log.error("설정 파일 쓰기 실패 뒤 관리자 계정을 지우지 못했습니다. 재설치 전에 DB 를 비우세요: {}", e.getMessage());
		}
	}

	private static String describe(IOException e) {
		return e.getClass().getSimpleName() + ": " + e.getMessage();
	}

	/** 드라이버가 입력값을 되풀이해도 비밀번호가 응답에 나가지 않게 한다. */
	private static String scrub(String message, InstallRequest req) {
		if (message == null) {
			return "";
		}
		String pw = req.password();
		return pw.isEmpty() ? message : message.replace(pw, "***");
	}
}
