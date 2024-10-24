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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TableSwitchGenerator;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.lang.model.element.Modifier;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_ENUM;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_RECORD;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.V17;

public class ByteCodeSourceGenerator implements SourceGenerator {

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    public void write(ObjectDef objectDef, Writer writer) {
        throw new UnsupportedOperationException("Not supported.");
    }

    private ClassWriter generateClassBytes(ObjectDef objectDef) {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        CheckClassAdapter cv = new CheckClassAdapter(classWriter);
//        TraceClassVisitor traceClassVisitor = new TraceClassVisitor(classWriter, new PrintWriter(System.out));
        writeObject(cv, objectDef);
        classWriter.visitEnd();
        return classWriter;
    }

    public void writeObject(ClassVisitor classWriter, ObjectDef objectDef) {
        if (objectDef instanceof ClassDef classDef) {
            writeClass(classWriter, classDef);
        } else if (objectDef instanceof RecordDef recordDef) {
            writeRecord(classWriter, recordDef);
        } else if (objectDef instanceof InterfaceDef interfaceDef) {
            writeInterface(classWriter, interfaceDef);
        } else if (objectDef instanceof EnumDef enumDef) {
            writeEnum(classWriter, enumDef);
        } else {
            throw new IllegalStateException("Unknown object definition: " + objectDef);
        }
    }

