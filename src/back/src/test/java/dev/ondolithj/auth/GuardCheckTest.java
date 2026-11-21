package dev.ondolithj.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ondolithj.Launcher;
import dev.ondolithj.config.ConfigFile;
import dev.ondolithj.install.ContextSwitch;
import dev.ondolithj.install.InstallApplication;
import dev.ondolithj.install.InstallTarget;
import fixture.guard.OutsideConfig;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 기동 점검(1-A 스펙 5절). 규칙은 단위로, 「실제로 기동이 멈춘다」는 컨텍스트로 본다. */
@Testcontainers
class GuardCheckTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine");

	@TempDir
	Path dir;

	// 단위 시험용 처리기 모양. @Controller 가 없어 컴포넌트 스캔에 잡히지 않는다
	static class None {
		public String m() {
			return "";
		}
	}

	@RequiresPermission(Permission.ADMIN_ACCESS)
	static class ClassLevel {
		public String m() {
			return "";
		}
	}

	@RequiresPermission(Permission.ADMIN_ACCESS)
	static class Both {
		@PublicApi
		public String m() {
			return "";
		}
	}

	static class Direct {
		@PreAuthorize("permitAll()")
		public String m() {
			return "";
		}
	}

	static class Private {
		@RequiresPermission(Permission.ADMIN_ACCESS)
		private String m() {
			return "";
		}
	}

	static class Final {
		@RequiresPermission(Permission.ADMIN_ACCESS)
		public final String m() {
			return "";
		}
	}

	static class Static {
		@RequiresPermission(Permission.ADMIN_ACCESS)
		public static String m() {
			return "";
		}
	}

	/** 누군가 만든 합성 애너테이션: 메서드의 @PreAuthorize 가 클래스의 @RequiresPermission 을 덮는다. */
	@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
	@PreAuthorize("permitAll()")
	@interface Sneaky {
	}

	@RequiresPermission(Permission.ADMIN_ACCESS)
	static class Composed {
		@Sneaky
		public String m() {
			return "";
		}
	}

	static class Public {
		@PublicApi
		public String m() {
			return "";
		}
	}

	static class AdminOnly {
		@AdminPermission(Permission.SETTINGS_VIEW)
		public String m() {
			return "";
		}
	}

	@RequiresPermission(Permission.ADMIN_ACCESS)
	static class AdminAndClass {
		@AdminPermission(Permission.SETTINGS_VIEW)
		public String m() {
			return "";
		}
	}

	static class OtherPermission {
		@RequiresPermission(Permission.SETTINGS_VIEW)
		public String m() {
			return "";
		}
	}

	static class LogView {
		@AdminPermission(Permission.LOG_VIEW)
		public String m() {
			return "";
		}
	}

	static HandlerMethod handler(Class<?> type) throws Exception {
		return new HandlerMethod(type.getDeclaredConstructor().newInstance(), type.getDeclaredMethod("m"));
	}

	static List<String> adminPathProblems(Class<?> type) throws Exception {
		var admin = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/api/admin/x").build();
		return GuardCheck.checkAdminPaths(java.util.Map.of(admin, handler(type)));
	}

	static List<String> problems(Class<?> type) throws Exception {
		Method m = Arrays.stream(type.getDeclaredMethods()).filter(x -> x.getName().equals("m")).findFirst().orElseThrow();
		Object bean = type.getDeclaredConstructor().newInstance();
		return GuardCheck.check(List.of(new HandlerMethod(bean, m)), Set.of());
	}

	@Test
	void 표시가_정확히_하나인_공개_메서드만_통과한다() throws Exception {
		assertThat(problems(Public.class)).isEmpty();
		assertThat(problems(ClassLevel.class)).isEmpty();
		assertThat(problems(None.class)).singleElement().asString().contains("None#m").contains("표시가 없다");
		assertThat(problems(Both.class)).singleElement().asString().contains("둘 이상");
		assertThat(problems(Direct.class)).singleElement().asString().contains("@PreAuthorize");
		// 프록시가 가로채지 못해 표시가 있어도 열린다(일회성 확인: 익명 200)
		assertThat(problems(Private.class)).singleElement().asString().contains("private");
		assertThat(problems(Final.class)).singleElement().asString().contains("final");
		assertThat(problems(Static.class)).singleElement().asString().contains("static");
		assertThat(problems(Composed.class)).singleElement().asString().contains("둘 이상");
		assertThat(problems(AdminOnly.class)).isEmpty();
		assertThat(problems(AdminAndClass.class)).singleElement().asString().contains("둘 이상");
	}

	@Test
	void 관리자_경로에는_공개_표시를_달_수_없다() throws Exception {
		java.lang.reflect.Method m = Public.class.getDeclaredMethod("m");
		HandlerMethod h = new HandlerMethod(new Public(), m);
		var admin = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/api/admin/x").build();
		var other = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/api/x").build();
		assertThat(GuardCheck.checkAdminPaths(java.util.Map.of(admin, h))).singleElement().asString().contains("/api/admin");
		assertThat(GuardCheck.checkAdminPaths(java.util.Map.of(other, h))).isEmpty();
	}

	@Test
	void 관리자_경로에는_admin_access_를_요구하는_표시만_단다() throws Exception {
		assertThat(adminPathProblems(OtherPermission.class)).singleElement().asString().contains("/api/admin")
			.contains("OtherPermission#m");
		assertThat(adminPathProblems(ClassLevel.class)).isEmpty();
		assertThat(adminPathProblems(AdminOnly.class)).isEmpty();
		assertThat(adminPathProblems(Public.class)).singleElement().asString().contains("@PublicApi");
	}

	@Test
	void 쓰는_권한에_AdminPermission_의_값도_센다() throws Exception {
		assertThat(GuardCheck.used(List.of(handler(LogView.class)))).containsExactly(Permission.LOG_VIEW);
	}

	@Test
	void 쓰는_권한을_모은다() throws Exception {
		Method m = ClassLevel.class.getDeclaredMethod("m");
		assertThat(GuardCheck.used(List.of(new HandlerMethod(new ClassLevel(), m)))).containsExactly(Permission.ADMIN_ACCESS);
	}

	ConfigurableInstall install(Class<?>... extra) {
		Class<?>[] sources = new Class<?>[extra.length + 1];
		sources[0] = InstallApplication.class;
		System.arraycopy(extra, 0, sources, 1, extra.length);
		InstallTarget target = new InstallTarget(dir.resolve("x.properties"));
		ContextSwitch sw = c -> {
		};
		return () -> new SpringApplicationBuilder(sources).initializers(c -> {
			c.getBeanFactory().registerSingleton("installTarget", target);
			c.getBeanFactory().registerSingleton("contextSwitch", sw);
		}).run("--server.port=0", "--server.address=127.0.0.1").close();
	}

	interface ConfigurableInstall {
		void run();
	}

	@Test
	void 패키지_밖의_처리기는_허용_목록에_없으면_기동이_멈춘다() {
		assertThatThrownBy(install(OutsideConfig.class)::run).hasStackTraceContaining("fixture.guard.Outside");
	}

	@Test
	void 관리자_경로에_admin_access_가_빠진_표시가_있으면_기동이_멈춘다() {
		// 허용 빈이 있으니 「패키지 밖」 때문이 아니다
		assertThatThrownBy(install(fixture.guard.WrongAdminConfig.class, fixture.guard.AllowOutsideConfig.class)::run)
			.hasStackTraceContaining("/api/admin").hasStackTraceContaining("WrongAdminMark");
	}

	@Test
	void 허용_빈이_있으면_패키지_밖의_처리기도_뜬다() {
		install(OutsideConfig.class, fixture.guard.AllowOutsideConfig.class).run();
	}

	@Test
	void DB_의_권한과_코드의_권한이_다르면_운영_기동이_멈춘다() throws Exception {
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
			c.createStatement().execute("CREATE DATABASE extra");
		}
		String url = pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/extra$1");
		Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).load().migrate();
		try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword())) {
			c.createStatement().execute("INSERT INTO permissions (key) VALUES ('ghost.read')");
		}
		ConfigFile config = new ConfigFile(url, pg.getUsername(), pg.getPassword(), URI.create("http://site.ondolith.test"),
				false, ConfigFile.newSealKey(), dir.resolve("u"), dir.resolve("b"), Instant.now());
		assertThatThrownBy(() -> Launcher.startRunning(config, "--server.port=0", "--server.address=127.0.0.1").close())
			.hasStackTraceContaining("ghost.read");
	}
}
