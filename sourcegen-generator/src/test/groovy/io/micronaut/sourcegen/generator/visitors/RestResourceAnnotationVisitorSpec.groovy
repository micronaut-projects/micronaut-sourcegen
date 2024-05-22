package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.annotation.processing.test.JavaFileObjectClassLoader
import io.micronaut.annotation.processing.test.JavaFileObjects
import io.micronaut.annotation.processing.test.JavaParser
import io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder

import javax.tools.JavaFileObject

class RestResourceAnnotationVisitorSpec extends AbstractTypeElementSpec {

    void "test RestResource"() {
        given:
        AbstractAnnotationMetadataBuilder.clearMutated()

        JavaParser parser = newJavaParser()
        List<? extends JavaFileObject> files = new ArrayList<>();
        JavaFileObject book = JavaFileObjects.forSourceString("test.Book", """
package test;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

@MappedEntity
public record Book(@GeneratedValue @Id @Nullable Long id, String title) {
}
        """)
        JavaFileObject bookRepository =JavaFileObjects.forSourceString("test.BookRepository", """
package test;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

@JdbcRepository(dialect = Dialect.H2)
public interface BookRepository extends CrudRepository<Book, Long> {
}
        """)
        JavaFileObject bookRestResource = JavaFileObjects.forSourceString("test.BookRestResource", """
package test;
import io.micronaut.sourcegen.annotations.RestResource;
@RestResource(repository = BookRepository.class)
public interface BookRestResource  {
}
        """)

        files.addAll(parser.generate(book, bookRepository, bookRestResource))
        ClassLoader classLoader = new JavaFileObjectClassLoader(files)

        when:
        classLoader.loadClass("test.BookController")

        then:
        noExceptionThrown()

        cleanup:
        parser.close()
    }

}