    private void writeEnum(ClassVisitor classWriter, EnumDef enumDef) {
        ClassTypeDef enumTypeDef = enumDef.asTypeDef();
        Type enumType = TypeUtils.getType(enumTypeDef, null);

        ClassTypeDef baseEnumTypeDef = ClassTypeDef.of(Enum.class);
        ClassDef enumClassDef = ClassDef.builder(enumDef.getName())
            .superclass(
                TypeDef.parameterized(baseEnumTypeDef, ClassTypeDef.of(enumDef.getName()))
            )
            .build();
        classWriter.visit(
            V17,
            ACC_ENUM | ACC_FINAL | getModifiersFlag(enumDef.getModifiers()),
            enumType.getInternalName(),
            SignatureWriterUtils.getClassSignature(enumClassDef),
            Type.getType(Enum.class).getInternalName(),
            enumDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, enumDef)).map(Type::getInternalName).toArray(String[]::new)
        );
        for (String enumConstant : enumDef.getEnumConstants()) {
            FieldVisitor fieldVisitor = classWriter.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC + ACC_ENUM, enumConstant, enumType.getDescriptor(), null, null);
            fieldVisitor.visitEnd();
        }

        writeMethod(classWriter, enumDef, MethodDef.constructor()
            .addParameter(ParameterDef.of("name", TypeDef.STRING))
            .addParameter(ParameterDef.of("ordinal", TypeDef.Primitive.INT))
            .build((aThis, methodParameters) -> aThis.superRef().invoke(MethodDef.constructor().build(), methodParameters.get(0), methodParameters.get(1))));

        FieldDef $valuesField = FieldDef.builder("$VALUES").ofType(enumTypeDef.array()).addModifiers(Modifier.STATIC, Modifier.PRIVATE).build();

        writeField(classWriter, enumDef, $valuesField);

        MethodDef valuesMethod = MethodDef.builder("values")
            .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
            .returns(enumTypeDef.array())
            .build((aThis, methodParameters) ->
                enumTypeDef.getStaticField($valuesField)
                    .invoke(ReflectionUtils.getRequiredMethod(Object.class, "clone"))
                    .cast(enumTypeDef.array())
                    .returning());

        writeMethod(classWriter, enumDef, valuesMethod);

        MethodDef valueOfMethod = MethodDef.builder("valueOf")
            .addParameter(ParameterDef.of("value", TypeDef.STRING))
            .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
            .build((aThis, methodParameters) ->
                baseEnumTypeDef
                    .invokeStatic("valueOf", baseEnumTypeDef, ExpressionDef.constant(enumTypeDef), methodParameters.get(0))
                    .cast(enumTypeDef)
                    .returning());

        writeMethod(classWriter, enumDef, valueOfMethod);

        MethodDef $valuesMethod = MethodDef.builder("$values")
            .addModifiers(Modifier.STATIC, Modifier.PRIVATE)
            .build((aThis, methodParameters) ->
                enumTypeDef.array()
                    .instantiate(
                        enumDef.getEnumConstants()
                            .stream()
                            .<ExpressionDef>map(name -> enumTypeDef.getStaticField(name, enumTypeDef))
                            .toList()
                    )
                    .returning());

        writeMethod(classWriter, enumDef, $valuesMethod);

        for (MethodDef method : enumDef.getMethods()) {
            writeMethod(classWriter, enumDef, method);
        }

        MethodDef.MethodDefBuilder staticMethodBuilder = MethodDef.builder("<clinit>")
            .returns(TypeDef.VOID)
            .addModifiers(Modifier.STATIC);

        int i = 0;
        for (String enumConstant : enumDef.getEnumConstants()) {
            staticMethodBuilder.addStatement(
                enumTypeDef.getStaticField(enumConstant, enumTypeDef).put(
                    enumTypeDef.instantiate(
                        ExpressionDef.constant(enumConstant),
                        TypeDef.Primitive.INT.constant(i++)
                    )
                )
            );
        }

        staticMethodBuilder.addStatement(enumTypeDef.getStaticField($valuesField).put(enumTypeDef.invokeStatic($valuesMethod)));
        writeMethod(classWriter, null, staticMethodBuilder.build());
    }

    private void writeField(ClassVisitor classWriter, ObjectDef objectDef, FieldDef fieldDef) {
        FieldVisitor fieldVisitor = classWriter.visitField(
            getModifiersFlag(fieldDef.getModifiers()),
            fieldDef.getName(),
            TypeUtils.getType(fieldDef.getType(), objectDef).getDescriptor(),
            SignatureWriterUtils.getFieldSignature(objectDef, fieldDef),
            null
        );
        for (AnnotationDef annotation : fieldDef.getAnnotations()) {
            AnnotationVisitor annotationVisitor = fieldVisitor.visitAnnotation(TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
            annotation.getValues().forEach(annotationVisitor::visit);
        }
        fieldVisitor.visitEnd();
    }

    private void writeInterface(ClassVisitor classWriter, InterfaceDef interfaceDef) {
        classWriter.visit(V17,
            ACC_INTERFACE | ACC_ABSTRACT | getModifiersFlag(interfaceDef.getModifiers()),
            TypeUtils.getType(interfaceDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getInterfaceSignature(interfaceDef),
            Type.getType(Object.class).getInternalName(),
            interfaceDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, interfaceDef)).map(Type::getInternalName).toArray(String[]::new)
        );
        for (AnnotationDef annotation : interfaceDef.getAnnotations()) {
            AnnotationVisitor annotationVisitor = classWriter.visitAnnotation(TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
            annotation.getValues().forEach(annotationVisitor::visit);
        }
        for (MethodDef method : interfaceDef.getMethods()) {
            writeMethod(classWriter, interfaceDef, method);
        }
        for (PropertyDef property : interfaceDef.getProperties()) {
            writeProperty(classWriter, interfaceDef, property);
        }
        classWriter.visitEnd();
    }

    private void writeRecord(ClassVisitor classWriter, RecordDef recordDef) {
        classWriter.visit(
            V17,
            ACC_RECORD | getModifiersFlag(recordDef.getModifiers()),
            TypeUtils.getType(recordDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getRecordSignature(recordDef),
            Type.getType(Record.class).getInternalName(),
            recordDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, recordDef)).map(Type::getInternalName).toArray(String[]::new)
        );
    }

    private void writeClass(ClassVisitor classWriter, ClassDef classDef) {
        String CONSTRUCTOR_NAME = "<init>";
        String DESCRIPTOR_DEFAULT_CONSTRUCTOR = "()V";

        TypeDef superclass = Objects.requireNonNullElse(classDef.getSuperclass(), TypeDef.OBJECT);

        classWriter.visit(
            V17,
            getModifiersFlag(classDef.getModifiers()),
            TypeUtils.getType(classDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getClassSignature(classDef),
            TypeUtils.getType(superclass, null).getInternalName(),
            classDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, classDef)).map(Type::getInternalName).toArray(String[]::new)
        );

        for (AnnotationDef annotation : classDef.getAnnotations()) {
            AnnotationVisitor annotationVisitor = classWriter.visitAnnotation(TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
            annotation.getValues().forEach(annotationVisitor::visit);
        }

        for (FieldDef field : classDef.getFields()) {
            writeField(classWriter, classDef, field);
        }

        if (classDef.getMethods().stream().noneMatch(MethodDef::isConstructor)) {
            MethodVisitor defaultConstructor = classWriter.visitMethod(ACC_PUBLIC, CONSTRUCTOR_NAME, DESCRIPTOR_DEFAULT_CONSTRUCTOR, null, null);
            GeneratorAdapter generatorAdapter = new GeneratorAdapter(defaultConstructor, ACC_PUBLIC, CONSTRUCTOR_NAME, DESCRIPTOR_DEFAULT_CONSTRUCTOR);

            generatorAdapter.visitCode();
            generatorAdapter.loadThis();
            generatorAdapter.invokeConstructor(TypeUtils.getType(superclass, null), Method.getMethod("void <init> ()"));
            generatorAdapter.returnValue();
            generatorAdapter.visitMaxs(2, 1);
            generatorAdapter.visitEnd();
        }

        for (PropertyDef property : classDef.getProperties()) {
            writeProperty(classWriter, classDef, property);
        }
        for (MethodDef method : classDef.getMethods()) {
            writeMethod(classWriter, classDef, method);
        }
    }

    private void writeProperty(ClassVisitor classWriter, ObjectDef objectDef, PropertyDef property) {
        FieldDef propertyField = FieldDef.builder(property.getName(), property.getType())
            .addModifiers(Modifier.PRIVATE)
            .addAnnotations(property.getAnnotations())
            .build();

        writeField(classWriter, objectDef, propertyField);

        String capitalizedPropertyName = NameUtils.capitalize(property.getName());

        boolean isAbstract = objectDef instanceof InterfaceDef;

        MethodDef.MethodDefBuilder getterBuilder = MethodDef.builder("get" + capitalizedPropertyName)
            .addModifiers(property.getModifiersArray());

        if (!isAbstract) {
            getterBuilder.addStatement((aThis, methodParameters) -> aThis.field(propertyField).returning());
        }

        writeMethod(classWriter, objectDef, getterBuilder.build());

        MethodDef.MethodDefBuilder setterBuilder = MethodDef.builder("set" + capitalizedPropertyName)
            .addParameter(ParameterDef.of(property.getName(), property.getType()))
            .addModifiers(property.getModifiersArray());

        if (!isAbstract) {
            setterBuilder.addStatement((aThis, methodParameters) -> aThis.field(propertyField).assign(methodParameters.get(0)));
        }

        writeMethod(classWriter, objectDef, setterBuilder.build());
    }

    private void writeMethod(ClassVisitor classWriter, @Nullable ObjectDef objectDef, MethodDef methodDef) {
        String name = methodDef.getName();
        String methodDescriptor = getMethodDescriptor(objectDef, Objects.requireNonNullElse(methodDef.getReturnType(), TypeDef.VOID), methodDef.getParameters());

        MethodVisitor methodVisitor = classWriter.visitMethod(
            getModifiersFlag(methodDef.getModifiers()),
            name,
            methodDescriptor,
            SignatureWriterUtils.getMethodSignature(objectDef, methodDef),
            null
        );
        GeneratorAdapter generatorAdapter = new GeneratorAdapter(methodVisitor, getModifiersFlag(methodDef.getModifiers()), name, methodDescriptor);
        for (AnnotationDef annotation : methodDef.getAnnotations()) {
            methodVisitor.visitAnnotation(TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
        }

        methodVisitor.visitAnnotableParameterCount(methodDef.getParameters().size(), true);
        int parameterIndex = 0;
        for (ParameterDef parameter : methodDef.getParameters()) {
            for (AnnotationDef annotation : parameter.getAnnotations()) {
                AnnotationVisitor annotationVisitor =  methodVisitor.visitParameterAnnotation(parameterIndex, TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
                annotation.getValues().forEach(annotationVisitor::visit);
            }
            parameterIndex++;
        }

//        methodDef.getJavadoc().forEach(methodBuilder::addJavadoc);
//        for (AnnotationDef annotation : method.getAnnotations()) {
//            methodBuilder.addAnnotation(
//                asAnnotationSpec(annotation)
//            );
//        }        method.getStatements().stream()
//            .map(st -> renderStatementCodeBlock(objectDef, method, st))
//            .forEach(methodBuilder::addCode);
        Context context = new Context(objectDef, methodDef);
        List<StatementDef> statements = methodDef.getStatements();
        if (methodDef.isConstructor() && objectDef instanceof ClassDef) {
            if (statements.isEmpty()) {
                statements = List.of(
                    superConstructoInvocation()
                );
            } else {
                if (statements.stream().noneMatch(this::isConstructorInvocation)) {
                    statements = new ArrayList<>();
                    statements.add(superConstructoInvocation());
                    statements.addAll(methodDef.getStatements());
                }
            }
        }
        if (!statements.isEmpty()) {
            methodVisitor.visitCode();
            for (StatementDef statement : statements) {
                pushStatement(generatorAdapter, context, statement);
            }
            StatementDef statementDef = statements.get(statements.size() - 1);
            if (!(statementDef instanceof StatementDef.Return)) {
                generatorAdapter.returnValue();
            }
        }
        if (!statements.isEmpty()) {
            methodVisitor.visitMaxs(100, 100);
        }
        methodVisitor.visitEnd();
    }

    private StatementDef superConstructoInvocation() {
        return MethodDef.constructor().build((aThis, methodParameters) -> aThis.superRef().invoke(MethodDef.constructor().build()))
            .getStatements()
            .get(0);
    }

    private boolean isConstructorInvocation(StatementDef statement) {
        return statement instanceof ExpressionDef.CallInstanceMethod call && call.instance().type().equals(TypeDef.SUPER)
            || statement instanceof ExpressionDef.CallInstanceMethod2 call2 && call2.instance().type().equals(TypeDef.SUPER);
    }

    private void pushStatement(GeneratorAdapter generatorAdapter,
                               Context context,
                               StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Multi statements) {
            for (StatementDef statement : statements.statements()) {
                pushStatement(generatorAdapter, context, statement);
            }
            return;
        }
        if (statementDef instanceof StatementDef.If ifStatement) {
            Label elseLabel = new Label();
            renderConditionalExpression(generatorAdapter, context, ifStatement.condition(), elseLabel);
            pushStatement(generatorAdapter, context, ifStatement.statement());
            generatorAdapter.visitLabel(elseLabel);
            return;
        }
        if (statementDef instanceof StatementDef.IfElse ifStatement) {
            Label elseLabel = new Label();
            renderConditionalExpression(generatorAdapter, context, ifStatement.condition(), elseLabel);
            Label end = new Label();
            pushStatement(generatorAdapter, context, ifStatement.statement());
            generatorAdapter.visitLabel(end);
            generatorAdapter.visitLabel(elseLabel);
            pushStatement(generatorAdapter, context, ifStatement.elseStatement());
            return;
        }
        if (statementDef instanceof StatementDef.Switch aSwitch) {
            pushSwitchExpression(generatorAdapter, context, aSwitch.expression());
            Map<ExpressionDef.Constant, StatementDef> cases = new HashMap<>(aSwitch.cases());
            StatementDef defaultCase = cases.remove(null);
            if (defaultCase == null) {
                ExpressionDef.Constant constant = cases.keySet().stream().filter(c -> c.value() == null).findFirst().orElse(null);
                if (constant != null) {
                    defaultCase = cases.remove(constant);
                }
            }
            Map<Integer, StatementDef> map = cases.entrySet().stream().map(e -> Map.entry(toSwitchKey(e.getKey()), e.getValue())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            int[] keys = map.keySet().stream().mapToInt(x -> x).sorted().toArray();
            StatementDef finalDefaultCase = defaultCase;
            generatorAdapter.tableSwitch(keys, new TableSwitchGenerator() {
                @Override
                public void generateCase(int key, Label end) {
                    StatementDef exp = map.get(key);
                    pushStatement(generatorAdapter, context, exp);
                    generatorAdapter.goTo(end);
                }

                @Override
                public void generateDefault() {
                    pushStatement(generatorAdapter, context, finalDefaultCase);
                }
            });
            return;
        }
        if (statementDef instanceof StatementDef.While aWhile) {
            Label whileLoop = new Label();
            Label end = new Label();
            generatorAdapter.visitLabel(whileLoop);
            pushExpression(generatorAdapter, context, aWhile.expression(), TypeDef.Primitive.BOOLEAN);
            generatorAdapter.push(true);
            generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, end);
            pushStatement(generatorAdapter, context, aWhile.statement());
            generatorAdapter.goTo(whileLoop);
            generatorAdapter.visitLabel(end);
            return;
        }
        if (statementDef instanceof StatementDef.Throw aThrow) {
            pushExpression(generatorAdapter, context, aThrow.expression(), aThrow.expression().type());
            generatorAdapter.throwException();
            return;
        }
        if (statementDef instanceof StatementDef.Return aReturn) {
            if (aReturn.expression() != null) {
                pushExpression(generatorAdapter, context, aReturn.expression(), context.methodDef.getReturnType());
            }
            generatorAdapter.returnValue();
            return;
        }
        if (statementDef instanceof StatementDef.PutStaticField putStaticField) {
            pushExpression(generatorAdapter, context, putStaticField.expression(), putStaticField.type());
            generatorAdapter.putStatic(
                TypeUtils.getType(putStaticField.classDef(), context.objectDef),
                putStaticField.name(),
                TypeUtils.getType(putStaticField.type(), context.objectDef)
            );
            return;
        }
        if (statementDef instanceof StatementDef.PutField putField) {
            TypeDef owner = putField.instance().type();
            pushExpression(generatorAdapter, context, putField.instance(), owner);
            TypeDef fieldType = putField.type();
            pushExpression(generatorAdapter, context, putField.expression(), fieldType);
            generatorAdapter.putField(
                TypeUtils.getType(owner, context.objectDef),
                putField.name(),
                TypeUtils.getType(fieldType, context.objectDef)
            );
            return;
        }
        if (statementDef instanceof StatementDef.Assign assign) {
            VariableDef.Local local = assign.variable();
            pushExpression(generatorAdapter, context, assign.expression(), local.type());
            Integer localIndex = context.locals.get(local.name());
            generatorAdapter.storeLocal(localIndex);
            return;
        }
        if (statementDef instanceof StatementDef.DefineAndAssign assign) {
            VariableDef.Local local = assign.variable();
            Type localType = TypeUtils.getType(local.type(), context.objectDef);
            int localIndex = generatorAdapter.newLocal(localType);
            pushExpression(generatorAdapter, context, assign.expression(), local.type());
            generatorAdapter.storeLocal(localIndex);
            context.locals.put(local.name(), localIndex);
            return;
        }
        if (statementDef instanceof ExpressionDef expressionDef) {
            pushExpression(generatorAdapter, context, expressionDef, expressionDef.type(), true);
            return;
        }
        throw new IllegalStateException("Unrecognized statement: " + statementDef);
    }

    private void pushExpression(GeneratorAdapter generatorAdapter,
                                Context context,
                                ExpressionDef expressionDef,
                                TypeDef expectedType) {
        pushExpression(generatorAdapter, context, expressionDef, expectedType, false);
    }

    private void pushExpression(GeneratorAdapter generatorAdapter,
                                Context context,
                                ExpressionDef expressionDef,
                                TypeDef expectedType,
                                boolean statement) {
        if (expectedType.isPrimitive() &&
            expressionDef instanceof ExpressionDef.Constant constant
            && !constant.type().isPrimitive()
            && constant.value() != null
            && ReflectionUtils.getPrimitiveType(constant.value().getClass()).isPrimitive()) {
            expressionDef = ExpressionDef.primitiveConstant(constant.value());
        }
        pushExpressionNoCast(generatorAdapter, context, expressionDef, statement);
        TypeDef type = expressionDef.type();
        cast(generatorAdapter, context, type, expectedType);
    }

    private void cast(GeneratorAdapter generatorAdapter, Context context, TypeDef from, TypeDef to) {
        from = ObjectDef.getContextualType(context.objectDef, from);
        to = ObjectDef.getContextualType(context.objectDef, to);
        if ((from instanceof TypeDef.Primitive fromP && to instanceof TypeDef.Primitive toP) && !from.equals(to)) {
            generatorAdapter.cast(TypeUtils.getType(fromP), TypeUtils.getType(toP));
            return;
        }
        if ((from.isPrimitive() || to.isPrimitive()) && !from.equals(to)) {
            if (from instanceof TypeDef.Primitive primitive && !to.isPrimitive()) {
                box(generatorAdapter, context, from);
                checkCast(generatorAdapter, context, primitive.wrapperType(), to);
            }
            if (!from.isPrimitive() && to.isPrimitive()) {
                unbox(generatorAdapter, context, to);
            }
        } else if (!from.makeNullable().equals(to.makeNullable())) {
            checkCast(generatorAdapter, context, from, to);
        }
    }

    private void unbox(GeneratorAdapter generatorAdapter, Context context, TypeDef to) {
        generatorAdapter.unbox(TypeUtils.getType(to, context.objectDef));
    }

    private void box(GeneratorAdapter generatorAdapter, Context context, TypeDef from) {
        generatorAdapter.valueOf(TypeUtils.getType(from, context.objectDef));
    }

    private void pushExpressionNoCast(GeneratorAdapter generatorAdapter,
                                      Context context,
                                      ExpressionDef expressionDef,
                                      boolean statement) {
        if (expressionDef instanceof ExpressionDef.CallInstanceMethod2 invokeMethod) {
            TypeDef instanceType = invokeMethod.instance().type();
            pushExpression(generatorAdapter, context, invokeMethod.instance(), instanceType, false);
            for (ExpressionDef parameter : invokeMethod.parameters()) {
                pushExpression(generatorAdapter, context, parameter, parameter.type(), false);
            }
            if (invokeMethod.method().getDeclaringClass().isInterface()) {
                generatorAdapter.invokeInterface(TypeUtils.getType(instanceType, context.objectDef), Method.getMethod(invokeMethod.method()));
            } else {
                generatorAdapter.invokeVirtual(TypeUtils.getType(instanceType, context.objectDef), Method.getMethod(invokeMethod.method()));
            }
            if (!invokeMethod.method().getReturnType().equals(Void.TYPE) && statement) {
                generatorAdapter.pop();
            }
            return;
        }
        if (expressionDef instanceof ExpressionDef.CallInstanceMethod callInstanceMethod) {
            ExpressionDef instance = callInstanceMethod.instance();
            TypeDef instanceType = instance.type();
            pushExpression(generatorAdapter, context, instance, instanceType);
            for (ExpressionDef parameter : callInstanceMethod.parameters()) {
                pushExpression(generatorAdapter, context, parameter, parameter.type());
            }
            if (instance instanceof VariableDef.Super) {
                generatorAdapter.invokeConstructor(
                    TypeUtils.getType(instanceType, context.objectDef),
                    new Method(callInstanceMethod.name(), getMethodDescriptor2(context.objectDef, callInstanceMethod.returningType(), callInstanceMethod.parameters()))
                );
            } else if (instanceType instanceof ClassTypeDef classTypeDef) {
                if (classTypeDef.isInterface()) {
                    generatorAdapter.invokeInterface(
                        TypeUtils.getType(instanceType, context.objectDef),
                        new Method(callInstanceMethod.name(), getMethodDescriptor2(context.objectDef, callInstanceMethod.returningType(), callInstanceMethod.parameters()))
                    );
                } else {
                    generatorAdapter.invokeVirtual(
                        TypeUtils.getType(instanceType, context.objectDef),
                        new Method(callInstanceMethod.name(), getMethodDescriptor2(context.objectDef, callInstanceMethod.returningType(), callInstanceMethod.parameters()))
                    );
                }
            } else {
                throw new IllegalStateException("Unrecognized instance type: " + instanceType);
            }
            if (!callInstanceMethod.returningType().equals(TypeDef.VOID) && statement) {
                generatorAdapter.pop();
            }
            return;
        }
        if (expressionDef instanceof ExpressionDef.NewInstance newInstance) {
            Type type = TypeUtils.getType(newInstance.type(), context.objectDef);
            generatorAdapter.newInstance(type);
            generatorAdapter.dup();
            for (ExpressionDef expression : newInstance.values()) {
                pushExpression(generatorAdapter, context, expression, expression.type());
            }
            generatorAdapter.invokeConstructor(
                type,
                new Method("<init>", getConstructorDescriptor(context.objectDef, newInstance.values().stream().map(ExpressionDef::type).toList()))
            );
            return;
        }
//        if (expressionDef instanceof TypeDef.Primitive.PrimitiveInstance primitiveInstance) {
//            return renderExpression(context, primitiveInstance.value());
//        }
        if (expressionDef instanceof ExpressionDef.NewArrayOfSize newArray) {
            generatorAdapter.push(newArray.size());
            generatorAdapter.newArray(TypeUtils.getType(newArray.type().componentType(), context.objectDef));
            return;
        }
        if (expressionDef instanceof ExpressionDef.NewArrayInitialized newArray) {
            List<ExpressionDef> expressions = newArray.expressions();
            generatorAdapter.push(expressions.size());
            TypeDef componentType = newArray.type().componentType();
            Type type = TypeUtils.getType(componentType, context.objectDef);
            generatorAdapter.newArray(type);

            if (!expressions.isEmpty()) {
                int index = 0;
                for (ExpressionDef expression : expressions) {
                    generatorAdapter.dup();
                    generatorAdapter.push(index++);
                    pushExpression(generatorAdapter, context, expression, componentType);
                    generatorAdapter.arrayStore(type);
                }
            }
            return;
        }
        if (expressionDef instanceof ExpressionDef.Cast castExpressionDef) {
            ExpressionDef exp = castExpressionDef.expressionDef();
            TypeDef from = exp.type();
            pushExpression(generatorAdapter, context, exp, from);
            TypeDef to = castExpressionDef.type();
            cast(generatorAdapter, context, from, to);
            return;
        }
        if (expressionDef instanceof ExpressionDef.Constant constant) {
            pushConstant(generatorAdapter, constant);
            return;
        }
        if (expressionDef instanceof ExpressionDef.CallStaticMethod staticMethod) {
            for (ExpressionDef parameter : staticMethod.parameters()) {
                pushExpression(generatorAdapter, context, parameter, parameter.type());
            }
            generatorAdapter.invokeStatic(
                TypeUtils.getType(staticMethod.classDef(), context.objectDef),
                new Method(staticMethod.name(), getMethodDescriptor2(context.objectDef, staticMethod.returningType(), staticMethod.parameters()))
            );
            return;
        }
//        if (expressionDef instanceof ExpressionDef.GetPropertyValue getPropertyValue) {
//            PropertyElement propertyElement = getPropertyValue.propertyElement();
//            Optional<? extends MemberElement> readPropertyMember = propertyElement.getReadMember();
//            if (readPropertyMember.isEmpty()) {
//                throw new IllegalStateException("Read member not found for property: " + propertyElement);
//            }
//            MemberElement memberElement = readPropertyMember.get();
//            if (memberElement instanceof MethodElement methodElement) {
//                return renderExpression(context, getPropertyValue.instance().invoke(methodElement));
//            }
//            if (memberElement instanceof FieldElement fieldElement) {
//                // TODO: support field
//            }
//            throw new IllegalStateException("Unrecognized property read element: " + propertyElement);
//        }
//        if (expressionDef instanceof ExpressionDef.Condition condition) {
//            renderExpression(generatorAdapter, context, condition.left());
//            renderExpression(generatorAdapter, context, condition.right());
//            return;
//        }
//        if (expressionDef instanceof ExpressionDef.And andExpressionDef) {
//            return CodeBlock.concat(
//                renderCondition(context, andExpressionDef.left()),
//                CodeBlock.of(" && "),
//                renderCondition(context, andExpressionDef.right())
//            );
//        }
//        if (expressionDef instanceof ExpressionDef.Or orExpressionDef) {
//            return CodeBlock.concat(
//                renderCondition(context, orExpressionDef.left()),
//                CodeBlock.of(" || "),
//                renderCondition(context, orExpressionDef.right())
//            );
//        }
        if (expressionDef instanceof ExpressionDef.IfElse conditionIfElse) {
            Label elseLabel = new Label();
            renderConditionalExpression(generatorAdapter, context, conditionIfElse.condition(), elseLabel);
            Label end = new Label();
            pushExpression(generatorAdapter, context, conditionIfElse.expression(), conditionIfElse.type());
            generatorAdapter.goTo(end);
            generatorAdapter.visitLabel(elseLabel);
            pushExpression(generatorAdapter, context, conditionIfElse.elseExpression(), conditionIfElse.type());
            generatorAdapter.visitLabel(end);
            return;
        }
        if (expressionDef instanceof ExpressionDef.Switch aSwitch) {
            ExpressionDef expression = aSwitch.expression();
            pushSwitchExpression(generatorAdapter, context, expression);
            Map<ExpressionDef.Constant, ExpressionDef> cases = new HashMap<>(aSwitch.cases());
            ExpressionDef defaultCase = cases.remove(null);
            if (defaultCase == null) {
                ExpressionDef.Constant constant = cases.keySet().stream().filter(c -> c.value() == null).findFirst().orElse(null);
                if (constant != null) {
                    defaultCase = cases.remove(constant);
                }
            }
            Map<Integer, ExpressionDef> map = cases.entrySet().stream().map(e -> Map.entry(toSwitchKey(e.getKey()), e.getValue())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            int[] keys = map.keySet().stream().mapToInt(x -> x).toArray();
            ExpressionDef finalDefaultCase = defaultCase;
            generatorAdapter.tableSwitch(keys, new TableSwitchGenerator() {
                @Override
                public void generateCase(int key, Label end) {
                    ExpressionDef exp = map.get(key);
                    pushExpression(generatorAdapter, context, exp, aSwitch.type());
                    generatorAdapter.goTo(end);
                }

                @Override
                public void generateDefault() {
                    pushExpression(generatorAdapter, context, finalDefaultCase, aSwitch.type());
                }
            });
            return;
        }
        if (expressionDef instanceof ExpressionDef.SwitchYieldCase switchYieldCase) {
            pushStatement(generatorAdapter, context, switchYieldCase.statement());
            return;
        }
        if (expressionDef instanceof VariableDef variableDef) {
            pushVariable(generatorAdapter, context, variableDef);
            return;
        }
//        if (expressionDef instanceof ExpressionDef.InvokeGetClassMethod invokeGetClassMethod) {
//            return renderExpression(context, invokeGetClassMethod.instance().invoke("getClass", TypeDef.of(Class.class)));
//        }
//        if (expressionDef instanceof ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod) {
//            ExpressionDef instance = invokeHashCodeMethod.instance();
//            TypeDef type = instance.type();
//            if (type.isArray()) {
//                if (type instanceof TypeDef.Array array && array.dimensions() > 1) {
//                    return renderExpression(
//                        objectDef,
//                        methodDef,
//                        ClassTypeDef.of(Arrays.class)
//                            .invokeStatic(
//                                "deepHashCode",
//                                TypeDef.Primitive.BOOLEAN,
//                                invokeHashCodeMethod.instance()
//                            ));
//                }
//                return renderExpression(
//                    objectDef,
//                    methodDef,
//                    ClassTypeDef.of(Arrays.class)
//                        .invokeStatic(
//                            "hashCode",
//                            TypeDef.Primitive.BOOLEAN,
//                            invokeHashCodeMethod.instance()
//                        ));
//            }
//            if (type instanceof TypeDef.Primitive primitive) {
//                return renderExpression(
//                    objectDef,
//                    methodDef,
//                    primitive.wrapperType()
//                        .invokeStatic(
//                            "hashCode",
//                            TypeDef.Primitive.BOOLEAN,
//                            invokeHashCodeMethod.instance()
//                        ));
//            }
//            return renderExpression(context, instance.isNull().asConditionIfElse(
//                ExpressionDef.constant(0),
//                instance.invoke("hashCode", TypeDef.Primitive.BOOLEAN))
//            );
//        }
//        if (expressionDef instanceof ExpressionDef.EqualsStructurally equalsStructurally) {
//            var type = equalsStructurally.instance().type();
//            if (type.isArray()) {
//                if (type instanceof TypeDef.Array array && array.dimensions() > 1) {
//                    return renderExpression(
//                        objectDef,
//                        methodDef,
//                        ClassTypeDef.of(Arrays.class)
//                            .invokeStatic(
//                                "deepEquals",
//                                TypeDef.Primitive.BOOLEAN,
//                                equalsStructurally.instance(),
//                                equalsStructurally.other())
//                    );
//                }
//                return renderExpression(
//                    objectDef,
//                    methodDef,
//                    ClassTypeDef.of(Arrays.class)
//                        .invokeStatic(
//                            "equals",
//                            TypeDef.Primitive.BOOLEAN,
//                            equalsStructurally.instance(),
//                            equalsStructurally.other())
//                );
//            }
//            return renderExpression(
//                objectDef,
//                methodDef,
//                equalsStructurally.instance().invoke("equals", TypeDef.Primitive.BOOLEAN, equalsStructurally.other())
//            );
//        }
//        if (expressionDef instanceof ExpressionDef.EqualsReferentially equalsReferentially) {
//            return CodeBlock.builder()
//                .add(renderExpression(context, equalsReferentially.instance()))
//                .add(" == ")
//                .add(renderExpression(context, equalsReferentially.other()))
//                .build();
//        }
        throw new IllegalStateException("Unrecognized expression: " + expressionDef);
    }

    private void checkCast(GeneratorAdapter generatorAdapter, Context context, TypeDef from, TypeDef to) {
        TypeDef toType = ObjectDef.getContextualType(context.objectDef, to);
        if (!toType.makeNullable().equals(from.makeNullable())) {
            generatorAdapter.checkCast(TypeUtils.getType(toType, context.objectDef));
        }
    }

    private void pushSwitchExpression(GeneratorAdapter generatorAdapter,
                                      Context context,
                                      ExpressionDef expression) {
        TypeDef switchExpressionType = expression.type();
        pushExpression(generatorAdapter, context, expression, switchExpressionType);
        if (switchExpressionType instanceof ClassTypeDef classTypeDef && classTypeDef.getName().equals(String.class.getName())) {
            generatorAdapter.invokeVirtual(
                Type.getType(String.class),
                Method.getMethod(ReflectionUtils.getRequiredMethod(String.class, "hashCode"))
            );
        } else if (!switchExpressionType.equals(TypeDef.Primitive.INT)) {
            throw new IllegalStateException("Not allowed switch expression type: " + switchExpressionType);
        }
    }

    private int toSwitchKey(ExpressionDef.Constant constant) {
        if (constant.value() instanceof String s) {
            return s.hashCode();
        }
        if (constant.value() instanceof Integer i) {
            return i;
        }
        throw new IllegalStateException("Unrecognized constant for a switch key: " + constant);
    }

    private void renderConditionalExpression(GeneratorAdapter generatorAdapter,
                                             Context context,
                                             ExpressionDef expressionDef,
                                             Label elseLabel) {
        if (expressionDef instanceof ExpressionDef.Condition condition) {
            if (condition.operator().trim().equals("==")) {
                generatorAdapter.ifCmp(TypeUtils.getType(condition.type(), context.objectDef), GeneratorAdapter.EQ, elseLabel);
            } else if (condition.operator().trim().equals("!=")) {
                generatorAdapter.ifCmp(TypeUtils.getType(condition.type(), context.objectDef), GeneratorAdapter.NE, elseLabel);
            } else {
                throw new IllegalStateException("Unrecognized condition operator: " + condition.operator());
            }
            return;
        }
        if (expressionDef instanceof ExpressionDef.IsNull isNull) {
            pushExpression(generatorAdapter, context, isNull.expression(), isNull.expression().type());
            generatorAdapter.ifNonNull(elseLabel);
            return;
        }
        if (expressionDef instanceof ExpressionDef.IsNotNull isNotNull) {
            pushExpression(generatorAdapter, context, isNotNull.expression(), isNotNull.expression().type());
            generatorAdapter.ifNull(elseLabel);
            return;
        }
        if (expressionDef instanceof ExpressionDef.Constant constant) {
            // TODO: allow only boolean
            pushConstant(generatorAdapter, constant);
            generatorAdapter.push(true);
            generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, elseLabel);
            return;
        }
        throw new IllegalStateException("Unrecognized conditional expression: " + expressionDef);
    }

    private void pushVariable(GeneratorAdapter generatorAdapter,
                              Context context,
                              VariableDef variableDef) {
        if (variableDef instanceof VariableDef.Local localVariableDef) {
            int index = context.locals.get(localVariableDef.name());
            generatorAdapter.loadLocal(index);
            return;
        }
        if (variableDef instanceof VariableDef.MethodParameter parameterVariableDef) {
            if (context.methodDef == null) {
                throw new IllegalStateException("Accessing method parameters is not available");
            }
            ParameterDef parameterDef = context.methodDef.getParameters().stream().filter(p -> p.getName().equals(parameterVariableDef.name())).findFirst().orElseThrow();
            int parameterIndex = context.methodDef.getParameters().indexOf(parameterDef);
            generatorAdapter.loadArg(parameterIndex);
            return;
        }
        if (variableDef instanceof VariableDef.StaticField field) {
            TypeDef owner = field.ownerType();
            TypeDef fieldType = field.type();
            generatorAdapter.getStatic(TypeUtils.getType(owner, context.objectDef), field.name(), TypeUtils.getType(fieldType, context.objectDef));
            return;
        }
        if (variableDef instanceof VariableDef.Field field) {
            if (context.objectDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            if (context.objectDef instanceof ClassDef classDef) {
                if (!classDef.hasField(field.name()) && classDef.getProperties().stream().noneMatch(prop -> prop.getName().equals(field.name()))) {
                    throw new IllegalStateException("Field " + field.name() + " is not available in [" + classDef + "]:" + classDef.getFields());
                }
            } else {
                throw new IllegalStateException("Field access no supported on the object definition: " + context.objectDef);
            }

            TypeDef owner = field.instance().type();
            pushExpression(generatorAdapter, context, field.instance(), owner);
            TypeDef fieldType = field.type();
            generatorAdapter.getField(TypeUtils.getType(owner, context.objectDef), field.name(), TypeUtils.getType(fieldType, context.objectDef));
            return;
        }
        if (variableDef instanceof VariableDef.This) {
            if (context.objectDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            generatorAdapter.loadThis();
            return;
        }
        if (variableDef instanceof VariableDef.Super) {
            if (context.objectDef == null) {
                throw new IllegalStateException("Accessing 'super' is not available");
            }
            generatorAdapter.loadThis();
            return;
        }
        throw new IllegalStateException("Unrecognized variable: " + variableDef);
    }

    private void pushConstant(GeneratorAdapter generatorAdapter,
                              ExpressionDef.Constant constant) {
        TypeDef type = constant.type();
        Object value = constant.value();
        if (value == null) {
            generatorAdapter.push((String) null);
            return;
        }
//        if (type instanceof ClassTypeDef classTypeDef && classTypeDef.isEnum()) {
//            return renderExpression(null, null, new VariableDef.StaticField(
//                type,
//                value instanceof Enum<?> anEnum ? anEnum.name() : value.toString(),
//                type
//            ));
//        }
        if (type instanceof TypeDef.Primitive primitive) {
            switch (primitive.name()) {
                case "long" -> generatorAdapter.push((long) value);
                case "float" -> generatorAdapter.push((float) value);
                case "double" -> generatorAdapter.push((double) value);
                case "boolean" -> generatorAdapter.push((boolean) value);
                case "byte" -> generatorAdapter.push((byte) value);
                case "int" -> generatorAdapter.push((int) value);
                default ->
                    throw new IllegalStateException("Unrecognized primitive type: " + primitive.name());
            }
//            if (!expectedType.isPrimitive()) {
//                generatorAdapter.box(asType(primitive, null));
//            }
            return;
        }
        if (value instanceof String string) {
            generatorAdapter.push(string);
            return;
        }
        if (value instanceof Enum<?> enumConstant) {
            Type enumType = Type.getType(enumConstant.getDeclaringClass());
            generatorAdapter.getStatic(enumType, enumConstant.name(), enumType);
            return;
        }
//        if (value instanceof Class<?> aClass) {
//            generatorAdapter.push(Type.getObjectType(aClass.getName().replace('.', '/')));
//            return;
//        }
        if (value instanceof ClassTypeDef aClass) {
            generatorAdapter.push(Type.getObjectType(aClass.getName().replace('.', '/')));
            return;
        }
        if (value instanceof Integer integer) {
            generatorAdapter.push(integer);
            generatorAdapter.valueOf(Type.getType(int.class));
            return;
        }
//        } else if (type instanceof TypeDef.Array arrayDef) {
//            if (value.getClass().isArray()) {
//                final var array = value;
//                final var values = IntStream.range(0, Array.getLength(array))
//                    .mapToObj(i -> renderConstantExpression(new ExpressionDef.Constant(arrayDef.componentType(), Array.get(array, i))))
//                    .collect(CodeBlock.joining(", "));
//                final String typeName;
//                if (arrayDef.componentType() instanceof ClassTypeDef arrayClassTypeDef) {
//                    typeName = arrayClassTypeDef.getSimpleName();
//                } else if (arrayDef.componentType() instanceof TypeDef.Primitive arrayPrimitive) {
//                    typeName = arrayPrimitive.name();
//                } else {
//                    throw new IllegalStateException("Unrecognized expression: " + constant);
//                }
//                return CodeBlock.concat(
//                    CodeBlock.of("new $N[] {", typeName),
//                    values,
//                    CodeBlock.of("}"));
//            }
//        } else if (type instanceof ClassTypeDef classTypeDef) {
//            String name = classTypeDef.getName();
//            if (ClassUtils.isJavaLangType(name)) {
//                return switch (name) {
//                    case "java.lang.Long" -> CodeBlock.of(value + "l");
//                    case "java.lang.Float" -> CodeBlock.of(value + "f");
//                    case "java.lang.Double" -> CodeBlock.of(value + "d");
//                    case "java.lang.String" -> CodeBlock.of("$S", value);
//                    default -> CodeBlock.of("$L", value);
//                };
//            } else {
//                return CodeBlock.of("$L", value);
//            }
//        }
        throw new IllegalStateException("Unrecognized constant: " + constant);
    }

    private int getModifiersFlag(Set<Modifier> modifiers) {
        int access = 0;
        if (modifiers.contains(Modifier.PUBLIC)) {
            access |= ACC_PUBLIC;
        }
        if (modifiers.contains(Modifier.PRIVATE)) {
            access |= ACC_PRIVATE;
        }
        if (modifiers.contains(Modifier.PROTECTED)) {
            access |= ACC_PROTECTED;
        }
        if (modifiers.contains(Modifier.FINAL)) {
            access |= ACC_FINAL;
        }
        if (modifiers.contains(Modifier.ABSTRACT)) {
            access |= ACC_ABSTRACT;
        }
        if (modifiers.contains(Modifier.STATIC)) {
            access |= ACC_STATIC;
        }
        return access;
    }

    @Override
    public void write(ObjectDef objectDef, VisitorContext context, Element... originatingElements) {
        try (OutputStream os = context.visitClass(objectDef.getName(), originatingElements)) {
            os.write(generateClassBytes(objectDef).toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
            Element element = originatingElements.length > 0 ? originatingElements[0] : null;
            throw new ProcessingException(element, "Failed to generate '" + objectDef.getName() + "': " + e.getMessage(), e);
        }
    }

    private static String getConstructorDescriptor(@Nullable ObjectDef objectDef, Collection<TypeDef> types) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');

        for (TypeDef argumentType : types) {
            builder.append(TypeUtils.getType(argumentType, objectDef).getDescriptor());
        }

        return builder.append(")V").toString();
    }

    private static String getMethodDescriptor(@Nullable ObjectDef objectDef, TypeDef returnType, Collection<ParameterDef> argumentTypes) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        for (ParameterDef argumentType : argumentTypes) {
            builder.append(TypeUtils.getType(argumentType.getType(), objectDef));
        }
        builder.append(')');
        builder.append(TypeUtils.getType(Objects.requireNonNullElse(returnType, TypeDef.VOID), objectDef));
        return builder.toString();
    }

    /**
     * @param returnType    The return type
     * @param argumentTypes The argument types
     * @return The method descriptor
     */
    private static String getMethodDescriptor2(@Nullable ObjectDef objectDef, TypeDef returnType, Collection<ExpressionDef> argumentTypes) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        for (ExpressionDef argumentType : argumentTypes) {
            TypeDef instanceType = argumentType.type();
            builder.append(TypeUtils.getType(instanceType, objectDef));
        }
        builder.append(')');
        builder.append(TypeUtils.getType(Objects.requireNonNullElse(returnType, TypeDef.VOID), objectDef));
        return builder.toString();
    }

    private record Context(@Nullable ObjectDef objectDef,
                           MethodDef methodDef,
                           Map<String, Integer> locals) {

        public Context(@Nullable ObjectDef objectDef,
                       MethodDef methodDef) {
            this(objectDef, methodDef, new HashMap<>());
        }

    }

}
