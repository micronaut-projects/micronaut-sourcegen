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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The conversions both bytecode writers emit, planned once as the {@link Step steps} each writer only emits: a
 * checkcast, an unboxing with the source's wrapper, a primitive conversion and a boxing - {@link #plan}.
 *
 * <p>The unboxing is javac's (JLS 5.1.8): a {@code Character} read as an {@code int} is {@code charValue()} widened, not
 * the {@code intValue()} of a {@code Number} a {@code Character} is not - for a {@code Character} parameter, and for
 * the {@code Character} a generic method returns where the call requests a primitive.</p>
 *
 * @since 2.3
 */
@Internal
public final class Conversions {

    private static final String NUMBER = "java/lang/Number";
    private static final String CHARACTER = "java/lang/Character";
    private static final String BOOLEAN = "java/lang/Boolean";
    private static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";

    private static final Map<String, TypeDef.Primitive> WRAPPERS = Map.of(
        "Ljava/lang/Boolean;", TypeDef.Primitive.BOOLEAN,
        "Ljava/lang/Byte;", TypeDef.Primitive.BYTE,
        "Ljava/lang/Character;", TypeDef.Primitive.CHAR,
        "Ljava/lang/Short;", TypeDef.Primitive.SHORT,
        "Ljava/lang/Integer;", TypeDef.Primitive.INT,
        "Ljava/lang/Long;", TypeDef.Primitive.LONG,
        "Ljava/lang/Float;", TypeDef.Primitive.FLOAT,
        "Ljava/lang/Double;", TypeDef.Primitive.DOUBLE
    );

    private Conversions() {
    }

    /**
     * The expression a cast converts: the last cast of a chain is the one written, as javac writes it. An inner
     * primitive cast of a reference would unbox and rebox it, turning a legitimate null into a NullPointerException; a
     * primitive cast of something that is not an {@code Object} is a real conversion and stays, and so does a cast
     * that checks a reference the outer cast does not, as javac writes {@code (Object) (Zeta[]) alphas}.
     *
     * @param cast      The cast
     * @param current   The definition being written, if any
     * @param enclosing The method being written, if any
     * @return The expression to write and convert to the type of the cast
     * @since 2.3
     */
    public static ExpressionDef castOperand(ExpressionDef.Cast cast, @Nullable ObjectDef current, @Nullable MethodDef enclosing) {
        return castOperand(cast, current, enclosing, EnclosingScope.NONE);
    }

    /**
     * The expression a cast converts, as {@link #castOperand(ExpressionDef.Cast, ObjectDef, MethodDef)} finds it, in the
     * enclosing scope of the class being written.
     *
     * @param cast           The cast
     * @param current        The definition being written, if any
     * @param enclosing      The method being written, if any
     * @param enclosingScope The enclosing scope of the class being written
     * @return The expression to write and convert to the type of the cast
     * @since 2.3
     */
    public static ExpressionDef castOperand(ExpressionDef.Cast cast,
                                            @Nullable ObjectDef current,
                                            @Nullable MethodDef enclosing,
                                            EnclosingScope enclosingScope) {
        TypeDef outer = cast.type();
        ExpressionDef result = cast.expressionDef();
        while (result instanceof ExpressionDef.Cast nested
            && !(nested.type().isPrimitive() && !nested.expressionDef().type().equals(TypeDef.OBJECT))
            && !checks(nested, outer, current, enclosing, enclosingScope)) {
            result = nested.expressionDef();
        }
        return result;
    }

    /**
     * Whether a cast of a chain checks what the cast after it does not: a narrowing of a reference to a type the
     * outer cast's is not a subtype of.
     */
    private static boolean checks(ExpressionDef.Cast inner,
                                  TypeDef outer,
                                  @Nullable ObjectDef current,
                                  @Nullable MethodDef enclosing,
                                  EnclosingScope enclosingScope) {
        return !inner.type().isPrimitive() && !inner.expressionDef().type().isPrimitive()
            && TypeUtils.checksReference(inner.expressionDef().type(), inner.type(), current, enclosing, enclosingScope)
            && (outer.isPrimitive() || TypeUtils.checksReference(outer, inner.type(), current, enclosing, enclosingScope));
    }

