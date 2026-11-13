package dev.ondolithj.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

	@TempDir
	Path dir;

	static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	String valid() {
		return """
				db.url=jdbc:postgresql://localhost:5432/ondolithj?sslmode=disable
				db.user=app
				db.password=not-a-real-password
				site.url=https://site.ondolith.test
				cookies.secure=true
				seal.key=%s
				upload.dir=
				backup.dir=data/backups
				installed.at=2026-11-13T00:00:00Z
				""".formatted(KEY);
	}

	Path write(String body) throws IOException {
		Path f = dir.resolve("ondolith-j.properties");
		Files.writeString(f, body);
		return f;
	}

	@Test
	void 없으면_Missing() {
		assertThat(ConfigFile.load(dir.resolve("none.properties"))).isInstanceOf(ConfigLoad.Missing.class);
	}

	@Test
	void 정상_파일은_Loaded() throws IOException {
		ConfigLoad r = ConfigFile.load(write(valid()));
		assertThat(r).isInstanceOf(ConfigLoad.Loaded.class);
		ConfigFile c = ((ConfigLoad.Loaded) r).config();
		assertThat(c.dbUrl()).isEqualTo("jdbc:postgresql://localhost:5432/ondolithj?sslmode=disable");
		assertThat(c.dbUser()).isEqualTo("app");
		assertThat(c.dbPassword()).isEqualTo("not-a-real-password");
		assertThat(c.siteUrl()).isEqualTo(URI.create("https://site.ondolith.test"));
		assertThat(c.secureCookies()).isTrue();
		assertThat(c.sealKey()).isEqualTo(KEY);
		assertThat(c.uploadDir()).isEqualTo(dir.resolve("uploads"));
		assertThat(c.backupDir()).isEqualTo(dir.resolve("data/backups"));
		assertThat(c.installedAt()).isEqualTo(Instant.parse("2026-11-13T00:00:00Z"));
	}

	@Test
	void db_url_없으면_Broken() throws IOException {
		assertBroken(valid().replaceFirst("db.url=.*\n", ""), "db.url");
	}

	@Test
	void site_url_이_http_https_가_아니면_Broken() throws IOException {
		assertBroken(valid().replace("https://site.ondolith.test", "ftp://x"), "site.url");
	}

	@Test
	void seal_key_가_32바이트가_아니면_Broken() throws IOException {
		assertBroken(valid().replace(KEY, Base64.getEncoder().encodeToString(new byte[16])), "seal.key");
	}

	@Test
	void installed_at_형식이_틀리면_Broken() throws IOException {
		assertBroken(valid().replace("2026-11-13T00:00:00Z", "어제"), "installed.at");
	}

	@Test
	void cookies_secure_가_불리언이_아니면_Broken() throws IOException {
		assertBroken(valid().replace("cookies.secure=true", "cookies.secure=yes"), "cookies.secure");
	}

	void assertBroken(String body, String key) throws IOException {
		ConfigLoad r = ConfigFile.load(write(body));
		assertThat(r).isInstanceOf(ConfigLoad.Broken.class);
		assertThat(((ConfigLoad.Broken) r).reason()).contains(key);
	}

	@Test
	void commit_은_0600_으로_원자적으로_쓴다() throws IOException {
		ConfigFile c = ((ConfigLoad.Loaded) ConfigFile.load(write(valid()))).config();
		Path target = dir.resolve("new.properties");
		Path reserved = ConfigFile.reserve(target);
		c.commit(reserved, target);
		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------");
		assertThat(ConfigFile.load(target)).isEqualTo(new ConfigLoad.Loaded(c));
		try (var files = Files.list(dir)) {
			assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("ondolith-j.properties", "new.properties");
		}
	}

	@Test
	void reserve_는_쓸_수_없는_폴더에서_IOException() throws IOException {
		Path ro = Files.createDirectory(dir.resolve("ro"));
		Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-x------"));
		try {
			assertThatThrownBy(() -> ConfigFile.reserve(ro.resolve("x.properties"))).isInstanceOf(IOException.class);
		} finally {
			Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test
	void newSealKey_는_32바이트_무작위() {
		String a = ConfigFile.newSealKey();
		assertThat(Base64.getDecoder().decode(a)).hasSize(32);
		assertThat(ConfigFile.newSealKey()).isNotEqualTo(a);
	}

	@Test
	void toString_에_비밀번호와_봉인_키가_없다() throws IOException {
		ConfigFile c = ((ConfigLoad.Loaded) ConfigFile.load(write(valid()))).config();
		assertThat(c.toString()).doesNotContain("not-a-real-password").doesNotContain(KEY);
	}
}
