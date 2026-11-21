package dev.ondolithj.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/** 이 권한이 있어야 부를 수 있다. enum 을 받아 권한 이름 오타를 컴파일 때 막는다(애너테이션 템플릿). */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('{value}')")
public @interface RequiresPermission {

	Permission value();
}
