package dev.ondolithj.auth;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 기동 점검(1-A 스펙 5절, ondolith D15 4.4). 웹 서버가 요청을 받기 전에 돌고, 하나라도 어긋나면 기동을 멈춘다.
 * URL 층은 permitAll 이라, 기본 거부는 이 점검이 맡는다 — 표시 없는 API 는 앱이 뜨지 않는다.
 */
@Component
public class GuardCheck implements SmartInitializingSingleton {

	/** dev.ondolithj 밖에서 허용할 처리기의 패키지 접두사. 시험만 쓴다(운영 설정 속성으로는 열 수 없다). */
	public record Allowed(String packagePrefix) {
	}

	private static final Logger log = LoggerFactory.getLogger(GuardCheck.class);

	/** Boot 의 오류 처리기(/error). 우리 코드가 아니지만 늘 있다. */
	private static final String BOOT_ERROR = "org.springframework.boot.webmvc.autoconfigure.error";

	private static final Pattern API_ROOT_VARIABLE = Pattern.compile("^/api/[^/]*[{*?]");

	private final ListableBeanFactory beans;

	private final ObjectProvider<DataSource> dataSource;

	private final ObjectProvider<Allowed> allowed;

	GuardCheck(ListableBeanFactory beans, ObjectProvider<DataSource> dataSource, ObjectProvider<Allowed> allowed) {
		this.beans = beans;
		this.dataSource = dataSource;
		this.allowed = allowed;
	}

	@Override
	public void afterSingletonsInstantiated() {
		Map<RequestMappingInfo, HandlerMethod> mappings = new HashMap<>();
		beans.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(m -> mappings.putAll(m.getHandlerMethods()));
		List<HandlerMethod> handlers = List.copyOf(mappings.values());
		Set<String> prefixes = Stream.concat(Stream.of(BOOT_ERROR), allowed.stream().map(Allowed::packagePrefix))
			.collect(Collectors.toSet());
		List<String> problems = new ArrayList<>(check(handlers, prefixes));
		problems.addAll(checkAdminPaths(mappings));
		problems.addAll(checkApiRoot(mappings));
		Set<Permission> used = used(handlers);
		DataSource ds = dataSource.getIfAvailable();
		if (ds != null) {
			problems.addAll(checkDb(ds));
			EnumSet<Permission> unused = EnumSet.allOf(Permission.class);
			unused.removeAll(used);
			unused.forEach(p -> log.warn("어느 API 도 쓰지 않는 권한: {}", p.key()));
		}
		if (!problems.isEmpty()) {
			throw new IllegalStateException("권한 표시 점검 실패:\n- " + String.join("\n- ", problems));
		}
	}

	/** 처리기마다 문제를 모은다. dev.ondolithj 밖은 허용 접두사에 없으면 문제다. */
	static List<String> check(Collection<HandlerMethod> handlers, Set<String> allowedPrefixes) {
		List<String> problems = new ArrayList<>();
		for (HandlerMethod h : handlers) {
			Class<?> type = h.getBeanType();
			Method m = h.getMethod();
			String name = type.getName() + "#" + m.getName();
			if (!type.getPackageName().startsWith("dev.ondolithj")) {
				if (allowedPrefixes.stream().noneMatch(type.getPackageName()::startsWith)) {
					problems.add(name + ": dev.ondolithj 밖의 처리기다(허용 목록에 없다)");
				}
				continue;
			}
			if (m.isAnnotationPresent(PreAuthorize.class) || type.isAnnotationPresent(PreAuthorize.class)) {
				problems.add(name + ": @PreAuthorize 를 직접 쓰지 않는다(@RequiresPermission·@AdminPermission·@PublicApi)");
				continue;
			}
			// 메타 애너테이션까지 센다 — 누가 만든 합성 애너테이션의 @PreAuthorize 가 클래스의 표시를 덮을 수 있다
			long marks = Stream.of(RequiresPermission.class, AdminPermission.class, PublicApi.class)
				.mapToLong(a -> Stream.of(m, type).filter(e -> AnnotatedElementUtils.hasAnnotation(e, a)).count()).sum();
			long rules = Stream.of(m, type)
				.mapToLong(e -> AnnotatedElementUtils.findAllMergedAnnotations(e, PreAuthorize.class).size()).sum();
			if (marks == 0) {
				problems.add(name + ": 권한 표시가 없다(@RequiresPermission·@AdminPermission·@PublicApi)");
			}
			else if (marks > 1 || rules > 1) {
				problems.add(name + ": 권한 표시가 둘 이상이다(메서드·클래스 합쳐 하나)");
			}
			// 보안 프록시가 가로채지 못해 표시가 있어도 열린다(일회성 확인: 익명 200)
			else if (Modifier.isPrivate(m.getModifiers())) {
				problems.add(name + ": private 처리 메서드는 권한 검사가 걸리지 않는다");
			}
			else if (Modifier.isFinal(m.getModifiers())) {
				problems.add(name + ": final 처리 메서드는 권한 검사가 걸리지 않는다");
			}
			else if (Modifier.isStatic(m.getModifiers())) {
				problems.add(name + ": static 처리 메서드는 권한 검사가 걸리지 않는다");
			}
		}
		return problems;
	}