    /**
     * Plans the conversion of a value of one type to another, as the steps a writer emits in order.
     *
     * <ul>
     *     <li>A primitive to another: the {@link PrimitiveConversion}.</li>
     *     <li>A primitive to a reference: the {@link Box} of the primitive, then a {@link CheckCast} to the target where
     *     the wrapper is not known to be one. To the wrapper of another primitive that is no boolean: the
     *     {@link PrimitiveConversion} to that primitive, then its {@link Box}.</li>
     *     <li>A reference to a primitive: the {@link Unboxing} with the source's wrapper, then the
     *     {@link PrimitiveConversion} of the primitive it holds to the target where it is another.</li>
     *     <li>The wrapper of a number or a char to the wrapper of another: the {@link Unboxing} with the source's
     *     wrapper, the {@link PrimitiveConversion} and the {@link Box} of the target's primitive.</li>
     *     <li>A reference to another: a {@link CheckCast} to the target where the value is not known to be one.</li>
     * </ul>
     *
     * <p>Which checkcasts are redundant is where the writers differ: the {@link Checkcasts policy} keeps each one's.</p>
     *
     * @param from       The type of the value
     * @param to         The type to convert it to
     * @param current    The definition being written, if any
     * @param enclosing  The method being written, if any
     * @param checkcasts Which checkcasts to leave out as redundant
     * @return The steps, none where the value already is of the type
     * @since 2.3
     */
    public static List<Step> plan(TypeDef from,
                                  TypeDef to,
                                  @Nullable ObjectDef current,
                                  @Nullable MethodDef enclosing,
                                  Checkcasts checkcasts) {
        return plan(from, to, current, enclosing, checkcasts, EnclosingScope.NONE);
    }

    /**
     * Plans the conversion of a value of one type to another, as {@link #plan(TypeDef, TypeDef, ObjectDef, MethodDef,
     * Checkcasts)} does, in the enclosing scope of the class being written.
     *
     * @param from           The type of the value
     * @param to             The type to convert it to
     * @param current        The definition being written, if any
     * @param enclosing      The method being written, if any
     * @param checkcasts     Which checkcasts to leave out as redundant
     * @param enclosingScope The enclosing scope of the class being written
     * @return The steps, none where the value already is of the type
     * @since 2.3
     */
    public static List<Step> plan(TypeDef from,
                                  TypeDef to,
                                  @Nullable ObjectDef current,
                                  @Nullable MethodDef enclosing,
                                  Checkcasts checkcasts,
                                  EnclosingScope enclosingScope) {
        TypeDef source = ObjectDef.getContextualType(current, from);
        TypeDef target = ObjectDef.getContextualType(current, to);
        String sourceDescriptor = TypeUtils.getDescriptor(source, current, enclosing, enclosingScope);
        String targetDescriptor = TypeUtils.getDescriptor(target, current, enclosing, enclosingScope);
        boolean sourceReference = isReference(sourceDescriptor);
        boolean targetReference = isReference(targetDescriptor);
        List<Step> steps = new ArrayList<>(2);
        if (sourceReference && targetReference) {
            TypeDef.Primitive sourceWrapped = WRAPPERS.get(sourceDescriptor);
            TypeDef.Primitive targetWrapped = WRAPPERS.get(targetDescriptor);
            if (sourceWrapped != null && targetWrapped != null && !sourceWrapped.equals(targetWrapped)
                && isNumeric(sourceWrapped) && isNumeric(targetWrapped)) {
                // The box of another number or char holds the value converted to its primitive: a Byte converted to
                // a Long is unboxed, widened and boxed, as a byte converted to a Long is
                Unboxing unboxing = unboxing(sourceDescriptor, targetWrapped);
                steps.add(unboxing);
                if (!unboxing.unboxed().equals(targetWrapped)) {
                    steps.add(new PrimitiveConversion(unboxing.unboxed(), targetWrapped));
                }
                steps.add(new Box(targetWrapped));
                return steps;
            }
            if (checkcasts.checks(source, sourceDescriptor, target, targetDescriptor, false)) {
                steps.add(new CheckCast(targetDescriptor));
            }
            return steps;
        }
        if (sourceDescriptor.equals(targetDescriptor) || isVoid(sourceDescriptor) || isVoid(targetDescriptor)) {
            return steps;
        }
        if (!sourceReference && !targetReference) {
            steps.add(new PrimitiveConversion(TypeDef.primitive(sourceDescriptor), TypeDef.primitive(targetDescriptor)));
        } else if (sourceReference) {
            TypeDef.Primitive primitive = TypeDef.primitive(targetDescriptor);
            Unboxing unboxing = unboxing(sourceDescriptor, primitive);
            steps.add(unboxing);
            if (!unboxing.unboxed().equals(primitive)) {
                steps.add(new PrimitiveConversion(unboxing.unboxed(), primitive));
            }
        } else {
            TypeDef.Primitive primitive = TypeDef.primitive(sourceDescriptor);
            TypeDef.Primitive boxed = WRAPPERS.get(targetDescriptor);
            if (boxed != null && !boxed.equals(primitive) && isNumeric(boxed) && isNumeric(primitive)) {
                // The wrapper of another primitive holds the value converted to that primitive, as the Java source
                // converts a char branch of a conditional typed Integer to an int before boxing it
                steps.add(new PrimitiveConversion(primitive, boxed));
                steps.add(new Box(boxed));
                return steps;
            }
            steps.add(new Box(primitive));
            // The target may be narrower than the box, and may even be unrelated to it when the model casts through a
            // shared dispatch signature; a checkcast keeps that verifiable
            ClassTypeDef wrapper = primitive.wrapperType();
            if (checkcasts.checks(wrapper, TypeUtils.getDescriptor(wrapper, null, EnclosingScope.NONE), target, targetDescriptor, true)) {
                steps.add(new CheckCast(targetDescriptor));
            }
        }
        return steps;
    }

