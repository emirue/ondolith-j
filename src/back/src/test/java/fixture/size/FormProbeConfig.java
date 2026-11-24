package fixture.size;

import dev.ondolithj.auth.GuardCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FormProbeConfig {

	@Bean
	FormProbe formProbe() {
		return new FormProbe();
	}

	@Bean
	GuardCheck.Allowed allowFormProbe() {
		return new GuardCheck.Allowed("fixture.size");
	}
}
