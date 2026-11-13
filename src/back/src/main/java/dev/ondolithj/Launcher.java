package dev.ondolithj;

import dev.ondolithj.config.ConfigFile;
import dev.ondolithj.config.ConfigLoad;
import dev.ondolithj.install.ContextSwitch;
import dev.ondolithj.install.InstallApplication;
import dev.ondolithj.install.InstallTarget;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 설정 파일을 보고 설치 컨텍스트 또는 운영 컨텍스트를 띄운다(스펙 2.1).
 * 두 컨텍스트는 라우트 트리가 완전히 다르다 — 한 라우터에 「설치됐나」 분기를 넣지 않는다(ondolith D20).
 */
public final class Launcher {

	static final String DEFAULT_CONFIG = "./ondolith-j.properties";

	private static final Logger log = LoggerFactory.getLogger(Launcher.class);

	/** 시험이 바꿔 끼운다. */
	static IntConsumer exitHandler = System::exit;

	private static volatile ConfigurableApplicationContext current;

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

	/** 설치가 끝나면 별도 스레드가 설치 컨텍스트를 닫고 같은 인자(같은 포트)로 운영 컨텍스트를 띄운다(FR-106). */
	public static ConfigurableApplicationContext startInstall(Path configFile, String... args) {
		AtomicReference<ConfigurableApplicationContext> self = new AtomicReference<>();
		// 요청 스레드(데몬)에서 만들므로 데몬이 아님을 명시한다 — 두 컨텍스트 사이에 JVM 이 끝나지 않게
		ContextSwitch sw = config -> Thread.ofPlatform().name("ondolith-switch").daemon(false)
			.start(() -> switchToRunning(self.get(), config, args));
		ConfigurableApplicationContext ctx = startInstall(configFile, sw, args);
		self.set(ctx);
		return ctx;
	}

	public static ConfigurableApplicationContext startInstall(Path configFile, ContextSwitch contextSwitch,
			String... args) {
		InstallTarget target = new InstallTarget(configFile.toAbsolutePath());
		ConfigurableApplicationContext ctx = new SpringApplicationBuilder(InstallApplication.class).initializers(c -> {
			c.getBeanFactory().registerSingleton("installTarget", target);
			c.getBeanFactory().registerSingleton("contextSwitch", contextSwitch);
		}).run(args);
		current = ctx;
		return current;
	}

	/** DB 접속 값은 기본 속성으로만 넘긴다(환경변수·파일로 새지 않게). */
	public static ConfigurableApplicationContext startRunning(ConfigFile config, String... args) {
		current = new SpringApplicationBuilder(RunningApplication.class)
			.properties(Map.of("spring.datasource.url", config.dbUrl(), "spring.datasource.username", config.dbUser(),
					"spring.datasource.password", config.dbPassword()))
			.run(args);
		return current;
	}

	/** 시험용: 마지막으로 띄운 컨텍스트. */
	static ConfigurableApplicationContext current() {
		return current;
	}

	/** 설치 컨텍스트를 닫고(정상 종료라 202 응답을 마친다) 운영 컨텍스트를 띄운다. 실패하면 종료 1 — 설정 파일이 있으니 다시 시작하면 운영으로 뜬다. */
	static void switchToRunning(ConfigurableApplicationContext installCtx, ConfigFile config, String[] args) {
		try {
			installCtx.close();
			startRunning(config, args);
		} catch (Throwable t) {
			log.error("운영 모드로 바꾸지 못했습니다. 다시 시작하면 운영 모드로 뜹니다", t);
			exitHandler.accept(1);
		}
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