    /**
     * Whether a primitive is a number or a char: every one but a boolean.
     */
    private static boolean isNumeric(TypeDef.Primitive primitive) {
        return !primitive.equals(TypeDef.Primitive.BOOLEAN) && !primitive.equals(TypeDef.VOID);
    }

    private static boolean isReference(String descriptor) {
        return descriptor.charAt(0) == 'L' || descriptor.charAt(0) == '[';
    }

    private static boolean isVoid(String descriptor) {
        return descriptor.equals("V");
    }

    /**
     * Whether the model knows a reference of one type to be of another, so that a checkcast to it is left out: the
     * same type, or one a class, a compiled type or a generated class's superclass is assignable to.
     */
    private static boolean needsCast(TypeDef from, TypeDef to) {
        if (from.makeNullable().equals(to.makeNullable())) {
            return false;
        }
        if (from instanceof ClassTypeDef.Parameterized parameterized) {
            return needsCast(parameterized.rawType(), to);
        }
        if (to instanceof ClassTypeDef.Parameterized parameterized) {
            return needsCast(from, parameterized.rawType());
        }
        if (from instanceof ClassTypeDef.ClassElementType fromElement) {
            return needsCast(fromElement.classElement(), to);
        }
        if (from instanceof ClassTypeDef.JavaClass fromClass && to instanceof ClassTypeDef.JavaClass toClass) {
            return !toClass.type().isAssignableFrom(fromClass.type());
        }
        if (from instanceof ClassTypeDef.ClassDefType fromClassDef) {
            ClassTypeDef fromSuperclass = superclassOf(fromClassDef.objectDef());
            if (fromSuperclass != null) {
                return needsCast(fromSuperclass, to);
            }
        }
        return true;
    }

    private static boolean needsCast(ClassElement from, TypeDef to) {
        if (to instanceof ClassTypeDef.ClassElementType toElement) {
            return !from.isAssignable(toElement.classElement());
        }
        if (to instanceof ClassTypeDef.JavaClass toClass) {
            return !from.isAssignable(toClass.type());
        }
        if (to instanceof ClassTypeDef.ClassName || to instanceof ClassTypeDef.ClassDefType) {
            return !from.isAssignable(((ClassTypeDef) to).getName());
        }
        return true;
    }

