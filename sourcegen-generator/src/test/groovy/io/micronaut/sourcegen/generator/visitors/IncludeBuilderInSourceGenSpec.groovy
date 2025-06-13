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
    String[] stuff();

    Map<String, Stuff> moreStuff();
}

interface Stuff {}
''')
        expect:
        introspection != null
        introspection.getBeanType().builder().name("foo").age(30).build().name == 'foo'
        introspection.getBeanType().builder().name("foo").build().age == 10
        introspection.getBeanType().builder().name("foo").age(30).build().explicitlySet() == ["name", "age" ] as Set
        introspection.getBeanType().builder().name("foo").age(30).build().withName("bob").name == 'bob'
    }
}
