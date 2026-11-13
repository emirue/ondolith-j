package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.Launcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthControllerTest {

	@TempDir
	Path dir;

	@Test
	void 설치_컨텍스트의_헬스는_install_과_버전() throws Exception {
		try (var ctx = Launcher.startInstall(dir.resolve("ondolith-j.properties"), "--server.port=0")) {
			String port = ctx.getEnvironment().getProperty("local.server.port");
			HttpResponse<String> r = HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(r.statusCode()).isEqualTo(200);
			assertThat(r.body()).contains("\"state\":\"install\"").containsPattern("\"version\":\"[^\"]+\"");
		}
	}
}
