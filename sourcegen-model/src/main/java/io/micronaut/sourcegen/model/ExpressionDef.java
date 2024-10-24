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
package io.micronaut.sourcegen.model;

import com.github.javaparser.quality.NotNull;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.JavaModelUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The expression definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface ExpressionDef
    permits ExpressionDef.And, ExpressionDef.CallInstanceMethod, ExpressionDef.CallInstanceMethod2, ExpressionDef.CallStaticMethod, ExpressionDef.Cast, ExpressionDef.Condition, ExpressionDef.Constant, ExpressionDef.EqualsReferentially, ExpressionDef.EqualsStructurally, ExpressionDef.GetPropertyValue, ExpressionDef.IfElse, ExpressionDef.InvokeGetClassMethod, ExpressionDef.InvokeHashCodeMethod, ExpressionDef.IsNotNull, ExpressionDef.IsNull, ExpressionDef.NewArrayInitialized, ExpressionDef.NewArrayOfSize, ExpressionDef.NewInstance, ExpressionDef.Or, ExpressionDef.Switch, ExpressionDef.SwitchYieldCase, TypeDef.Primitive.PrimitiveInstance, VariableDef {

    /**
     * The condition of this variable.
     *
     * @param op         The operator
     * @param expression The expression of this variable
     * @return The condition expression
     * @since 1.2
     */
    default ExpressionDef asCondition(String op, ExpressionDef expression) {
        return new ExpressionDef.Condition(op, this, expression);
    }

    /**
     * The and condition of this variable.
     *
     * @param expression The expression of this variable
     * @return The "and" condition expression
     * @since 1.3
     */
    default ExpressionDef asConditionAnd(ExpressionDef expression) {
        return new ExpressionDef.And(this, expression);
    }

    /**
     * The or condition of this variable.
     *
     * @param expression The expression of this variable
     * @return The "or" condition expression
     * @since 1.3
     */
    default ExpressionDef asConditionOr(ExpressionDef expression) {
        return new ExpressionDef.Or(this, expression);
    }

    /**
     * @return Is non-null expression
     * @since 1.2
     */
    default ExpressionDef isNonNull() {
        return new ExpressionDef.IsNotNull(this);
    }

    /**
     * @return Is null expression
     * @since 1.2
     */
    default ExpressionDef isNull() {
        return new ExpressionDef.IsNull(this);
    }

    /**
     * @return The null value expression
     * @since 1.2
     */
    @NonNull
    static ExpressionDef.Constant nullValue() {
        return new Constant(TypeDef.OBJECT, null);
    }

    /**
     * @return The true value expression
     * @since 1.2
     */
    @NonNull
    static ExpressionDef.Constant trueValue() {
        return TypeDef.Primitive.TRUE;
    }

    /**
     * @return The true value expression
     * @since 1.2
     */
    @NonNull
    static ExpressionDef.Constant falseValue() {
        return TypeDef.Primitive.FALSE;
    }

    /**
     * The statement returning this expression.
     *
     * @return The statement returning this expression
     */
    default StatementDef returning() {
        return new StatementDef.Return(this);
    }

    /**
     * Cast expression to a different type.
     *
     * @param type The type to cast to
     * @return The cast expression
     */
    @NonNull
    default ExpressionDef.Cast cast(TypeDef type) {
        return new Cast(type, this);
    }

    /**
     * The conditional statement based on this expression.
     *
     * @param statement The statement
     * @return The statement returning this expression
     * @since 1.2
     */
    default StatementDef asConditionIf(StatementDef statement) {
        return new StatementDef.If(this, statement);
    }

    /**
     * The conditional statement based on this expression.
     *
     * @param statement     The statement
     * @param elseStatement The else statement
     * @return The statement returning this expression
     * @since 1.2
     */
    default StatementDef asConditionIfElse(StatementDef statement, StatementDef elseStatement) {
        return new StatementDef.IfElse(this, statement, elseStatement);
    }

    /**
     * The conditional if else expression.
     *
     * @param expression     The expression
     * @param elseExpression The else expression
     * @return The statement returning this expression
     * @since 1.2
     */
    default ExpressionDef asConditionIfElse(ExpressionDef expression, ExpressionDef elseExpression) {
        return new ExpressionDef.IfElse(this, expression, elseExpression);
    }

    /**
     * Turn this expression into a new local variable.
     *
     * @param name The local name
     * @return A new local
     * @since 1.2
     */
    default StatementDef.DefineAndAssign newLocal(String name) {
        return new VariableDef.Local(name, type()).defineAndAssign(this);
    }

    /**
     * Turn this expression into a new local variable.
     *
     * @param name The local name
     * @param fn   The contextual function
     * @return A new local
     * @since 1.2
     */
    default StatementDef newLocal(String name, Function<VariableDef, StatementDef> fn) {
        StatementDef.DefineAndAssign defineAndAssign = newLocal(name);
        return StatementDef.multi(
            defineAndAssign,
            fn.apply(defineAndAssign.variable())
        );
    }

    /**
     * Turn this expression into an expression switch.
     *
     * @param type  The expression type
     * @param cases The cases
     * @return A new switch expression
     * @since 1.2
     */
    default ExpressionDef.Switch asExpressionSwitch(TypeDef type, Map<Constant, ExpressionDef> cases) {
        return new Switch(this, type, cases);
    }

    /**
     * Turn this expression into a statement switch.
     *
     * @param type  The expression type
     * @param cases The cases
     * @return A new switch expression
     * @since 1.2
     */
    default StatementDef.Switch asStatementSwitch(TypeDef type, Map<Constant, StatementDef> cases) {
        return new StatementDef.Switch(this, type, cases);
    }

    /**
     * Turn this expression into a while statement.
     *
     * @param statement The statement
     * @return A new switch expression
     * @since 1.2
     */
    default StatementDef.While whileLoop(StatementDef statement) {
        return new StatementDef.While(this, statement);
    }

    /**
     * Reference the field of this variable.
     *
     * @param fieldName The field type
     * @param typeDef   Teh field type
     * @return The field variable
     * @since 1.2
     */
    default VariableDef.Field field(String fieldName, TypeDef typeDef) {
        return new VariableDef.Field(this, fieldName, typeDef);
    }

    /**
     * Reference the field of this variable.
     *
     * @param fieldDef The field definition
     * @return The field variable
     * @since 1.2
     */
    default VariableDef.Field field(FieldDef fieldDef) {
        return new VariableDef.Field(this, fieldDef.name, fieldDef.getType());
    }

    /**
     * The call the instance method expression.
     *
     * @param methodDef The method
     * @return The call to the instance method
     * @since 1.2
     */
    default CallInstanceMethod invoke(MethodDef methodDef, ExpressionDef... parameters) {
        return new CallInstanceMethod(this, methodDef, parameters);
    }

    /**
     * The invoke the method defined by the reflection.
     *
     * @param method     The method
     * @param parameters The parameters
     * @return The invoke method expression
     * @since 1.4
     */
    default CallInstanceMethod2 invoke(Method method, ExpressionDef... parameters) {
        return new CallInstanceMethod2(this, method, parameters);
    }

    /**
     * The call the instance method expression.
     *
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @return The call to the instance method
     * @since 1.2
     */
    default CallInstanceMethod invoke(String name, TypeDef returning, ExpressionDef... parameters) {
        return invoke(name, returning, List.of(parameters));
    }

    /**
     * The call the instance method expression.
     *
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @return The call to the instance method
     * @since 1.2
     */
    default CallInstanceMethod invoke(String name, TypeDef returning, List<ExpressionDef> parameters) {
        return new CallInstanceMethod(
            this,
            name,
            parameters,
            returning
        );
    }

    /**
     * The call the instance method expression.
     *
     * @param methodElement The method element
     * @param parameters    The parameters
     * @return The call to the instance method
     * @since 1.2
     */
    default CallInstanceMethod invoke(MethodElement methodElement, ExpressionDef... parameters) {
        return invoke(methodElement, List.of(parameters));
    }

    /**
     * The call the instance method expression.
     *
     * @param methodElement The method element
     * @param parameters    The parameters
     * @return The call to the instance method
     * @since 1.2
     */
    default CallInstanceMethod invoke(MethodElement methodElement, List<ExpressionDef> parameters) {
        return new CallInstanceMethod(
            this,
            methodElement.getName(),
            parameters,
            TypeDef.of(methodElement.getReturnType())
        );
    }

    /**
     * The invocation of the {@link Object#hashCode()} or equivalent method for the expression.
     *
     * @return The hash code invocation
     * @since 1.2
     */
    default InvokeHashCodeMethod invokeHashCode() {
        return new InvokeHashCodeMethod(this);
    }

    /**
     * The invocation of the {@link Object#getClass()}} or equivalent method for the expression.
     *
     * @return The get class invocation
     * @since 1.2
     */
    default InvokeGetClassMethod invokeGetClass() {
        return new InvokeGetClassMethod(this);
    }

    /**
     * The structurally equals {@link Object#equals(Object)} of this expression and the other expression.
     *
     * @param other The other expression to compare with
     * @return The equals expression
     * @since 1.3
     */
    default EqualsStructurally equalsStructurally(ExpressionDef other) {
        return new EqualsStructurally(this, other);
    }

    /**
     * The referentially equals (==) of this expression and the other expression.
     *
     * @param other The other expression to compare with
     * @return The equals expression
     * @since 1.3
     */
    default EqualsReferentially equalsReferentially(ExpressionDef other) {
        return new EqualsReferentially(this, other);
    }

    /**
     * The get property value expression.
     *
     * @param propertyElement The property element
     * @return The get property value expression
     * @since 1.3
     */
    default GetPropertyValue getPropertyValue(PropertyElement propertyElement) {
        return new GetPropertyValue(this, propertyElement);
    }

    /**
     * Resolve a constant for the given type from the string.
     *
     * @param type    The type
     * @param typeDef The type def
     * @param value   The string value
     * @return The constant
     * @throws IllegalArgumentException if the constant is not supported.
     */
    @Experimental
    @Nullable
    static ExpressionDef constant(ClassElement type, TypeDef typeDef, @Nullable Object value) {
        Objects.requireNonNull(type, "Type cannot be null");
        if (value == null) {
            return null;
        }
        if (type.isPrimitive()) {
            return ClassUtils.getPrimitiveType(type.getName()).flatMap(t ->
                ConversionService.SHARED.convert(value, t)
            ).map(o -> new Constant(typeDef, o)).orElse(null);
        } else if (ClassUtils.isJavaLangType(type.getName())) {
            return ClassUtils.forName(type.getName(), ExpressionDef.class.getClassLoader())
                .flatMap(t -> ConversionService.SHARED.convert(value, t))
                .map(o -> new Constant(typeDef, o)).orElse(null);
        } else if (type.isEnum()) {
            String name;
            if (value instanceof Enum<?> anEnum) {
                name = anEnum.name();
            } else {
                name = value.toString();
            }
            return ((ClassTypeDef) typeDef).getStaticField(name, typeDef);
        }
        return null;
    }

    /**
     * Resolve a constant for the given type from the string.
     *
     * @param value The string value
     * @return The constant
     * @throws IllegalArgumentException if the constant is not supported.
     * @since 1.2
     */
    @Experimental
    @Nullable
    static ExpressionDef.Constant constant(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        TypeDef type;
        if (value instanceof TypeDef) {
            type = TypeDef.CLASS;
        } else {
            type = TypeDef.of(value.getClass());
        }
        return new Constant(type, value);
    }

    /**
     * Resolve a constant for the given type from the string.
     *
     * @param value The string value
     * @return The constant
     * @throws IllegalArgumentException if the constant is not supported.
     * @since 1.2
     */
    @Experimental
    @Nullable
    static ExpressionDef.Constant primitiveConstant(@NotNull Object value) {
        Class<?> primitiveType = ReflectionUtils.getPrimitiveType(value.getClass());
        return new ExpressionDef.Constant(TypeDef.primitive(primitiveType), value);
    }

    /**
     * The type of the expression.
     *
     * @return The type
     */
    TypeDef type();

    /**
     * The new instance expression.
     *
     * @param type The type
     * @return The new instance
     */
    @Experimental
    static NewInstance instantiate(ClassTypeDef type) {
        return ExpressionDef.instantiate(type, List.of());
    }

    /**
     * The new instance expression.
     *
     * @param type   The type
     * @param values The constructor values
     * @return The new instance
     */
    @Experimental
    static NewInstance instantiate(ClassTypeDef type,
                                   List<ExpressionDef> values) {
        return new NewInstance(type, values);
    }

    /**
     * The call the instance method expression.
     *
     * @param instance   The instance
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @return The call to the instance method
     */
    @Experimental
    static CallInstanceMethod invoke(
        VariableDef instance,
        String name,
        List<ExpressionDef> parameters,
        TypeDef returning) {
        return new CallInstanceMethod(
            instance,
            name,
            parameters,
            returning
        );
    }

    /**
     * The call the instance method expression.
     *
     * @param typeDef    The class type def
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @return The call to the static method
     */
    @Experimental
    static CallStaticMethod invokeStatic(
        ClassTypeDef typeDef,
        String name,
        List<ExpressionDef> parameters,
        TypeDef returning) {
        return new CallStaticMethod(
            typeDef,
            name,
            parameters,
            returning
        );
    }

    /**
     * The new instance expression.
     *
     * @param type   The type
     * @param values The constructor values
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record NewInstance(ClassTypeDef type,
                       List<ExpressionDef> values) implements ExpressionDef {
    }

    /**
     * The cast expression. No checks are performed on the types and casting expression is
     * always generated.
     *
     * @param type          The type to cast to
     * @param expressionDef The expression to cast
     * @author Andriy Dmytruk
     * @since 1.3
     */
    @Experimental
    record Cast(TypeDef type, ExpressionDef expressionDef) implements ExpressionDef {
    }

    /**
     * The constant expression.
     *
     * @param type  The type
     * @param value The value
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Constant(TypeDef type,
                    @Nullable
                    Object value) implements ExpressionDef {
    }

    /**
     * The call an instance method expression.
     *
     * @param instance      The instance
     * @param name          The method name
     * @param parameters    The parameters
     * @param returningType The returning
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record CallInstanceMethod(ExpressionDef instance,
                              String name,
                              List<ExpressionDef> parameters,
                              TypeDef returningType) implements ExpressionDef, StatementDef {

        public CallInstanceMethod(ExpressionDef instance, MethodDef methodDef, ExpressionDef... parameters) {
            this(instance, methodDef.name, List.of(parameters), methodDef.getReturnType());
        }

        @Override
        public TypeDef type() {
            return returningType;
        }
    }

    /**
     * The invoke method expression.
     *
     * @param instance The instance
     * @param method   The method
     * @param type     The type
     * @author Denis Stepanov
     * @since 1.4
     */
    @Experimental
    record CallInstanceMethod2(ExpressionDef instance,
                               Method method,
                               TypeDef type,
                               ExpressionDef... parameters) implements ExpressionDef, StatementDef {

        public CallInstanceMethod2(ExpressionDef instance, Method method, ExpressionDef... parameters) {
            this(instance, method, TypeDef.of(method.getReturnType()), parameters);
        }

    }

    /**
     * The call a static method expression.
     *
     * @param classDef      The class
     * @param name          The method name
     * @param parameters    The parameters
     * @param returningType The returning
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record CallStaticMethod(ClassTypeDef classDef, String name, List<ExpressionDef> parameters,
                            TypeDef returningType) implements ExpressionDef, StatementDef {
        @Override
        public TypeDef type() {
            return returningType;
        }
    }

    /**
     * The condition operator.
     *
     * @param operator The operator
     * @param left     The left expression
     * @param right    The right expression
     * @author Denis Stepanov
     */
    @Experimental
    record Condition(String operator,
                     ExpressionDef left,
                     ExpressionDef right) implements ExpressionDef {
        @Override
        public TypeDef type() {
            return TypeDef.Primitive.BOOLEAN;
        }
    }

    /**
     * The IS NULL condition.
     *
     * @param expression The expression
     * @author Denis Stepanov
     */
    @Experimental
    record IsNull(ExpressionDef expression) implements ExpressionDef {
        @Override
        public TypeDef type() {
            return TypeDef.Primitive.BOOLEAN;
        }
    }

    /**
     * The IS NOT NULL condition.
     *
     * @param expression The expression
     * @author Denis Stepanov
     */
    @Experimental
    record IsNotNull(ExpressionDef expression) implements ExpressionDef {
        @Override
        public TypeDef type() {
            return TypeDef.Primitive.BOOLEAN;
        }
    }

    /**
     * The and condition. Puts parenthesis around itself when needed.
     *
     * @param left  The left expression
     * @param right The right expression
     * @author Elif Kurtay
     * @since 1.3
     */
    @Experimental
    record And(ExpressionDef left, ExpressionDef right) implements ExpressionDef {
        @Override
        public TypeDef type() {
            return TypeDef.Primitive.BOOLEAN;
        }
    }

    /**
     * The or condition. Puts parenthesis around itself when needed.
     *
     * @param left  The left expression
     * @param right The right expression
     * @author Elif Kurtay
     * @since 1.3
     */
    @Experimental
    record Or(ExpressionDef left, ExpressionDef right) implements ExpressionDef {
        @Override
        public TypeDef type() {
            return TypeDef.Primitive.BOOLEAN;
        }
    }

    /**
     * The if-else expression.
     *
     * @param condition      The condition
     * @param expression     The expression if the condition is true
     * @param elseExpression The expression if the condition is false
     */
    @Experimental
    record IfElse(ExpressionDef condition, ExpressionDef expression,
                  ExpressionDef elseExpression) implements ExpressionDef {
        @Override
        public TypeDef type() {
            return expression.type();
        }
    }

    /**
     * The switch expression.
     * Note: null constant or null value represents a default case.
     *
     * @param expression The switch expression
     * @param type       The switch type
     * @param cases      The cases
     * @since 1.2
     */
    @Experimental
    record Switch(ExpressionDef expression,
                  TypeDef type,
                  Map<Constant, ExpressionDef> cases) implements ExpressionDef {
    }

    /**
     * The switch yield case expression.
     *
     * @param type      The yield result
     * @param statement The statement that should yield the result
     * @since 1.2
     */
    @Experimental
    record SwitchYieldCase(TypeDef type, StatementDef statement) implements ExpressionDef {
    }

    /**
     * The new array expression.
     *
     * @param type The type
     * @param size The array size
     * @author Denis Stepanov
     * @since 1.2
     */
    @Experimental
    record NewArrayOfSize(TypeDef.Array type, int size) implements ExpressionDef {
    }

    /**
     * The new array expression.
     *
     * @param type        The type
     * @param expressions The items expression
     * @author Denis Stepanov
     * @since 1.2
     */
    @Experimental
    record NewArrayInitialized(TypeDef.Array type,
                               List<ExpressionDef> expressions) implements ExpressionDef {
    }

    /**
     * The get property value expression.
     *
     * @param instance        The instance
     * @param propertyElement The property element
     * @author Denis Stepanov
     * @since 1.3
     */
    @Experimental
    record GetPropertyValue(ExpressionDef instance,
                            PropertyElement propertyElement) implements ExpressionDef {

        @Override
        public TypeDef type() {
            return TypeDef.of(propertyElement.getType());
        }
    }

    /**
     * The get class expression.
     *
     * @param instance The instance
     * @author Denis Stepanov
     * @since 1.3
     */
    @Experimental
    record InvokeGetClassMethod(ExpressionDef instance) implements ExpressionDef {

        @Override
        public TypeDef type() {
            return TypeDef.of(Class.class);
        }
    }

    /**
     * The get hashCode expression.
     *
     * @param instance The instance
     * @author Denis Stepanov
     * @since 1.3
     */
    @Experimental
    record InvokeHashCodeMethod(ExpressionDef instance) implements ExpressionDef {

        @Override
        public TypeDef type() {
            return TypeDef.of(Class.class);
        }
    }

    /**
     * The structurally equals expression.
     *
     * @param instance The instance
     * @param other The other
     * @author Denis Stepanov
     * @since 1.3
     */
    @Experimental
    record EqualsStructurally(ExpressionDef instance, ExpressionDef other) implements ExpressionDef {

        @Override
        public TypeDef type() {
            return TypeDef.Primitive.BOOLEAN;
        }
    }

    /**
     * The referential equals expression.
     *
     * @param instance The instance
     * @param other The other
     * @author Denis Stepanov
     * @since 1.3
     */
    @Experimental
    record EqualsReferentially(ExpressionDef instance, ExpressionDef other) implements ExpressionDef {

        @Override
        public TypeDef type() {
            return TypeDef.Primitive.BOOLEAN;
        }
    }

}
