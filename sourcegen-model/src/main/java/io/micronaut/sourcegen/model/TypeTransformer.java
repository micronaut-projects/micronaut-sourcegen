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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Internal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Rewrites the types a body of statements names: each statement and expression is rebuilt with every type it
 * names mapped, and its nested statements and expressions rewritten in turn.
 *
 * <p>The types of the scope the body is written in are mapped: the type of a variable, a cast, a new instance or
 * array, a constant, a field access, a caught exception, and the types a lambda implementation declares, which is
 * written in the body. A method a statement invokes or a method reference names is a declaration of its own scope,
 * whose types are not those of the body: it is left as it is, as are the types of a method reference, which are
 * checked against the declarations it names; the receiver of a bound reference is rewritten.</p>
 *
 * <p>Every kind of statement and expression is rebuilt through its canonical constructor, so that the invariants it
 * establishes hold for the rewritten one too; an operand the constructor casts is given to it without the cast it
 * added before, so that the rebuilt operation casts it once. A statement or expression naming no mapped type is kept as the same
 * instance. A type a statement requires to be a class type or an array type has to be mapped to one.</p>
 *
 * @since 2.3
 */
@Internal
public final class TypeTransformer {

    private final UnaryOperator<TypeDef> types;

    private TypeTransformer(UnaryOperator<TypeDef> types) {
        this.types = types;
    }

    /**
     * @param types Maps each type named, and returns the same instance for a type it does not change
     * @return The transformer
     */
    public static TypeTransformer of(UnaryOperator<TypeDef> types) {
        return new TypeTransformer(Objects.requireNonNull(types, "types"));
    }

    /**
     * @param type A type the body names
     * @return The mapped type
     */
    public TypeDef type(TypeDef type) {
        return Objects.requireNonNull(types.apply(type), "A type is mapped to null");
    }

    /**
     * @param statements Statements
     * @return The statements with their types mapped, the same list where none changes
     */
    public List<StatementDef> statements(List<StatementDef> statements) {
        return list(statements, this::statement);
    }

