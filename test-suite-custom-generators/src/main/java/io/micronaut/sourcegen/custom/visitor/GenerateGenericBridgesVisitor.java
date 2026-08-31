/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.custom.visitor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateGenericBridges;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.NonNull;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Generates types whose supertypes are generic, so that every method implementing one of them needs a
 * bridge carrying the erased signature. The methods carry no bridge information: the Java compiler adds
 * bridges when the source generators are used, and the bytecode writer resolves them from the generic
 * supertypes. The supertype references therefore carry the parent definitions — the generated parents
 * as their {@code ClassDef}, the source parent as its {@code ClassElement}, and the JDK interfaces as
 * their {@code Class}.
 */
@Internal
public final class GenerateGenericBridgesVisitor implements TypeElementVisitor<GenerateGenericBridges, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        String packageName = element.getPackageName();

        ClassDef genericHolder = genericHolder(ClassTypeDef.of(packageName + ".GenericHolder"));
        sourceGenerator.write(genericHolder, context, element);
        sourceGenerator.write(stringHolder(packageName, genericHolder), context, element);
        sourceGenerator.write(numberHolder(packageName, genericHolder), context, element);
        sourceGenerator.write(lengthFunction(packageName), context, element);
        sourceGenerator.write(lengthComparator(packageName), context, element);

        InterfaceDef throwingHandler = throwingHandler(ClassTypeDef.of(packageName + ".ThrowingHandler"));
        sourceGenerator.write(throwingHandler, context, element);
        sourceGenerator.write(stringHandler(packageName, throwingHandler), context, element);

        ClassElement sourceHolder = context.getClassElement(packageName + ".SourceValueHolder").orElse(null);
        if (sourceHolder != null) {
            sourceGenerator.write(stringSourceHolder(packageName, sourceHolder), context, element);
        }
    }

    /**
     * {@code class StringSourceHolder extends SourceValueHolder<String>}, extending a hand-written
     * generic source class, so the hierarchy is only available as a {@code ClassElement}.
     *
     * @param packageName  The package
     * @param sourceHolder The hand-written generic super class
     * @return The definition
     */
    private ClassDef stringSourceHolder(String packageName, ClassElement sourceHolder) {
        TypeDef stringType = TypeDef.of(String.class);
        FieldDef valueField = FieldDef.builder("value", stringType)
            .addModifiers(Modifier.PRIVATE)
            .build();
        return ClassDef.builder(packageName + ".StringSourceHolder")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(sourceHolder), stringType))
            .addField(valueField)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", stringType)
                .returns(stringType)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    aThis.field(valueField).put(methodParameters.get(0)),
                    aThis.field(valueField).returning())))
            .build();
    }

    /**
     * {@code interface ThrowingHandler<T>} whose method declares a checked exception.
     *
     * @param handlerType The type of the generated interface
     * @return The definition
     */
    private InterfaceDef throwingHandler(ClassTypeDef handlerType) {
        return InterfaceDef.builder(handlerType.getName())
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("handle")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T"))
                .returns(TypeDef.variable("T"))
                .addThrows(TypeDef.of(IOException.class))
                .build())
            .build();
    }

    /**
     * {@code class StringHandler implements ThrowingHandler<String>}, whose bridge has to repeat the
     * declared exception and the annotations of the method it delegates to.
     *
     * @param packageName The package
     * @param handlerType The raw type of the generic interface
     * @return The definition
     */
    private ClassDef stringHandler(String packageName, InterfaceDef handlerDef) {
        TypeDef stringType = TypeDef.of(String.class);
        return ClassDef.builder(packageName + ".StringHandler")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(handlerDef), stringType))
            .addMethod(MethodDef.builder("handle")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addAnnotation(Deprecated.class)
                .addParameter(ParameterDef.builder("value", stringType)
                    .addAnnotation(Deprecated.class)
                    .build())
                .returns(stringType)
                .addThrows(TypeDef.of(IOException.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();
    }

    /**
     * {@code abstract class GenericHolder<T>} whose two abstract methods erase to {@code Object}.
     *
     * @param holderType The type of the generated class
     * @return The definition
     */
    private ClassDef genericHolder(ClassTypeDef holderType) {
        return ClassDef.builder(holderType.getName())
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.variable("T"))
                .build())
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T"))
                .returns(TypeDef.VOID)
                .build())
            .build();
    }

    /**
     * {@code class StringHolder extends GenericHolder<String>}, needing a bridge for the covariant
     * {@code get} and one for the erased parameter of the void {@code set}.
     *
     * @param packageName The package
     * @param holderType  The raw type of the generic super class
     * @return The definition
     */
    private ClassDef stringHolder(String packageName, ClassDef holderDef) {
        TypeDef stringType = TypeDef.of(String.class);
        FieldDef valueField = FieldDef.builder("value", stringType)
            .addModifiers(Modifier.PRIVATE)
            .build();
        return ClassDef.builder(packageName + ".StringHolder")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(holderDef), stringType))
            .addField(valueField)
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(stringType)
                .build((aThis, methodParameters) -> aThis.field(valueField).returning()))
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", stringType)
                .returns(TypeDef.VOID)
                .build((aThis, methodParameters) -> aThis.field(valueField).put(methodParameters.get(0))))
            .build();
    }

    /**
     * {@code class NumberHolder<T extends Number> extends GenericHolder<T>}: the declaring type binds
     * the parent's variable with its own bounded variable, so the methods erase to {@code Number} while
     * their bridges erase to the parent's {@code Object}.
     *
     * @param packageName The package
     * @param holderDef   The generic super class
     * @return The definition
     */
    private ClassDef numberHolder(String packageName, ClassDef holderDef) {
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        FieldDef valueField = FieldDef.builder("value", variable)
            .addModifiers(Modifier.PRIVATE)
            .build();
        return ClassDef.builder(packageName + ".NumberHolder")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .superclass(TypeDef.parameterized(ClassTypeDef.of(holderDef), variable))
            .addField(valueField)
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(variable)
                .build((aThis, methodParameters) -> aThis.field(valueField).returning()))
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", variable)
                .returns(TypeDef.VOID)
                .build((aThis, methodParameters) -> aThis.field(valueField).put(methodParameters.get(0))))
            .build();
    }

    /**
     * {@code class LengthFunction implements Function<String, Integer>}, needing a bridge whose
     * parameter and return type are both erased to {@code Object}.
     *
     * @param packageName The package
     * @return The definition
     */
    private ClassDef lengthFunction(String packageName) {
        TypeDef stringType = TypeDef.of(String.class);
        TypeDef integerType = TypeDef.of(Integer.class);
        return ClassDef.builder(packageName + ".LengthFunction")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), stringType, integerType))
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", stringType)
                .returns(integerType)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke("length", TypeDef.Primitive.INT)
                    .cast(integerType)
                    .returning()))
            .build();
    }

    /**
     * {@code class LengthComparator implements Comparator<String>}, needing a bridge with two erased
     * parameters and a primitive return type.
     *
     * @param packageName The package
     * @return The definition
     */
    private ClassDef lengthComparator(String packageName) {
        TypeDef stringType = TypeDef.of(String.class);
        return ClassDef.builder(packageName + ".LengthComparator")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparator.class), stringType))
            .addMethod(MethodDef.builder("compare")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("first", stringType)
                .addParameter("second", stringType)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke("compareTo", TypeDef.Primitive.INT, methodParameters.get(1))
                    .returning()))
            .build();
    }

}
