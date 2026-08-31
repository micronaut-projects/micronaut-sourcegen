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
 * bridge carrying the erased signature. The Java compiler adds those bridges when the source generators
 * are used, the bytecode writer needs them declared as a {@link io.micronaut.sourcegen.model.BridgeDef}.
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

        ClassTypeDef holderType = ClassTypeDef.of(packageName + ".GenericHolder");
        sourceGenerator.write(genericHolder(holderType), context, element);
        sourceGenerator.write(stringHolder(packageName, holderType), context, element);
        sourceGenerator.write(lengthFunction(packageName), context, element);
        sourceGenerator.write(lengthComparator(packageName), context, element);

        ClassTypeDef handlerType = ClassTypeDef.of(packageName + ".ThrowingHandler");
        sourceGenerator.write(throwingHandler(handlerType), context, element);
        sourceGenerator.write(stringHandler(packageName, handlerType), context, element);
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
    private ClassDef stringHandler(String packageName, ClassTypeDef handlerType) {
        TypeDef stringType = TypeDef.of(String.class);
        return ClassDef.builder(packageName + ".StringHandler")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(handlerType, stringType))
            .addMethod(MethodDef.builder("handle")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addAnnotation(Deprecated.class)
                .addParameter(ParameterDef.builder("value", stringType)
                    .addAnnotation(Deprecated.class)
                    .build())
                .returns(stringType)
                .addThrows(TypeDef.of(IOException.class))
                .addBridge(TypeDef.OBJECT, List.of(TypeDef.OBJECT))
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
    private ClassDef stringHolder(String packageName, ClassTypeDef holderType) {
        TypeDef stringType = TypeDef.of(String.class);
        FieldDef valueField = FieldDef.builder("value", stringType)
            .addModifiers(Modifier.PRIVATE)
            .build();
        return ClassDef.builder(packageName + ".StringHolder")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(holderType, stringType))
            .addField(valueField)
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(stringType)
                .addCovariantReturnBridge(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> aThis.field(valueField).returning()))
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", stringType)
                .returns(TypeDef.VOID)
                .addBridge(TypeDef.VOID, List.of(TypeDef.OBJECT))
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
                .addBridge(TypeDef.OBJECT, List.of(TypeDef.OBJECT))
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
                .addBridge(TypeDef.Primitive.INT, List.of(TypeDef.OBJECT, TypeDef.OBJECT))
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke("compareTo", TypeDef.Primitive.INT, methodParameters.get(1))
                    .returning()))
            .build();
    }

}
