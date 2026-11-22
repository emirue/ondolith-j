package fixture.guard;

import dev.ondolithj.auth.GuardCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnclassifiedPostConfig {

	@Bean
	UnclassifiedPost unclassifiedPost() {
		return new UnclassifiedPost();
	}

	@Bean
	GuardCheck.Allowed allowUnclassifiedPost() {
		return new GuardCheck.Allowed("fixture.guard");
	}
}
