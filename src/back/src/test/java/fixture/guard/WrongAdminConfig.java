package fixture.guard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WrongAdminConfig {

	@Bean
	WrongAdminMark wrongAdminMark() {
		return new WrongAdminMark();
	}
}