    /**
     * @param statement A statement
     * @return The statement with its types mapped, the same instance where none changes
     */
    public StatementDef statement(StatementDef statement) {
        return switch (statement) {
            case ExpressionDef.InvokeInstanceMethod invoke -> invokeInstance(invoke);
            case ExpressionDef.InvokeStaticMethod invoke -> invokeStatic(invoke);
            case StatementDef.Multi multi -> {
                // The statements of a block are read flattened, into a list of their own
                List<StatementDef> flattened = multi.statements();
                List<StatementDef> statements = statements(flattened);
                yield statements == flattened ? multi : new StatementDef.Multi(statements);
            }
            case StatementDef.Throw aThrow -> {
                ExpressionDef expression = expression(aThrow.expression());
                yield expression == aThrow.expression() ? aThrow : new StatementDef.Throw(expression);
            }
            case StatementDef.Return aReturn -> {
                ExpressionDef returned = aReturn.expression();
                ExpressionDef expression = returned == null ? null : expression(returned);
                yield expression == aReturn.expression() ? aReturn : new StatementDef.Return(expression);
            }
            case StatementDef.Assign assign -> {
                VariableDef.Local variable = local(assign.variable());
                ExpressionDef expression = expression(assign.expression());
                yield variable == assign.variable() && expression == assign.expression() ? assign
                    : new StatementDef.Assign(variable, expression);
            }
            case StatementDef.DefineAndAssign define -> {
                VariableDef.Local variable = local(define.variable());
                ExpressionDef expression = expression(define.expression());
                yield variable == define.variable() && expression == define.expression() ? define
                    : new StatementDef.DefineAndAssign(variable, expression);
            }
            case StatementDef.PutField put -> {
                VariableDef.Field field = field(put.field());
                ExpressionDef expression = expression(put.expression());
                yield field == put.field() && expression == put.expression() ? put : new StatementDef.PutField(field, expression);
            }
            case StatementDef.PutStaticField put -> {
                VariableDef.StaticField field = staticField(put.field());
                ExpressionDef expression = expression(put.expression());
                yield field == put.field() && expression == put.expression() ? put : new StatementDef.PutStaticField(field, expression);
            }
            case StatementDef.If anIf -> {
                ExpressionDef condition = expression(anIf.condition());
                StatementDef body = statement(anIf.statement());
                yield condition == anIf.condition() && body == anIf.statement() ? anIf : new StatementDef.If(condition, body);
            }
            case StatementDef.IfElse ifElse -> {
                ExpressionDef condition = expression(ifElse.condition());
                StatementDef body = statement(ifElse.statement());
                StatementDef elseBody = statement(ifElse.elseStatement());
                yield condition == ifElse.condition() && body == ifElse.statement() && elseBody == ifElse.elseStatement()
                    ? ifElse : new StatementDef.IfElse(condition, body, elseBody);
            }
            case StatementDef.Switch aSwitch -> {
                ExpressionDef expression = expression(aSwitch.expression());
                TypeDef type = type(aSwitch.type());
                Map<ExpressionDef.Constant, StatementDef> cases = cases(aSwitch.cases(), this::statement);
                StatementDef declaredDefault = aSwitch.defaultCase();
                StatementDef defaultCase = declaredDefault == null ? null : statement(declaredDefault);
                yield expression == aSwitch.expression() && type == aSwitch.type() && cases == aSwitch.cases()
                    && defaultCase == aSwitch.defaultCase() ? aSwitch : new StatementDef.Switch(expression, type, cases, defaultCase);
            }
            case StatementDef.While aWhile -> {
                ExpressionDef expression = expression(aWhile.expression());
                StatementDef body = statement(aWhile.statement());
                yield expression == aWhile.expression() && body == aWhile.statement() ? aWhile : new StatementDef.While(expression, body);
            }
            case StatementDef.Try aTry -> {
                StatementDef body = statement(aTry.statement());
                List<StatementDef.Try.Catch> catches = list(aTry.catches(), this::aCatch);
                StatementDef declaredFinally = aTry.finallyStatement();
                StatementDef finallyStatement = declaredFinally == null ? null : statement(declaredFinally);
                yield body == aTry.statement() && catches == aTry.catches() && finallyStatement == aTry.finallyStatement()
                    ? aTry : new StatementDef.Try(body, catches, finallyStatement);
            }
            case StatementDef.Synchronized aSynchronized -> {
                ExpressionDef monitor = expression(aSynchronized.monitor());
                StatementDef body = statement(aSynchronized.statement());
                yield monitor == aSynchronized.monitor() && body == aSynchronized.statement() ? aSynchronized
                    : new StatementDef.Synchronized(monitor, body);
            }
            case StatementDef.InvokeSuperConstructor invoke -> {
                VariableDef.Super superInstance = superVariable(invoke.superInstance());
                List<ExpressionDef> values = expressions(invoke.values());
                yield superInstance == invoke.superInstance() && values == invoke.values() ? invoke
                    : new StatementDef.InvokeSuperConstructor(superInstance, invoke.method(), values);
            }
        };
    }

