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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.GenerationScope;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The value of a constant variable a qualified name reads (JLS 4.12.4): a {@code static final} field of a primitive
 * type or {@code String} initialized with a constant expression, which javac folds into the expressions that read it,
 * so that a loop over it cannot complete.
 *
 * @since 2.3
 */
@Internal
final class JavaConstantFields {

    private static final Map<Field, Optional<Object>> COMPILED = new ConcurrentHashMap<>();

    private JavaConstantFields() {
    }

    /**
     * @param field     The static field
     * @param evaluator Evaluates the initializer of a generated field as a constant expression
     * @param scope     The scope of the file being written, whose definitions declare a generated field and whose
     *                  context looks up a field only the compiler knows
     * @return The value the field is a constant variable of, or {@code null} where it is none
     */
    @Nullable
    static Object valueOf(VariableDef.StaticField field, Function<ExpressionDef, @Nullable Object> evaluator,
                          GenerationScope scope) {
        ObjectDef definition = scope.definitionOf(field.ownerType());
        if (definition != null) {
            return generatedValue(definition, field.name(), evaluator);
        }
        Class<?> owner = JavaTypes.loaded(field.ownerType(), scope.typeLookup());
        if (owner != null) {
            return compiledValue(owner, field.name());
        }
        VisitorContext context = scope.visitorContext();
        if (context == null) {
            return null;
        }
        return context.getClassElement(field.ownerType().getName())
            .flatMap(element -> element.getEnclosedElements(ElementQuery.ALL_FIELDS.named(field.name())).stream().findFirst())
            .filter(element -> element.isStatic() && element.isFinal())
            .map(FieldElement::getConstantValue)
            .orElse(null);
    }

    @Nullable
    private static Object generatedValue(ObjectDef definition, String name, Function<ExpressionDef, @Nullable Object> evaluator) {
        List<FieldDef> fields = switch (definition) {
            case ClassDef classDef -> classDef.getFields();
            case EnumDef enumDef -> enumDef.getFields();
            default -> List.of();
        };
        for (FieldDef field : fields) {
            if (field.getName().equals(name) && field.getModifiers().containsAll(List.of(Modifier.STATIC, Modifier.FINAL))
                && (field.getType().isPrimitive() || TypeDef.STRING.equals(field.getType()))) {
                return field.getInitializer().map(evaluator).orElse(null);
            }
        }
        return null;
    }

    /**
     * The value a compiled field is a constant of, read from its {@code ConstantValue} attribute: reflection does not
     * tell a constant variable from a field its class initializes.
     */
    @Nullable
    private static Object compiledValue(Class<?> owner, String name) {
        Field field;
        try {
            field = owner.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !java.lang.reflect.Modifier.isFinal(field.getModifiers())
            || !(field.getType().isPrimitive() || field.getType() == String.class)) {
            return null;
        }
        return COMPILED.computeIfAbsent(field, JavaConstantFields::readConstant).orElse(null);
    }

    private static Optional<Object> readConstant(Field field) {
        Class<?> declaring = field.getDeclaringClass();
        String binaryName = declaring.getName();
        try (InputStream in = declaring.getResourceAsStream(binaryName.substring(binaryName.lastIndexOf('.') + 1) + ".class")) {
            if (in == null) {
                return Optional.empty();
            }
            ClassModel model = ClassFile.of().parse(in.readAllBytes());
            for (FieldModel candidate : model.fields()) {
                if (candidate.fieldName().equalsString(field.getName())) {
                    return candidate.findAttribute(Attributes.constantValue())
                        .map(attribute -> converted(attribute.constant().constantValue(), field.getType()));
                }
            }
            return Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * A constant as the field types it: the class file holds a boolean, a char, a byte and a short as an int.
     */
    private static Object converted(Object value, Class<?> type) {
        if (!(value instanceof Integer number)) {
            return value;
        }
        if (type == boolean.class) {
            return number != 0;
        }
        if (type == char.class) {
            return (char) number.intValue();
        }
        if (type == byte.class) {
            return number.byteValue();
        }
        if (type == short.class) {
            return number.shortValue();
        }
        return number;
    }
}
