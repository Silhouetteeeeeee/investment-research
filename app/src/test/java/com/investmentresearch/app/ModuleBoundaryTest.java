package com.investmentresearch.app;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.investmentresearch")
class ModuleBoundaryTest {
    @ArchTest static final ArchRule core_does_not_depend_on_data_or_app =
        noClasses().that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage("..data..", "..app..");
    @ArchTest static final ArchRule data_does_not_depend_on_app =
        noClasses().that().resideInAPackage("..data..")
            .should().dependOnClassesThat().resideInAPackage("..app..");
    @ArchTest static final ArchRule core_has_no_web_mybatis_or_jdbc =
        noClasses().that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..", "org.apache.ibatis..", "java.sql..");
    @ArchTest static final ArchRule agentscope_stays_in_core_agent =
        noClasses().that().resideInAPackage("..core..")
            .and().resideOutsideOfPackage("..core.agent..")
            .should().dependOnClassesThat().resideInAPackage("io.agentscope..");
}
