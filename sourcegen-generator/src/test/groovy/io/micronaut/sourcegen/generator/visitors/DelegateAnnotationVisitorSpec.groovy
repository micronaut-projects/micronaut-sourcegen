package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec;

class DelegateAnnotationVisitorSpec extends AbstractTypeElementSpec {

    void "test delegate"() {
        given:
        var classLoader = buildClassLoader("test.Worker", """
        package test;
        import io.micronaut.sourcegen.annotations.Delegate;
        import java.util.List;

        @Delegate
        public interface Worker<T extends String> {
            String name();
            double tasksPerDay();
            boolean canComplete(List<T> tasks);
            T currentTask();
            List<String> competencies();
        }
        """)
        var workerDelegateClass = classLoader.loadClass("test.WorkerDelegate")

        expect:
        workerDelegateClass != null
    }

}
