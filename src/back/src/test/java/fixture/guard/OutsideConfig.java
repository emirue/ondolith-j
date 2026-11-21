package fixture.guard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OutsideConfig {

	@Bean
	Outside outside() {
		return new Outside();
	}
}
