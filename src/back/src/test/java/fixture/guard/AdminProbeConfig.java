package fixture.guard;

import dev.ondolithj.auth.GuardCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminProbeConfig {

	@Bean
	AdminProbe adminProbe() {
		return new AdminProbe();
	}

	@Bean
	GuardCheck.Allowed allowAdminProbe() {
		return new GuardCheck.Allowed("fixture.guard");
	}
}
