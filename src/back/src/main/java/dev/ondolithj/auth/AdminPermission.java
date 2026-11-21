package dev.ondolithj.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 관리자 트리의 리소스 권한: admin.access 와 이 권한을 함께 요구한다(1-B1 스펙 3절).
 * 식에 admin.access 를 박아 두어, 리소스 권한만 받은 역할이 관리자 경로에 들어오지 못한다.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('ADMIN_ACCESS') and hasAuthority('{value}')")
public @interface AdminPermission {

	Permission value();
}
