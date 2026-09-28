package com.yeso.backend;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.auth.infrastructure.UserRepository;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 모듈·의존성 규칙(docs/conventions/모듈-의존성.md)을 코드로 강제한다.
 * 모듈 간 호출은 상대 모듈의 application 공개 계약을 쓰고, infrastructure(Repository 등)를 직접 쓰지 않는다.
 */
@AnalyzeClasses(packages = "com.yeso.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * 모듈 밖에서는 그 모듈의 infrastructure를 직접 쓰지 않는다. {@code mvpExceptions}는
     * 모듈-의존성.md "현재 허용하는 예외"에 적힌 회원·지역 조회용 Repository뿐이다.
     * MVP 이후 각 모듈 application의 공개 조회 계약으로 옮기면 예외를 지운다.
     */
    private static ArchRule onlyOwnerUsesInfrastructure(String module, Class<?>... mvpExceptions) {
        String base = "com.yeso.backend." + module;
        DescribedPredicate<JavaClass> forbidden = resideInAPackage(base + ".infrastructure..");
        for (Class<?> allowed : mvpExceptions) {
            forbidden = forbidden.and(not(type(allowed)));
        }
        return noClasses().that().resideOutsideOfPackage(base + "..")
                .should().dependOnClassesThat(forbidden)
                .as(module + " 모듈 밖에서는 " + module + ".infrastructure를 직접 쓰지 않는다")
                .because("모듈 간 호출은 application 공개 계약을 쓴다");
    }

    @ArchTest
    static final ArchRule auth = onlyOwnerUsesInfrastructure("auth", UserRepository.class);

    @ArchTest
    static final ArchRule profile = onlyOwnerUsesInfrastructure("profile");

    @ArchTest
    static final ArchRule trip = onlyOwnerUsesInfrastructure("trip");

    @ArchTest
    static final ArchRule attraction = onlyOwnerUsesInfrastructure("attraction", RegionRepository.class);

    @ArchTest
    static final ArchRule domainIsIndependent = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..", "..presentation..")
            .as("domain은 application·infrastructure·presentation을 쓰지 않는다");

    @ArchTest
    static final ArchRule sharedIsIndependent = noClasses().that().resideInAPackage("com.yeso.backend.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.yeso.backend.auth..", "com.yeso.backend.profile..",
                    "com.yeso.backend.trip..", "com.yeso.backend.attraction..")
            .as("shared는 특정 도메인 모듈을 쓰지 않는다");
}
