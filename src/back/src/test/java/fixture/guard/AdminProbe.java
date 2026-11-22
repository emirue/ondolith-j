package fixture.guard;

import dev.ondolithj.auth.AdminPermission;
import dev.ondolithj.auth.Permission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** @AdminPermission 이 admin.access 와 그 권한을 함께 요구하는지 보는 관리자 경로 처리기. AdminProbeConfig 로만 들어간다. */
@RestController
public class AdminProbe {

	@AdminPermission(Permission.SETTINGS_VIEW)
	@GetMapping("/api/admin/probe")
	public Map<String, Object> probe() {
		return Map.of();
	}
}
