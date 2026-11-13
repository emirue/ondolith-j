package dev.ondolithj.shared;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 두 컨텍스트에 모두 있는 유일한 API. 프런트가 이것으로 화면을 고른다. */
@RestController
public class HealthController {

	public record Health(String state, String version) {
	}

	private final Health health;

	public HealthController(AppState state, ObjectProvider<BuildProperties> build) {
		BuildProperties b = build.getIfAvailable();
		this.health = new Health(state.state(), b == null ? "dev" : b.getVersion());
	}

	@GetMapping("/api/health")
	public Health health() {
		return health;
	}
}