    /**
     * @param expression An expression
     * @return The expression with its types mapped, the same instance where none changes
     */
    public ExpressionDef expression(ExpressionDef expression) {
        return switch (expression) {
            case VariableDef variable -> variable(variable);
            case ExpressionDef.ConditionExpressionDef condition -> condition(condition);
            case MethodReferenceExpression reference -> methodReference(reference);
            case ExpressionDef.InvokeInstanceMethod invoke -> invokeInstance(invoke);
            case ExpressionDef.InvokeStaticMethod invoke -> invokeStatic(invoke);
            case ExpressionDef.NewInstance newInstance -> {
                ClassTypeDef type = classType(newInstance.type());
                List<TypeDef> parameterTypes = list(newInstance.parameterTypes(), this::type);
                List<ExpressionDef> values = expressions(newInstance.values());
                yield type == newInstance.type() && parameterTypes == newInstance.parameterTypes() && values == newInstance.values()
                    ? newInstance : new ExpressionDef.NewInstance(type, parameterTypes, values);
            }
            case ExpressionDef.Cast cast -> {
                TypeDef type = type(cast.type());
                ExpressionDef value = expression(cast.expressionDef());
                yield type == cast.type() && value == cast.expressionDef() ? cast : new ExpressionDef.Cast(type, value);
            }
            case ExpressionDef.Constant constant -> constant(constant);
            case ExpressionDef.MathBinaryOperation operation -> {
                ExpressionDef left = expression(operation.left());
                ExpressionDef right = expression(operation.right());
                yield left == operation.left() && right == operation.right() ? operation
                    : new ExpressionDef.MathBinaryOperation(operation.opType(), left, uncast(right));
            }
            case ExpressionDef.MathUnaryOperation operation -> {
                ExpressionDef value = expression(operation.expression());
                yield value == operation.expression() ? operation : new ExpressionDef.MathUnaryOperation(operation.opType(), value);
            }
            case ExpressionDef.StringConcatenation concatenation -> {
                ExpressionDef left = expression(concatenation.left());
                ExpressionDef right = expression(concatenation.right());
                yield left == concatenation.left() && right == concatenation.right() ? concatenation
                    : new ExpressionDef.StringConcatenation(left, right);
            }
            case ExpressionDef.IfElse ifElse -> {
                ExpressionDef condition = expression(ifElse.condition());
                ExpressionDef value = expression(ifElse.ifExpression());
                ExpressionDef elseValue = expression(ifElse.elseExpression());
                TypeDef type = type(ifElse.type());
                yield condition == ifElse.condition() && value == ifElse.ifExpression() && elseValue == ifElse.elseExpression()
                    && type == ifElse.type() ? ifElse : new ExpressionDef.IfElse(condition, value, elseValue, type);
            }
            case ExpressionDef.Switch aSwitch -> {
                ExpressionDef value = expression(aSwitch.expression());
                TypeDef type = type(aSwitch.type());
                Map<ExpressionDef.Constant, ExpressionDef> cases = cases(aSwitch.cases(), this::expression);
                ExpressionDef declaredDefault = aSwitch.defaultCase();
                ExpressionDef defaultCase = declaredDefault == null ? null : expression(declaredDefault);
                yield value == aSwitch.expression() && type == aSwitch.type() && cases == aSwitch.cases()
                    && defaultCase == aSwitch.defaultCase() ? aSwitch : new ExpressionDef.Switch(value, type, cases, defaultCase);
            }
            case ExpressionDef.SwitchYieldCase yieldCase -> {
                TypeDef type = type(yieldCase.type());
                StatementDef statement = statement(yieldCase.statement());
                yield type == yieldCase.type() && statement == yieldCase.statement() ? yieldCase
                    : new ExpressionDef.SwitchYieldCase(type, statement);
            }
            case ExpressionDef.NewArrayOfSize newArray -> {
                TypeDef.Array type = arrayType(newArray.type());
                yield type == newArray.type() ? newArray : new ExpressionDef.NewArrayOfSize(type, newArray.size());
            }
            case ExpressionDef.NewArrayInitialized newArray -> {
                TypeDef.Array type = arrayType(newArray.type());
                List<ExpressionDef> values = expressions(newArray.expressions());
                yield type == newArray.type() && values == newArray.expressions() ? newArray
                    : new ExpressionDef.NewArrayInitialized(type, values);
            }
            case ExpressionDef.GetPropertyValue property -> {
                ExpressionDef instance = expression(property.instance());
                yield instance == property.instance() ? property : new ExpressionDef.GetPropertyValue(instance, property.propertyElement());
            }
            case ExpressionDef.InvokeGetClassMethod invoke -> {
                ExpressionDef instance = expression(invoke.instance());
                yield instance == invoke.instance() ? invoke : new ExpressionDef.InvokeGetClassMethod(instance);
            }
            case ExpressionDef.InvokeHashCodeMethod invoke -> {
                ExpressionDef instance = expression(invoke.instance());
                yield instance == invoke.instance() ? invoke : new ExpressionDef.InvokeHashCodeMethod(instance);
            }
            case ExpressionDef.ArrayElement element -> {
                ExpressionDef array = expression(element.expression());
                TypeDef type = type(element.type());
                ExpressionDef index = expression(element.indexExpression());
                yield array == element.expression() && type == element.type() && index == element.indexExpression() ? element
                    : new ExpressionDef.ArrayElement(array, type, index);
            }
            case ExpressionDef.Lambda lambda -> {
                ClassTypeDef type = classType(lambda.type());
                MethodDef implementation = method(lambda.implementation());
                yield type == lambda.type() && implementation == lambda.implementation() ? lambda
                    : new ExpressionDef.Lambda(type, lambda.target(), implementation);
            }
        };
    }

