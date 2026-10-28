package com.carddemo.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.carddemo.test.Covers;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Module boundaries that make selective conversion possible (architecture.md section 3): a bounded
 * context reaches another context only through that context's published {@code api} package.
 */
class ModuleBoundariesTest {

    static final List<String> CONTEXTS = List.of("iam", "card", "account", "transaction", "billpay", "refdata",
        "reporting", "cycle", "dataexchange", "authorization", "inquiry");

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.carddemo");
    }

    static String contextOf(String pkg) {
        for (String c : CONTEXTS) {
            String p = "com.carddemo." + c;
            if (pkg.equals(p) || pkg.startsWith(p + ".")) {
                return c;
            }
        }
        return null;
    }

    @Test
    @Covers({"ARCH-BOUNDARIES"})
    void contexts_use_each_other_only_through_api_packages() {
        ArchCondition<JavaClass> onlyApi = new ArchCondition<>("depend on other contexts only through their api package") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String own = contextOf(item.getPackageName());
                for (Dependency d : item.getDirectDependenciesFromSelf()) {
                    String pkg = d.getTargetClass().getPackageName();
                    String target = contextOf(pkg);
                    if (target != null && !target.equals(own) && !pkg.startsWith("com.carddemo." + target + ".api")) {
                        events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                    }
                }
            }
        };
        classes().that().resideInAnyPackage(CONTEXTS.stream().map(c -> "com.carddemo." + c + "..").toArray(String[]::new))
            .should(onlyApi).check(classes);
    }

    @Test
    @Covers({"ARCH-BOUNDARIES"})
    void shared_kernel_depends_on_no_context() {
        noClasses().that().resideInAPackage("com.carddemo.common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(CONTEXTS.stream().map(c -> "com.carddemo." + c + "..").toArray(String[]::new))
            .check(classes);
    }

    @Test
    @Covers({"ARCH-BOUNDARIES"})
    void published_api_packages_hold_only_interfaces_and_records() {
        classes().that().resideInAnyPackage(CONTEXTS.stream().map(c -> "com.carddemo." + c + ".api..").toArray(String[]::new))
            .should().beInterfaces().orShould().beRecords()
            .allowEmptyShould(true)
            .check(classes);
    }
}
