package io.micronaut.sourcegen.generator.visitors

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.core.beans.BeanIntrospection

class IncludeBuilderInSourceGenSpec extends AbstractTypeElementSpec {

    void "test include builder methods in generated record"() {
        given:
        def introspection = buildBeanIntrospection("test.TestRecord", '''
package test;

import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.sourcegen.generator.visitors.TestAnn;
import java.util.List;
import java.util.Map;

@TestAnn
interface Test {
    String name();
    int age();
    List<String> friends();
    Map<String, Object> metadata();
    int[] bytes();

    boolean active();
    long x();
    float y();
    char c();

    String[] stuff();

    Map<String, Stuff> moreStuff();
}

interface Stuff {}
''')
        expect:
        introspection != null

        introspection.getBeanType().builder().build().x == 0
        introspection.getBeanType().builder().build().c == '\u0000'
        introspection.getBeanType().builder().y(1000).build().y == 1000
        introspection.getBeanType().builder().active(true).build().active == true
        introspection.getBeanType().builder().build().active == false
        introspection.getBeanType().builder().active(true).build().active == true
        introspection.getBeanType().builder().build().active == false
        introspection.getBeanType().builder().name("foo").age(30).build().name == 'foo'
        introspection.getBeanType().builder().name("foo").age(10).build().age == 10
        introspection.getBeanType().builder().name("foo").age(30).build().explicitlySet() == ["name", "age" ] as Set
        introspection.getBeanType().builder().name("foo").age(30).build().withName("bob").name == 'bob'
    }
}
