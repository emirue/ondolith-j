package fixture.guard;

import dev.ondolithj.auth.GuardCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AllowOutsideConfig {

	@Bean
	GuardCheck.Allowed allowOutside() {
		return new GuardCheck.Allowed("fixture.guard");
	}
}