    @Nullable
    private static ClassTypeDef superclassOf(ObjectDef objectDef) {
        if (objectDef instanceof ClassDef classDef) {
            return classDef.getSuperclass();
        }
        if (objectDef instanceof EnumDef) {
            return ClassTypeDef.of(Enum.class);
        }
        if (objectDef instanceof RecordDef) {
            return ClassTypeDef.of(Record.class);
        }
        return null;
    }

    /**
     * How a reference unboxes to a primitive. A reference is read as the box of the target: a {@code Boolean}, a
     * {@code Character}, or a {@code Number} for a numeric target - the numeric wrappers' own methods, which any number
     * converts with. A {@code Character} is no {@code Number}: it unboxes with its own method, and the char it holds
     * is converted to the target, as javac converts {@code Character} to {@code int}: {@code charValue()} widened. A
     * numeric wrapper read as a {@code char} unboxes to the primitive it holds, which is converted to a {@code char}.
     *
     * @param sourceDescriptor The erased descriptor of the reference
     * @param target           The primitive
     * @return The unboxing
     */
    public static Unboxing unboxing(String sourceDescriptor, TypeDef.Primitive target) {
        TypeDef.Primitive wrapped = WRAPPERS.get(sourceDescriptor);
        if (TypeDef.Primitive.CHAR.equals(wrapped) && !target.equals(TypeDef.Primitive.BOOLEAN)) {
            return new Unboxing(CHARACTER, TypeDef.Primitive.CHAR);
        }
        if (target.equals(TypeDef.Primitive.CHAR) && wrapped != null && isNumeric(wrapped)) {
            // A number converts to a char as the primitive it holds does: (char) 1000 of an Integer 1000
            return new Unboxing(NUMBER, wrapped);
        }
        if (target.equals(TypeDef.Primitive.BOOLEAN)) {
            return new Unboxing(BOOLEAN, target);
        }
        if (target.equals(TypeDef.Primitive.CHAR)) {
            return new Unboxing(CHARACTER, target);
        }
        return new Unboxing(NUMBER, target);
    }

    /**
     * The type to convert the result of a call from, where the call requests another type than its method declares:
     * the declared one, erased, but the wrapper the call returns where it requests a primitive of a generic return -
     * the {@code Character} a {@code <T> T identity(T)} returns for a {@code char} argument unboxes as a
     * {@code Character} to the {@code int} requested.
     *
     * @param declared   The erased return type the method is invoked with
     * @param requested  The type the call requests
     * @param owner      The type the method is invoked on
     * @param method     The invoked method
     * @param descriptor The descriptor the call is written with
     * @param values     The arguments
     * @param current    The definition being written, if any
     * @return The type to convert from
     */
    public static TypeDef convertedFrom(TypeDef declared,
                                        TypeDef requested,
                                        @Nullable TypeDef owner,
                                        MethodDef method,
                                        String descriptor,
                                        List<? extends ExpressionDef> values,
                                        @Nullable ObjectDef current) {
        if (declared.isPrimitive() || !(TypeHierarchy.unwrap(ObjectDef.getContextualType(current, requested)) instanceof TypeDef.Primitive)) {
            return declared;
        }
        ClassTypeDef returned = returnedType(owner, method, descriptor, values, current);
        return returned != null && WRAPPERS.containsKey(TypeUtils.getDescriptor(returned, null, EnclosingScope.NONE)) ? returned : declared;
    }