	/**
	 * /api/admin/** 는 관리자 트리 진입 경계다(1-A 스펙 5절, D15 1.2) — 공개 표시를 달 수 없고,
	 * @RequiresPermission 은 ADMIN_ACCESS 만 된다. 다른 권한은 @AdminPermission 으로 admin.access 와 함께 요구한다(1-B1 스펙 3절).
	 */
	static List<String> checkAdminPaths(Map<RequestMappingInfo, HandlerMethod> mappings) {
		List<String> problems = new ArrayList<>();
		mappings.forEach((info, h) -> {
			boolean admin = info.getPatternValues().stream().anyMatch(p -> p.equals("/api/admin") || p.startsWith("/api/admin/"));
			boolean open = Stream.of(h.getMethod(), h.getBeanType())
				.anyMatch(e -> AnnotatedElementUtils.hasAnnotation(e, PublicApi.class));
			String name = h.getBeanType().getName() + "#" + h.getMethod().getName();
			if (admin && open) {
				problems.add(name + ": /api/admin 경로에 @PublicApi 를 달 수 없다");
			}
			RequiresPermission r = requires(h);
			if (admin && r != null && r.value() != Permission.ADMIN_ACCESS) {
				problems.add(name + ": /api/admin 경로의 @RequiresPermission 은 ADMIN_ACCESS 만 된다(@AdminPermission 을 쓴다)");
			}
		});
		return problems;
	}

	/**
	 * /api/ 바로 다음 마디의 변수·와일드카드(/api/{area}/x, /api/**, /api/admi?/x 처럼 첫 마디에 { · * · ? 가 든 것 —
	 * PathPattern 에서 ? 는 한 글자 와일드카드다)는 /api/admin/x 요청을 받지만
	 * 관리자 경로로 세지 않는다 — checkAdminPaths 를 비켜 간다(1-B2 스펙 7절 M-7). 더 깊은 마디의 변수는 된다.
	 */
	static List<String> checkApiRoot(Map<RequestMappingInfo, HandlerMethod> mappings) {
		List<String> problems = new ArrayList<>();
		mappings.forEach((info, h) -> {
			if (info.getPatternValues().stream().anyMatch(p -> API_ROOT_VARIABLE.matcher(p).find())) {
				problems.add(h.getBeanType().getName() + "#" + h.getMethod().getName()
						+ ": /api/ 바로 다음 마디에 변수·와일드카드를 쓸 수 없다");
			}
		});
		return problems;
	}

	static Set<Permission> used(Collection<HandlerMethod> handlers) {
		Set<Permission> used = EnumSet.noneOf(Permission.class);
		for (HandlerMethod h : handlers) {
			RequiresPermission r = requires(h);
			if (r != null) {
				used.add(r.value());
			}
			AdminPermission a = AnnotatedElementUtils.findMergedAnnotation(h.getMethod(), AdminPermission.class);
			if (a == null) {
				a = AnnotatedElementUtils.findMergedAnnotation(h.getBeanType(), AdminPermission.class);
			}
			if (a != null) {
				used.add(a.value());
			}
		}
		return used;
	}

	/** 메서드 먼저, 없으면 클래스. */
	private static RequiresPermission requires(HandlerMethod h) {
		RequiresPermission r = AnnotatedElementUtils.findMergedAnnotation(h.getMethod(), RequiresPermission.class);
		return r != null ? r : AnnotatedElementUtils.findMergedAnnotation(h.getBeanType(), RequiresPermission.class);
	}

	/** DB 의 권한 키 집합이 코드의 권한 목록과 같아야 한다(D15 P1: 코드가 정하고 마이그레이션이 심는다). */
	private static List<String> checkDb(DataSource ds) {
		Set<String> db = new HashSet<>();
		try (Connection c = ds.getConnection(); ResultSet r = c.createStatement().executeQuery("SELECT key FROM permissions")) {
			while (r.next()) {
				db.add(r.getString(1));
			}
		}
		catch (SQLException e) {
			return List.of("permissions 표를 읽지 못했다: " + e.getMessage());
		}
		Set<String> code = Arrays.stream(Permission.values()).map(Permission::key).collect(Collectors.toSet());
		List<String> problems = new ArrayList<>();
		db.stream().filter(k -> !code.contains(k)).sorted()
			.forEach(k -> problems.add("DB 에만 있는 권한: " + k + "(코드의 Permission 에 없다)"));
		code.stream().filter(k -> !db.contains(k)).sorted()
			.forEach(k -> problems.add("코드에만 있는 권한: " + k + "(마이그레이션이 심지 않았다)"));
		return problems;
	}
}
