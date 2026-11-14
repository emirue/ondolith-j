package fixture.guard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** dev.ondolithj 밖의 처리기(나중에 딸려 오는 의존성의 컨트롤러를 흉내). 스캔 범위 밖이라 OutsideConfig 로만 들어간다. */
@RestController
public class Outside {

	@GetMapping("/outside")
	public String get() {
		return "open";
	}
}