    /**
     * The type the call of a generic method returns where it is written, where the method declares it with a
     * variable: the type argument of the receiver's type for a variable of the class - {@code Character} of the
     * {@code T get()} of a {@code Supplier<Character>} - and the type of the arguments for a variable of the method -
     * {@code Character} of {@code identity('x')} for a {@code <T> T identity(T)}. That is how javac unboxes the value
     * a call requesting a primitive returns.
     *
     * @param owner      The type the method is invoked on
     * @param method     The invoked method
     * @param descriptor The descriptor the call is written with
     * @param values     The arguments
     * @param current    The definition being written, if any
     * @return The type, or {@code null} where it is not known or the method declares no variable as its return type
     */
    @Nullable
    public static ClassTypeDef returnedType(@Nullable TypeDef owner,
                                            MethodDef method,
                                            String descriptor,
                                            List<? extends ExpressionDef> values,
                                            @Nullable ObjectDef current) {
        if (owner == null || method.isConstructor()) {
            return null;
        }
        TypeDef ownerType = TypeHierarchy.unwrap(ObjectDef.getContextualType(current, owner));
        TypeDef raw = TypeOperations.rawClassOf(ownerType);
        if (!(raw instanceof ClassTypeDef.JavaClass javaClass) || !(ownerType instanceof ClassTypeDef ownerClass)) {
            return null;
        }
        Method declaration = declaration(javaClass.type(), method.getName(), descriptor.substring(0, descriptor.indexOf(')') + 1));
        if (declaration == null || !(declaration.getGenericReturnType() instanceof java.lang.reflect.TypeVariable<?> variable)) {
            return null;
        }
        if (variable.getGenericDeclaration() instanceof Method) {
            return inferred(declaration, variable, values, current);
        }
        if (variable.getGenericDeclaration() instanceof Class<?> declaring) {
            int index = Arrays.asList(declaring.getTypeParameters()).indexOf(variable);
            ClassTypeDef inherited = TypeHierarchy.asSupertype(ownerClass, declaring.getName(), null);
            if (index >= 0 && inherited instanceof ClassTypeDef.Parameterized parameterized
                && index < parameterized.typeArguments().size()) {
                return classOf(parameterized.typeArguments().get(index), current);
            }
        }
        return null;
    }

    /**
     * A variable of the method, inferred as the type of the arguments of the parameters it is declared as.
     */
    @Nullable
    private static ClassTypeDef inferred(Method declaration,
                                         java.lang.reflect.TypeVariable<?> variable,
                                         List<? extends ExpressionDef> values,
                                         @Nullable ObjectDef current) {
        Type[] parameters = declaration.getGenericParameterTypes();
        if (parameters.length != values.size()) {
            return null;
        }
        ClassTypeDef result = null;
        for (int i = 0; i < parameters.length; i++) {
            if (!variable.equals(parameters[i])) {
                continue;
            }
            ClassTypeDef argument = classOf(values.get(i).type(), current);
            if (argument == null || result != null && !result.getName().equals(argument.getName())) {
                // Arguments of different types infer their least upper bound, which no wrapper is
                return null;
            }
            result = argument;
        }
        return result;
    }

    @Nullable
    private static ClassTypeDef classOf(TypeDef type, @Nullable ObjectDef current) {
        TypeDef unwrapped = TypeHierarchy.unwrap(ObjectDef.getContextualType(current, type));
        if (unwrapped instanceof TypeDef.Primitive primitive) {
            return primitive.equals(TypeDef.VOID) ? null : primitive.wrapperType();
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return wildcard.upperBounds().size() == 1 ? classOf(wildcard.upperBounds().getFirst(), current) : null;
        }
        return unwrapped instanceof ClassTypeDef classTypeDef ? TypeOperations.rawClass(classTypeDef) : null;
    }

