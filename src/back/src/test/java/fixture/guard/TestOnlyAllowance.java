package fixture.guard;

import dev.ondolithj.auth.GuardCheck;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 시험 클래스패스에만 있는 springdoc(OpenAPI 문서 생성, 시험 의존성)의 처리기를 기동 점검이 허용하게 한다.
 * 시험 자원의 AutoConfiguration.imports 로만 들어가므로 운영 jar 에는 없다.
 */
@AutoConfiguration
public class TestOnlyAllowance {

	@Bean
	GuardCheck.Allowed allowSpringdoc() {
		return new GuardCheck.Allowed("org.springdoc");
	}
}