    /**
     * A method declared in the scope of the body - the implementation of a lambda - with the types of its signature
     * and its body mapped. The variables it declares are its own, and kept.
     *
     * @param method The method
     * @return The method with its types mapped, the same instance where none changes
     */
    public MethodDef method(MethodDef method) {
        TypeDef returnType = type(method.getReturnType());
        List<ParameterDef> parameters = list(method.getParameters(), this::parameter);
        List<StatementDef> statements = statements(method.getStatements());
        List<TypeDef> throwTypes = list(method.getThrowTypes(), this::type);
        if (returnType == method.getReturnType() && parameters == method.getParameters() && statements == method.getStatements()
            && throwTypes == method.getThrowTypes()) {
            return method;
        }
        return new MethodDef(method.getName(), EnumSet.copyOf(method.modifiers), returnType, parameters, statements,
            method.getAnnotations(), method.getJavadoc(), method.getTypeVariables(), method.isOverride(), method.isSynthetic(),
            throwTypes);
    }

    private ParameterDef parameter(ParameterDef parameter) {
        TypeDef type = type(parameter.getType());
        if (type == parameter.getType()) {
            return parameter;
        }
        return ParameterDef.builder(parameter.getName(), type)
            .addModifiers(parameter.getModifiers())
            .addAnnotations(parameter.getAnnotations())
            .addJavadoc(parameter.getJavadoc())
            .synthetic(parameter.isSynthetic())
            .build();
    }

    private ExpressionDef.InvokeInstanceMethod invokeInstance(ExpressionDef.InvokeInstanceMethod invoke) {
        ExpressionDef instance = expression(invoke.instance());
        List<ExpressionDef> values = expressions(invoke.values());
        return instance == invoke.instance() && values == invoke.values() ? invoke
            : new ExpressionDef.InvokeInstanceMethod(instance, invoke.method(), invoke.isDefault(), values);
    }

    private ExpressionDef.InvokeStaticMethod invokeStatic(ExpressionDef.InvokeStaticMethod invoke) {
        ClassTypeDef owner = classType(invoke.classDef());
        List<ExpressionDef> values = expressions(invoke.values());
        return owner == invoke.classDef() && values == invoke.values() ? invoke
            : new ExpressionDef.InvokeStaticMethod(owner, invoke.method(), values);
    }

    private ExpressionDef variable(VariableDef variable) {
        return switch (variable) {
            case VariableDef.Local local -> local(local);
            case VariableDef.MethodParameter parameter -> {
                TypeDef type = type(parameter.type());
                yield type == parameter.type() ? parameter : new VariableDef.MethodParameter(parameter.name(), type);
            }
            case VariableDef.Field field -> field(field);
            case VariableDef.StaticField field -> staticField(field);
            case VariableDef.This aThis -> aThis;
            case VariableDef.Super aSuper -> superVariable(aSuper);
            case VariableDef.ExceptionVar exception -> {
                ClassTypeDef type = classType(exception.type());
                yield type == exception.type() ? exception : new VariableDef.ExceptionVar(type);
            }
        };
    }

    private VariableDef.Local local(VariableDef.Local local) {
        TypeDef type = type(local.type());
        return type == local.type() ? local : new VariableDef.Local(local.name(), type);
    }

