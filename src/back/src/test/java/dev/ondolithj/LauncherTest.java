package dev.ondolithj;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherTest {

	@TempDir
	Path dir;

	@Test
	void 깨진_설정_파일이면_종료_코드_1_과_한_줄_오류() throws IOException {
		Path config = dir.resolve("ondolith-j.properties");
		Files.writeString(config, "db.user=app\n");
		PrintStream original = System.err;
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
		int code;
		try {
			code = Launcher.run(new String[] { "--config=" + config });
		} finally {
			System.setErr(original);
		}
		assertThat(code).isEqualTo(1);
		assertThat(err.toString(StandardCharsets.UTF_8)).contains("설정 파일이 잘못됐습니다").contains(config.toString())
				.contains("db.url");
	}

	@Test
	void 설치_컨텍스트에는_DataSource_가_없다() {
		try (var ctx = Launcher.startInstall(dir.resolve("ondolith-j.properties"), "--server.port=0")) {
			assertThat(ctx.getBeanNamesForType(DataSource.class)).isEmpty();
		}
	}
}
