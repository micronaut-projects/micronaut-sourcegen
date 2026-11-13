/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.micronaut.sourcegen.bytecode.jdk.DiffModels.STRING;
import static io.micronaut.sourcegen.model.ExpressionDef.constant;

/**
 * The family of the shapes the bytecode writers disagreed on: a constructor delegating to {@code this(...)} in a class
 * with an initialized field, and a reference of variable arity bound to a field that is assigned after the reference
 * is created.
 */
final class DiffWriterParity {

    private DiffWriterParity() {
    }

    static void generate(DiffModels m) {
        constructorDelegation(m);
        varargsReferenceBoundToAField(m);
    }

    /**
     * {@code List items = new ArrayList(); M() { items.add("one"); } M(int n) { this(); } run() { return new
     * M(1).items.toString(); }}: the initializer runs once, in the constructor delegated to.
     */
    static void constructorDelegation(DiffModels m) {
        String name = m.nextName();
        ClassTypeDef self = ClassTypeDef.of(name);
        FieldDef items = FieldDef.builder("items", TypeDef.parameterized(ArrayList.class, String.class)).addModifiers(Modifier.PUBLIC)
            .initializer(ClassTypeDef.of(ArrayList.class).instantiate()).build();
        MethodDef plain = MethodDef.constructor().addModifiers(Modifier.PUBLIC)
            .build((aThis, p) -> aThis.field(items).invoke("add", TypeDef.Primitive.BOOLEAN, constant("one")));
        MethodDef delegating = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("n", TypeDef.Primitive.INT)
            .build((aThis, p) -> aThis.invokeConstructor(plain));
        ClassDef.ClassDefBuilder b = ClassDef.builder(name).addModifiers(Modifier.PUBLIC).addField(items)
            .addMethod(plain).addMethod(delegating)
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build((aThis, p) -> self.instantiate(List.of(TypeDef.Primitive.INT), constant(1)).field(items)
                    .invoke("toString", TypeDef.STRING).returning()));
        m.addClass("constructor delegating to this() runs the field initializers once", b, new Class<?>[0],
            List.<Object[]>of(new Object[0]));
    }

    /**
     * {@code Function<String, String> f = this.joiner::join; this.joiner = new Joiner("-second"); return f.apply(p0);}:
     * the reference reads the field where it is created.
     */
    static void varargsReferenceBoundToAField(DiffModels m) {
        String name = m.nextName();
        ClassTypeDef joinerType = ClassTypeDef.of(Joiner.class);
        FieldDef joiner = FieldDef.builder("joiner", joinerType).addModifiers(Modifier.PUBLIC)
            .initializer(joinerType.instantiate(constant("-first"))).build();
        ClassTypeDef function = TypeDef.parameterized(Function.class, String.class, String.class);
        MethodDef join;
        try {
            join = MethodDef.of(Joiner.class.getMethod("join", String[].class));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        ClassDef.ClassDefBuilder b = ClassDef.builder(name).addModifiers(Modifier.PUBLIC).addField(joiner)
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).addParameter("p0", TypeDef.STRING).returns(TypeDef.STRING)
                .build((aThis, p) -> function.methodReference(aThis.field(joiner), join).newLocal("f", f -> StatementDef.multi(
                    aThis.field(joiner).put(joinerType.instantiate(constant("-second"))),
                    f.invoke("apply", TypeDef.OBJECT, p.getFirst()).cast(TypeDef.STRING).returning()))));
        m.addClass("varargs reference bound to a field reads it where it is created", b, new Class<?>[] {String.class},
            STRING.samples().stream().map(sample -> new Object[] {sample}).toList());
    }

    /**
     * A receiver of a method of variable arity.
     */
    public static final class Joiner {
        private final String suffix;

        public Joiner(String suffix) {
            this.suffix = suffix;
        }

        public String join(String... parts) {
            return String.join("+", parts) + suffix;
        }
    }
}
