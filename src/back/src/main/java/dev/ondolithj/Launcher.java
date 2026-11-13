package dev.ondolithj;

import dev.ondolithj.config.ConfigFile;
import dev.ondolithj.config.ConfigLoad;
import dev.ondolithj.install.InstallApplication;
import dev.ondolithj.install.InstallTarget;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.IntConsumer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 설정 파일을 보고 설치 컨텍스트 또는 운영 컨텍스트를 띄운다(스펙 2.1).
 * 두 컨텍스트는 라우트 트리가 완전히 다르다 — 한 라우터에 「설치됐나」 분기를 넣지 않는다(ondolith D20).
 */
public final class Launcher {

	static final String DEFAULT_CONFIG = "./ondolith-j.properties";

	/** 시험이 바꿔 끼운다. */
	static IntConsumer exitHandler = System::exit;

	private Launcher() {
	}

	public static void main(String[] args) {
		int code = run(args);
		if (code != 0) {
			exitHandler.accept(code);
		}
	}

	/** 분기하고 기동한다. 정상 기동 0, 깨진 설정 1(FR-110). */
	static int run(String[] args) {
		Path config = configPath(args);
		return switch (ConfigFile.load(config)) {
			case ConfigLoad.Missing m -> {
				startInstall(config, args);
				yield 0;
			}
			case ConfigLoad.Broken b -> {
				System.err.println("설정 파일이 잘못됐습니다: " + config.toAbsolutePath() + ": " + b.reason());
				yield 1;
			}
			case ConfigLoad.Loaded l -> {
				startRunning(l.config(), args);
				yield 0;
			}
		};
	}

	public static ConfigurableApplicationContext startInstall(Path configFile, String... args) {
		InstallTarget target = new InstallTarget(configFile.toAbsolutePath());
		return new SpringApplicationBuilder(InstallApplication.class)
			.initializers(ctx -> ctx.getBeanFactory().registerSingleton("installTarget", target))
			.run(args);
	}

	/** DB 접속 값은 기본 속성으로만 넘긴다(환경변수·파일로 새지 않게). */
	public static ConfigurableApplicationContext startRunning(ConfigFile config, String... args) {
		return new SpringApplicationBuilder(RunningApplication.class)
			.properties(Map.of("spring.datasource.url", config.dbUrl(), "spring.datasource.username", config.dbUser(),
					"spring.datasource.password", config.dbPassword()))
			.run(args);
	}

	static Path configPath(String[] args) {
		for (String a : args) {
			if (a.startsWith("--config=")) {
				return Path.of(a.substring("--config=".length()));
			}
		}
		return Path.of(DEFAULT_CONFIG);
	}
}
