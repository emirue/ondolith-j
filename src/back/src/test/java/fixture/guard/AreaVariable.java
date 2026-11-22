package fixture.guard;

import dev.ondolithj.auth.Permission;
import dev.ondolithj.auth.RequiresPermission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/ 바로 다음 마디가 변수인 매핑 — /api/admin/probe 를 받지만 관리자 경로로 세지 않는다. 기동 점검이 막아야 한다(M-7). */
@RestController
public class AreaVariable {

	@RequiresPermission(Permission.ADMIN_ACCESS)
	@GetMapping("/api/{area}/probe")
	public Map<String, Object> probe() {
		return Map.of();
	}
}