    private VariableDef.Field field(VariableDef.Field field) {
        ExpressionDef instance = expression(field.instance());
        TypeDef declaringType = type(field.declaringType());
        TypeDef type = type(field.type());
        return instance == field.instance() && declaringType == field.declaringType() && type == field.type() ? field
            : new VariableDef.Field(instance, declaringType, field.name(), type);
    }

    private VariableDef.StaticField staticField(VariableDef.StaticField field) {
        ClassTypeDef owner = classType(field.ownerType());
        TypeDef type = type(field.type());
        return owner == field.ownerType() && type == field.type() ? field : new VariableDef.StaticField(owner, field.name(), type);
    }

    private VariableDef.Super superVariable(VariableDef.Super aSuper) {
        ClassTypeDef type = classType(aSuper.type());
        return type == aSuper.type() ? aSuper : new VariableDef.Super(type);
    }

    private ExpressionDef condition(ExpressionDef.ConditionExpressionDef condition) {
        return switch (condition) {
            case ExpressionDef.ComparisonOperation comparison -> {
                ExpressionDef left = expression(comparison.left());
                ExpressionDef right = expression(comparison.right());
                yield left == comparison.left() && right == comparison.right() ? comparison
                    : new ExpressionDef.ComparisonOperation(comparison.opType(), left,
                    comparison.right() instanceof ExpressionDef.Cast ? uncast(right) : right);
            }
            case ExpressionDef.IsNull isNull -> {
                ExpressionDef value = expression(isNull.expression());
                yield value == isNull.expression() ? isNull : new ExpressionDef.IsNull(uncast(value));
            }
            case ExpressionDef.IsNotNull isNotNull -> {
                ExpressionDef value = expression(isNotNull.expression());
                yield value == isNotNull.expression() ? isNotNull : new ExpressionDef.IsNotNull(uncast(value));
            }
            case ExpressionDef.IsTrue isTrue -> {
                ExpressionDef value = expression(isTrue.expression());
                yield value == isTrue.expression() ? isTrue : new ExpressionDef.IsTrue(value);
            }
            case ExpressionDef.IsFalse isFalse -> {
                ExpressionDef value = expression(isFalse.expression());
                yield value == isFalse.expression() ? isFalse : new ExpressionDef.IsFalse(value);
            }
            case ExpressionDef.And and -> {
                ExpressionDef.ConditionExpressionDef left = conditionOf(expression(and.left()));
                ExpressionDef.ConditionExpressionDef right = conditionOf(expression(and.right()));
                yield left == and.left() && right == and.right() ? and : new ExpressionDef.And(left, right);
            }
            case ExpressionDef.Or or -> {
                ExpressionDef.ConditionExpressionDef left = conditionOf(expression(or.left()));
                ExpressionDef.ConditionExpressionDef right = conditionOf(expression(or.right()));
                yield left == or.left() && right == or.right() ? or : new ExpressionDef.Or(left, right);
            }
            case ExpressionDef.EqualsStructurally equals -> {
                ExpressionDef instance = expression(equals.instance());
                ExpressionDef other = expression(equals.other());
                yield instance == equals.instance() && other == equals.other() ? equals : new ExpressionDef.EqualsStructurally(instance, other);
            }
            case ExpressionDef.NotEqualsStructurally notEquals -> {
                ExpressionDef instance = expression(notEquals.instance());
                ExpressionDef other = expression(notEquals.other());
                yield instance == notEquals.instance() && other == notEquals.other() ? notEquals
                    : new ExpressionDef.NotEqualsStructurally(instance, other);
            }
            case ExpressionDef.EqualsReferentially equals -> {
                ExpressionDef instance = expression(equals.instance());
                ExpressionDef other = expression(equals.other());
                yield instance == equals.instance() && other == equals.other() ? equals : new ExpressionDef.EqualsReferentially(uncast(instance), uncast(other));
            }
            case ExpressionDef.NotEqualsReferentially notEquals -> {
                ExpressionDef instance = expression(notEquals.instance());
                ExpressionDef other = expression(notEquals.other());
                yield instance == notEquals.instance() && other == notEquals.other() ? notEquals
                    : new ExpressionDef.NotEqualsReferentially(instance, other);
            }
            case ExpressionDef.InstanceOf instanceOf -> {
                ExpressionDef value = expression(instanceOf.expression());
                ClassTypeDef type = classType(instanceOf.instanceType());
                yield value == instanceOf.expression() && type == instanceOf.instanceType() ? instanceOf
                    : new ExpressionDef.InstanceOf(value, type);
            }
        };
    }

