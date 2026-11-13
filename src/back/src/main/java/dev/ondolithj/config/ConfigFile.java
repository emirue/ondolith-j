package dev.ondolithj.config;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Properties;

/**
 * 설치가 만드는 설정 파일(스펙 2.1). DB 접속·봉인 키처럼 DB 밖에 있어야 하는 값만 둔다.
 * 사이트 이름·유형은 DB(settings)에 있다.
 */
public record ConfigFile(String dbUrl, String dbUser, String dbPassword, URI siteUrl, boolean secureCookies,
		String sealKey, Path uploadDir, Path backupDir, Instant installedAt) {

	private static final SecureRandom RANDOM = new SecureRandom();

	public static ConfigLoad load(Path file) {
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (NoSuchFileException e) {
			return new ConfigLoad.Missing();
		} catch (IOException e) {
			return new ConfigLoad.Broken("읽을 수 없음: " + e.getMessage());
		}
		Properties p = new Properties();
		try {
			p.load(new StringReader(text));
		} catch (IOException | IllegalArgumentException e) {
			return new ConfigLoad.Broken("파싱 실패: " + e.getMessage());
		}
		try {
			Path base = file.toAbsolutePath().getParent();
			return new ConfigLoad.Loaded(new ConfigFile(dbUrl(p), required(p, "db.user"), p.getProperty("db.password", ""),
					siteUrl(p), bool(p, "cookies.secure"), sealKey(p), dir(p, "upload.dir", base, "uploads"),
					dir(p, "backup.dir", base, "backups"), instant(p)));
		} catch (IllegalArgumentException e) {
			return new ConfigLoad.Broken(e.getMessage());
		}
	}

	/** 설정 파일 자리에 0600 임시 파일을 먼저 만든다(설치 2단계). 쓸 수 없는 폴더면 IOException. */
	public static Path reserve(Path file) throws IOException {
		return Files.createTempFile(file.toAbsolutePath().getParent(), ".ondolith-j", ".tmp",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
	}

	/** reserve 로 만든 임시 파일에 내용을 쓰고 설정 파일 자리로 원자적으로 옮긴다(설치 7단계). */
	public void commit(Path reserved, Path file) throws IOException {
		Files.writeString(reserved, render(), StandardCharsets.UTF_8);
		Files.move(reserved, file, StandardCopyOption.ATOMIC_MOVE);
	}

	public static String newSealKey() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getEncoder().encodeToString(b);
	}

	/** 비밀번호·봉인 키가 로그에 새지 않게 가린다. */
	@Override
	public String toString() {
		return "ConfigFile[dbUrl=" + dbUrl + ", dbUser=" + dbUser + ", siteUrl=" + siteUrl + ", secureCookies="
				+ secureCookies + ", uploadDir=" + uploadDir + ", backupDir=" + backupDir + ", installedAt="
				+ installedAt + "]";
	}

	private String render() {
		Properties p = new Properties();
		p.setProperty("db.url", dbUrl);
		p.setProperty("db.user", dbUser);
		p.setProperty("db.password", dbPassword);
		p.setProperty("site.url", siteUrl.toString());
		p.setProperty("cookies.secure", Boolean.toString(secureCookies));
		p.setProperty("seal.key", sealKey);
		p.setProperty("upload.dir", uploadDir.toString());
		p.setProperty("backup.dir", backupDir.toString());
		p.setProperty("installed.at", installedAt.toString());
		StringWriter w = new StringWriter();
		try {
			p.store(w, "ondolith-j 설정. 설치가 만들었다. 권한 0600 을 유지한다");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return w.toString();
	}

	private static String required(Properties p, String key) {
		String v = p.getProperty(key, "").strip();
		if (v.isEmpty()) {
			throw new IllegalArgumentException(key + ": 값이 없음");
		}
		return v;
	}

	private static String dbUrl(Properties p) {
		String v = required(p, "db.url");
		if (!v.startsWith("jdbc:postgresql://")) {
			throw new IllegalArgumentException("db.url: jdbc:postgresql:// 로 시작해야 함");
		}
		return v;
	}

	private static URI siteUrl(Properties p) {
		return parseSiteUrl(required(p, "site.url"));
	}

	/**
	 * 사이트 주소 규칙(설치 검증과 설정 파일 읽기가 같이 쓴다 — 둘이 다르면 설치는 되고 재시작이 실패한다).
	 * http·https 절대 주소이고 호스트가 있어야 한다. 한글 도메인은 punycode 로 바꿔서 넣는다.
	 */
	public static URI parseSiteUrl(String v) {
		try {
			URI u = new URI(v);
			if (u.isAbsolute() && ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null) {
				return u;
			}
		} catch (java.net.URISyntaxException e) {
			// 아래에서 같은 오류로 알린다
		}
		throw new IllegalArgumentException("site.url: http·https 절대 주소가 아님");
	}

	private static boolean bool(Properties p, String key) {
		String v = required(p, key);
		if (!v.equals("true") && !v.equals("false")) {
			throw new IllegalArgumentException(key + ": true 또는 false 여야 함");
		}
		return Boolean.parseBoolean(v);
	}

	private static String sealKey(Properties p) {
		String v = required(p, "seal.key");
		try {
			if (Base64.getDecoder().decode(v).length == 32) {
				return v;
			}
		} catch (IllegalArgumentException e) {
			// 아래에서 같은 오류로 알린다
		}
		throw new IllegalArgumentException("seal.key: base64 32바이트가 아님");
	}

	private static Path dir(Properties p, String key, Path base, String fallback) {
		String v = p.getProperty(key, "").strip();
		return v.isEmpty() ? base.resolve(fallback) : base.resolve(v).normalize();
	}

	private static Instant instant(Properties p) {
		String v = required(p, "installed.at");
		try {
			return Instant.parse(v);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("installed.at: ISO-8601 UTC 시각이 아님");
		}
	}
}
