package dev.measure.bench;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(a -> a
				.requestMatchers(HttpMethod.GET, "/api/**").permitAll()
				.anyRequest().authenticated());
		return http.build();
	}
}
