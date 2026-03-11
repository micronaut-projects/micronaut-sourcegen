/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.Delegate;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodDef.MethodDefBuilder;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeDef.TypeVariable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The visitor that is generation a delegate.
 *
 * @author Andriy Dmytruk
 * @since 1.3
 */
@Internal
public final class DelegateAnnotationVisitor implements TypeElementVisitor<Delegate, Object> {

    private static final String DELEGATE_TYPE_MEMBER = "type";
    private static final String DELEGATEE_MEMBER = "delegatee";
    private static final String NAME_SUFFIX = "Delegate";

    private final Set<String> processed = new HashSet<>();

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void start(VisitorContext visitorContext) {
        processed.clear();
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of(Delegate.class.getName());
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        AnnotationValue<?> annotation = element.getAnnotation(Delegate.class);
        if (annotation == null) {
            throw new ProcessingException(element, "Element " + element.getName() + " is not annotated with @" + Delegate.class.getSimpleName());
        }
        Optional<Class<?>> type = annotation.classValue(DELEGATE_TYPE_MEMBER);

        if (type.isPresent() && !type.get().equals(Void.class)) {
            ClassElement typeElement = context.getClassElement(type.get()).orElseThrow(
                () -> new ProcessingException(element, "Could not find required type " + type.get().getName()));
            createDelegate(typeElement, context);
        } else {
            createDelegate(element, context);
        }
    }

    private void createDelegate(ClassElement element, VisitorContext context) {
        if (processed.contains(element.getName())) {
            return;
        }
        if (!element.isInterface()) {
            throw new ProcessingException(element, "Only interfaces are supported for delegate creation. But '"
                + element.getName() + "' is annotated with @Delegate");
        }

        try {
            String simpleName = element.getSimpleName() + NAME_SUFFIX;
            String delegateClassName = element.getPackageName() + "." + simpleName;

            ClassTypeDef typeDef = ClassTypeDef.of(element);
            ClassDefBuilder delegate = ClassDef.builder(delegateClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);

            List<TypeVariable> typeVariables = new ArrayList<>();
            if (!element.getTypeArguments().isEmpty()) {
                element.getTypeArguments().forEach(
                    (k, v) -> typeVariables.add(TypeVariable.of(k, v))
                );
                typeDef = TypeDef.parameterized(
                    typeDef,
                    element.getTypeArguments().keySet().stream().<TypeDef>map(TypeVariable::new).toList()
                );
            }
            typeVariables.forEach(delegate::addTypeVariable);
            FieldDef delegateField = FieldDef.builder(DELEGATEE_MEMBER).ofType(typeDef).build();

            delegate.addSuperinterface(typeDef)
                .addField(delegateField)
                .addAllFieldsConstructor();

            addDelegateMethods(
                element,
                delegate,
                delegateField,
                typeVariables.stream()
                .collect(Collectors.toMap(TypeVariable::name, v -> v))
            );

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            ClassDef builderDef = delegate.build();
            processed.add(element.getName());

            sourceGenerator.write(builderDef, context, element);
        } catch (ProcessingException e) {
            throw e;
        } catch (Exception e) {
            SourceGenerators.handleFatalException(
                element,
                Delegate.class,
                e,
                (exception -> {
                    processed.remove(element.getName());
                    throw exception;
                })
            );
        }
    }

    private void addDelegateMethods(ClassElement element,
                                    ClassDefBuilder builder,
                                    FieldDef delegateField,
                                    Map<String, TypeDef> resolvedTypeVariables) {
        for (MethodElement method: element.getMethods()) {
            if (method.isPrivate()) {
                continue;
            }

            MethodDefBuilder methodBuilder = MethodDef.override(method, resolvedTypeVariables::get);
            builder.addMethod(methodBuilder.build((aThis, methodParameters) -> {
                ExpressionDef.InvokeInstanceMethod delegateInvocation = aThis.field(delegateField)
                    .invoke(method.getName(), TypeDef.of(method.getGenericReturnType()), methodParameters);
                if (method.getReturnType().isVoid()) {
                    return delegateInvocation;
                } else {
                    return delegateInvocation.returning();
                }
            }));
        }
    }

}
