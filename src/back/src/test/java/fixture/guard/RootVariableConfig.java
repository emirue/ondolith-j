package fixture.guard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RootVariableConfig {

	@Bean
	RootVariable rootVariable() {
		return new RootVariable();
	}
}