    /**
     * The method a call names, declared by the class or one of its supertypes, with the erased parameters it is
     * written with.
     */
    @Nullable
    private static Method declaration(Class<?> type, String name, String parameters) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && !method.isBridge() && !method.isSynthetic()
                    && parameters.equals(Arrays.stream(method.getParameterTypes())
                    .map(parameter -> TypeUtils.getDescriptor(TypeDef.of(parameter), null, EnclosingScope.NONE))
                    .collect(Collectors.joining("", "(", ")")))) {
                    return method;
                }
            }
            if (current.getSuperclass() != null) {
                queue.addLast(current.getSuperclass());
            }
            queue.addAll(Arrays.asList(current.getInterfaces()));
        }
        return null;
    }

    /**
     * An unboxing: a checkcast of the reference to the owner, which the method is invoked on, returning the primitive
     * the owner holds, and a conversion of that primitive to the target, where it is another.
     *
     * @param owner    The internal name of the class the reference is cast to and unboxed with
     * @param unboxed  The primitive the unboxing method returns
     */
    public record Unboxing(String owner, TypeDef.Primitive unboxed) implements Step {

        /**
         * @return The name of the unboxing method: {@code charValue} of a {@code Character}
         */
        public String method() {
            return unboxed.name() + "Value";
        }

        /**
         * @return The descriptor of the unboxing method: {@code ()C} of a {@code Character}
         */
        public String methodDescriptor() {
            return "()" + TypeUtils.getDescriptor(unboxed, null, EnclosingScope.NONE);
        }
    }

    /**
     * Which checkcasts of a conversion are left out as redundant. The two writers differ, and each keeps its own: the
     * ASM writer's output is compared instruction by instruction, and the JDK writer leaves out every cast its erasure
     * makes redundant to keep generated methods well inside the 64KB limit.
     *
     * @since 2.3
     */
    public enum Checkcasts {

        /**
         * The ASM writer's: a reference is cast unless the model knows its type to be the target's or assignable to
         * it, and a boxed value unless its wrapper is the target type itself - even {@code Object}.
         */
        MODEL {
            @Override
            boolean checks(TypeDef source, String sourceDescriptor, TypeDef target, String targetDescriptor, boolean boxed) {
                if (target.makeNullable().equals(source.makeNullable())) {
                    return false;
                }
                return boxed || needsCast(source, target);
            }
        },

        /**
         * The JDK writer's: a value is cast unless its erasure is the target's, or the target is {@code Object}.
         */
        ERASURE {
            @Override
            boolean checks(TypeDef source, String sourceDescriptor, TypeDef target, String targetDescriptor, boolean boxed) {
                return !targetDescriptor.equals(OBJECT_DESCRIPTOR) && !targetDescriptor.equals(sourceDescriptor);
            }
        };

        /**
         * @param source           The type of the reference, the wrapper of a boxed primitive
         * @param sourceDescriptor Its erasure
         * @param target           The type to convert it to
         * @param targetDescriptor Its erasure
         * @param boxed            Whether the reference is a primitive just boxed
         * @return Whether a checkcast to the target is written
         */
        abstract boolean checks(TypeDef source, String sourceDescriptor, TypeDef target, String targetDescriptor, boolean boxed);
    }

    /**
     * A step of a conversion, which a writer emits as its instructions.
     *
     * @since 2.3
     */
    public sealed interface Step permits CheckCast, Unboxing, PrimitiveConversion, Box {
    }

    /**
     * A {@code checkcast} of a reference.
     *
     * @param descriptor The descriptor of the type the reference is cast to
     * @since 2.3
     */
    public record CheckCast(String descriptor) implements Step {
    }

    /**
     * A conversion of a primitive to another: {@code i2l}, {@code l2i} followed by {@code i2b}, and so on.
     *
     * @param from The primitive converted
     * @param to   The primitive it is converted to
     * @since 2.3
     */
    public record PrimitiveConversion(TypeDef.Primitive from, TypeDef.Primitive to) implements Step {
    }

    /**
     * A boxing of a primitive: the {@code valueOf} of its wrapper.
     *
     * @param primitive The primitive boxed
     * @since 2.3
     */
    public record Box(TypeDef.Primitive primitive) implements Step {

        /**
         * @return The internal name of the wrapper, which declares the {@code valueOf}
         */
        public String owner() {
            return TypeUtils.getInternalName(primitive.wrapperType().getName());
        }

        /**
         * @return The descriptor of the {@code valueOf}: {@code (I)Ljava/lang/Integer;} of an {@code int}
         */
        public String methodDescriptor() {
            return "(" + TypeUtils.getDescriptor(primitive, null, EnclosingScope.NONE) + ")" + TypeUtils.getDescriptor(primitive.wrapperType(), null, EnclosingScope.NONE);
        }
    }
}
