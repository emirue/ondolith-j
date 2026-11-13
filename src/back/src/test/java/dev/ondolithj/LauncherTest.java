package dev.ondolithj;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.config.ConfigFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherTest {

	@TempDir
	Path dir;

	@AfterEach
	void restoreExit() {
		Launcher.exitHandler = System::exit;
	}

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

	@Test
	void 설치_컨텍스트가_준비되기_전에_온_전환_요청도_설치_컨텍스트를_닫는다() throws Exception {
		AtomicInteger code = new AtomicInteger(-1);
		Launcher.exitHandler = code::set;
		// 운영 컨텍스트는 못 뜨는 설정 — 이 시험은 설치 컨텍스트가 닫히는지만 본다
		ConfigFile unreachable = new ConfigFile("jdbc:postgresql://127.0.0.1:1/none", "app", "x",
				URI.create("http://site.ondolith.test"), false, ConfigFile.newSealKey(), dir.resolve("u"), dir.resolve("b"),
				Instant.now());
		CompletableFuture<org.springframework.context.ConfigurableApplicationContext> self = new CompletableFuture<>();
		Launcher.contextSwitch(self, new String[] { "--server.port=0",
				"--spring.datasource.hikari.initialization-fail-timeout=1" }).requestRunning(unreachable);
		var installCtx = Launcher.startInstall(dir.resolve("ondolith-j.properties"), cfg -> {
		}, "--server.port=0");
		self.complete(installCtx);
		for (int i = 0; i < 100 && installCtx.isActive(); i++) {
			Thread.sleep(100);
		}
		assertThat(installCtx.isActive()).isFalse();
	}
}