    private ExpressionDef methodReference(MethodReferenceExpression reference) {
        // The types of a reference are checked against the methods it names, which are declared in their own scope
        if (reference instanceof InstanceMethodReferenceExpression bound) {
            ExpressionDef instance = expression(bound.instance());
            return instance == bound.instance() ? bound : new InstanceMethodReferenceExpression(bound.type(), bound.target(),
                bound.instantiated(), bound.owner(), instance, bound.method());
        }
        return reference;
    }

    private ExpressionDef.Constant constant(ExpressionDef.Constant constant) {
        TypeDef type = type(constant.type());
        // A class literal holds the type it names
        Object value = constant.value() instanceof TypeDef named ? type(named) : constant.value();
        return type == constant.type() && value == constant.value() ? constant : new ExpressionDef.Constant(type, value);
    }

    private StatementDef.Try.Catch aCatch(StatementDef.Try.Catch aCatch) {
        ClassTypeDef exception = classType(aCatch.exception());
        StatementDef statement = statement(aCatch.statement());
        return exception == aCatch.exception() && statement == aCatch.statement() ? aCatch
            : new StatementDef.Try.Catch(exception, statement);
    }

    private List<ExpressionDef> expressions(List<? extends ExpressionDef> expressions) {
        return list(expressions, this::expression);
    }

    private <V> Map<ExpressionDef.Constant, V> cases(Map<ExpressionDef.Constant, ? extends V> cases,
                                                      UnaryOperator<V> values) {
        Map<ExpressionDef.Constant, V> result = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<ExpressionDef.Constant, ? extends V> entry : cases.entrySet()) {
            ExpressionDef.Constant key = constant(entry.getKey());
            V value = values.apply(entry.getValue());
            changed |= key != entry.getKey() || value != entry.getValue();
            result.put(key, value);
        }
        @SuppressWarnings("unchecked")
        Map<ExpressionDef.Constant, V> unchanged = (Map<ExpressionDef.Constant, V>) cases;
        return changed ? result : unchanged;
    }

    /**
     * Maps the elements of a list, keeping the list where no element changes.
     */
    private static <T, R extends T> List<R> list(List<? extends T> elements, Function<? super T, ? extends R> mapping) {
        List<R> result = new ArrayList<>(elements.size());
        boolean changed = false;
        for (T element : elements) {
            R mapped = mapping.apply(element);
            changed |= mapped != element;
            result.add(mapped);
        }
        if (changed) {
            return result;
        }
        @SuppressWarnings("unchecked")
        List<R> unchanged = (List<R>) elements;
        return unchanged;
    }

    private ClassTypeDef classType(ClassTypeDef type) {
        TypeDef mapped = type(type);
        if (mapped instanceof ClassTypeDef classType) {
            return classType;
        }
        throw new IllegalStateException("The class type " + type + " is mapped to " + mapped + ", which is no class type");
    }

    private TypeDef.Array arrayType(TypeDef.Array type) {
        TypeDef mapped = type(type);
        if (mapped instanceof TypeDef.Array array) {
            return array;
        }
        throw new IllegalStateException("The array type " + type + " is mapped to " + mapped + ", which is no array type");
    }

    /**
     * An operand as it was given to an operation that casts it on construction, which a rebuilt operation casts
     * again: the cast of the rewritten operand is its own, not one more.
     */
    private static ExpressionDef uncast(ExpressionDef operand) {
        return operand instanceof ExpressionDef.Cast cast ? cast.expressionDef() : operand;
    }

    private static ExpressionDef.ConditionExpressionDef conditionOf(ExpressionDef expression) {
        return (ExpressionDef.ConditionExpressionDef) expression;
    }
}
