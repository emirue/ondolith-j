package fixture.size;

import dev.ondolithj.auth.PublicApi;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 폼 본문이 Tomcat 매개변수 해석으로 어디까지 읽히는지 보는 탐침. FormProbeConfig 로만 들어간다. */
@RestController
public class FormProbe {

	@PublicApi
	@PostMapping("/api/form-probe")
	public Map<String, Object> probe(HttpServletRequest request) {
		int length;
		try {
			String x = request.getParameter("x");
			length = x == null ? -1 : x.length();
		}
		catch (RuntimeException e) {
			// 상한 초과를 예외로 내는 Tomcat 판 — 탐침 탓의 500 이 제품의 500 으로 보이지 않게
			length = -2;
		}
		return Map.of("length", length, "content_length", request.getContentLengthLong());
	}
}
