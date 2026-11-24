package dev.ondolithj.install;

import dev.ondolithj.auth.GuardCheck;
import dev.ondolithj.shared.ApiErrors;
import dev.ondolithj.shared.AppState;
import dev.ondolithj.shared.HealthController;
import dev.ondolithj.shared.RequestSizeLimit;
import dev.ondolithj.shared.SecurityConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** 설치 컨텍스트. DB 가 아직 없으므로 DataSource·JPA·Flyway 자동 구성을 뺀다. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = { DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
		DataJpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class,
		UserDetailsServiceAutoConfiguration.class })
@ComponentScan
// 설치 컨텍스트는 install 패키지만 스캔하므로 기동 점검을 직접 넣는다
@Import({ SecurityConfig.class, ApiErrors.class, HealthController.class, GuardCheck.class, RequestSizeLimit.class })
public class InstallApplication {

	@Bean
	AppState appState() {
		return new AppState("install");
	}
}
