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
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TableSwitchGenerator;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Collection;
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
        writeClass(classWriter, objectDef);
        classWriter.visitEnd();
        return classWriter;
    }

    private void writeClass(ClassWriter classWriter, ObjectDef objectDef) {
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

    private void writeEnum(ClassWriter classWriter, EnumDef enumDef) {
        ClassTypeDef enumTypeDef = enumDef.asTypeDef();
        Type enumType = TypeUtils.getType(enumTypeDef);

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
            enumDef.getSuperinterfaces().stream().map(TypeUtils::getType).map(Type::getInternalName).toArray(String[]::new)
        );
        for (String enumConstant : enumDef.getEnumConstants()) {
            FieldVisitor fieldVisitor = classWriter.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC + ACC_ENUM, enumConstant, enumType.getDescriptor(), null, null);
            fieldVisitor.visitEnd();
        }

        writeMethod(classWriter, enumDef, MethodDef.constructor()
            .addParameter(ParameterDef.of("name", TypeDef.STRING))
            .addParameter(ParameterDef.of("ordinal", TypeDef.Primitive.INT))
            .build((aThis, methodParameters) -> aThis.superRef().invoke(MethodDef.constructor().build(), methodParameters.get(0), methodParameters.get(1))));
//        private <init>(Ljava/lang/String;I)V
//            // parameter synthetic  $enum$name
//            // parameter synthetic  $enum$ordinal
//            L0
//        LINENUMBER 5 L0
//        ALOAD 0
//        ALOAD 1
//        ILOAD 2
//        INVOKESPECIAL java/lang/Enum.<init> (Ljava/lang/String;I)V
//            RETURN
//        L1
//        LOCALVARIABLE this Lio/micronaut/sourcegen/example/MyEnum1; L0 L1 0
//        MAXSTACK = 3
//        MAXLOCALS = 3

        FieldDef $valuesField = FieldDef.builder("$VALUES").ofType(enumTypeDef.array()).addModifiers(Modifier.STATIC, Modifier.PRIVATE).build();

        writeField(classWriter, $valuesField);

//        private final static synthetic [Lio/micronaut/sourcegen/example/MyEnum1; $VALUES

        MethodDef valuesMethod = MethodDef.builder("values")
            .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
            .returns(enumTypeDef.array())
            .build((aThis, methodParameters) ->
                enumTypeDef.getStatic($valuesField)
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
                            .<ExpressionDef>map(name -> enumTypeDef.getStatic(name, enumTypeDef))
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
                enumTypeDef.putStatic(
                    enumConstant,
                    enumTypeDef,
                    enumTypeDef.instantiate(
                        ExpressionDef.constant(enumConstant),
                        TypeDef.Primitive.INT.constant(i++)
                    )
                )
            );
        }

        staticMethodBuilder.addStatement(enumTypeDef.putStatic($valuesField, enumTypeDef.invokeStatic($valuesMethod)));
        writeMethod(classWriter, null, staticMethodBuilder.build());
    }

    private void writeField(ClassWriter classWriter, FieldDef fieldDef) {
        FieldVisitor fieldVisitor = classWriter.visitField(
            getModifiersFlag(fieldDef.getModifiers()),
            fieldDef.getName(),
            TypeUtils.getType(fieldDef.getType()).getDescriptor(),
            SignatureWriterUtils.getFieldSignature(fieldDef),
            null
        );
        fieldVisitor.visitEnd();
    }

    private void writeInterface(ClassWriter classWriter, InterfaceDef interfaceDef) {
        classWriter.visit(V17,
            ACC_INTERFACE | ACC_ABSTRACT | getModifiersFlag(interfaceDef.getModifiers()),
            TypeUtils.getType(interfaceDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getInterfaceSignature(interfaceDef),
            Type.getType(Object.class).getInternalName(),
            interfaceDef.getSuperinterfaces().stream().map(TypeUtils::getType).map(Type::getInternalName).toArray(String[]::new)
        );
        for (MethodDef method : interfaceDef.getMethods()) {
            writeMethod(classWriter, interfaceDef, method);
        }
        classWriter.visitEnd();
    }

    private void writeRecord(ClassWriter classWriter, RecordDef recordDef) {
        classWriter.visit(V17, ACC_RECORD | getModifiersFlag(recordDef.getModifiers()), TypeUtils.getType(recordDef.asTypeDef()).getInternalName(), null, null, null);
    }

    private void writeClass(ClassWriter classWriter, ClassDef classDef) {
        String CONSTRUCTOR_NAME = "<init>";
        String DESCRIPTOR_DEFAULT_CONSTRUCTOR = "()V";

        classWriter.visit(
            V17,
            getModifiersFlag(classDef.getModifiers()),
            TypeUtils.getType(classDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getClassSignature(classDef),
            classDef.getSuperclass() == null ? Type.getType(Object.class).getInternalName() : TypeUtils.getType(classDef.getSuperclass()).getInternalName(),
            classDef.getSuperinterfaces().stream().map(TypeUtils::getType).map(Type::getInternalName).toArray(String[]::new)
        );

        MethodVisitor defaultConstructor = classWriter.visitMethod(ACC_PUBLIC, CONSTRUCTOR_NAME, DESCRIPTOR_DEFAULT_CONSTRUCTOR, null, null);
        GeneratorAdapter generatorAdapter = new GeneratorAdapter(defaultConstructor, ACC_PUBLIC, CONSTRUCTOR_NAME, DESCRIPTOR_DEFAULT_CONSTRUCTOR);

        generatorAdapter.visitCode();
        generatorAdapter.loadThis();
        generatorAdapter.invokeConstructor(Type.getType(Object.class), Method.getMethod("void <init> ()"));
        generatorAdapter.returnValue();
        generatorAdapter.visitMaxs(2, 1);
        generatorAdapter.visitEnd();

        for (MethodDef method : classDef.getMethods()) {
            writeMethod(classWriter, classDef, method);
        }
    }

    private void writeMethod(ClassWriter classWriter, @Nullable ObjectDef objectDef, MethodDef methodDef) {
        String name = methodDef.getName();
        String methodDescriptor = getMethodDescriptor(Objects.requireNonNullElse(methodDef.getReturnType(), TypeDef.VOID), methodDef.getParameters());

        MethodVisitor methodVisitor = classWriter.visitMethod(
            getModifiersFlag(methodDef.getModifiers()),
            name,
            methodDescriptor,
            SignatureWriterUtils.getMethodSignature(methodDef),
            null
        );
        GeneratorAdapter generatorAdapter = new GeneratorAdapter(methodVisitor, getModifiersFlag(methodDef.getModifiers()), name, methodDescriptor);

//        methodDef.getJavadoc().forEach(methodBuilder::addJavadoc);
//        for (AnnotationDef annotation : method.getAnnotations()) {
//            methodBuilder.addAnnotation(
//                asAnnotationSpec(annotation)
//            );
//        }        method.getStatements().stream()
//            .map(st -> renderStatementCodeBlock(objectDef, method, st))
//            .forEach(methodBuilder::addCode);
        List<StatementDef> statements = methodDef.getStatements();
        if (!statements.isEmpty()) {
            methodVisitor.visitCode();
            for (StatementDef statement : statements) {
                pushStatement(generatorAdapter, objectDef, methodDef, statement);
            }
            methodVisitor.visitMaxs(20, 20);
        }
        if (methodDef.getName().endsWith("init>")) {
            generatorAdapter.returnValue();
        }
        methodVisitor.visitEnd();
    }

    private void pushStatement(GeneratorAdapter generatorAdapter,
                               @Nullable ObjectDef objectDef,
                               MethodDef methodDef,
                               StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Multi statements) {
            for (StatementDef statement : statements.statements()) {
                pushStatement(generatorAdapter, objectDef, methodDef, statement);
            }
            return;
        }
        if (statementDef instanceof StatementDef.If ifStatement) {
            Label elseLabel = new Label();
            renderConditionalExpression(generatorAdapter, objectDef, methodDef, ifStatement.condition(), elseLabel);
            pushStatement(generatorAdapter, objectDef, methodDef, ifStatement.statement());
            generatorAdapter.visitLabel(elseLabel);
            return;
        }
        if (statementDef instanceof StatementDef.IfElse ifStatement) {
            Label elseLabel = new Label();
            renderConditionalExpression(generatorAdapter, objectDef, methodDef, ifStatement.condition(), elseLabel);
            Label end = new Label();
            pushStatement(generatorAdapter, objectDef, methodDef, ifStatement.statement());
            generatorAdapter.visitLabel(end);
            generatorAdapter.visitLabel(elseLabel);
            pushStatement(generatorAdapter, objectDef, methodDef, ifStatement.elseStatement());
            return;
        }
        if (statementDef instanceof StatementDef.Switch aSwitch) {
            pushSwitchExpression(generatorAdapter, objectDef, methodDef, aSwitch.expression());
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
                    pushStatement(generatorAdapter, objectDef, methodDef, exp);
                    generatorAdapter.goTo(end);
                }

                @Override
                public void generateDefault() {
                    pushStatement(generatorAdapter, objectDef, methodDef, finalDefaultCase);
                }
            });
            return;
        }
        if (statementDef instanceof StatementDef.While aWhile) {
//            CodeBlock.Builder builder = CodeBlock.builder();
//            builder.add("while (");
//            builder.add(renderExpression(objectDef, methodDef, aWhile.expression()));
//            builder.add(") {\n");
//            builder.indent();
//            builder.add(renderStatementCodeBlock(objectDef, methodDef, aWhile.statement()));
//            builder.unindent();
//            builder.add("}\n");
//            return builder.build();
        }
        renderStatement(generatorAdapter, objectDef, methodDef, statementDef);
    }

    private void renderStatement(GeneratorAdapter generatorAdapter, @Nullable ObjectDef objectDef, MethodDef methodDef, StatementDef statementDef) {
//        if (statementDef instanceof StatementDef.Throw aThrow) {
//            return CodeBlock.concat(
//                CodeBlock.of("throw "),
//                renderExpression(objectDef, methodDef, aThrow.variableDef())
//            );
//        }
        if (statementDef instanceof StatementDef.Return aReturn) {
            if (aReturn.expression() != null) {
                pushExpression(generatorAdapter, objectDef, methodDef, aReturn.expression(), methodDef.getReturnType());
            }
            generatorAdapter.returnValue();
            return;
        }
//        if (statementDef instanceof StatementDef.Assign assign) {
//            return CodeBlock.concat(
//                renderExpression(objectDef, methodDef, assign.variable()),
//                CodeBlock.of(" = "),
//                renderExpression(objectDef, methodDef, assign.expression())
//            );
//        }
//        if (statementDef instanceof StatementDef.DefineAndAssign assign) {
//            return CodeBlock.concat(
//                CodeBlock.of("$T $L", asType(assign.variable().type(), objectDef), assign.variable().name()),
//                CodeBlock.of(" = "),
//                renderExpression(objectDef, methodDef, assign.expression())
//            );
//        }
        if (statementDef instanceof ExpressionDef expressionDef) {
            pushExpression(generatorAdapter, objectDef, methodDef, expressionDef, expressionDef.type());
            return;
        }
        throw new IllegalStateException("Unrecognized statement: " + statementDef);
    }

    private void pushExpression(GeneratorAdapter generatorAdapter,
                                @Nullable ObjectDef objectDef,
                                MethodDef methodDef,
                                ExpressionDef expressionDef,
                                TypeDef expectedType) {
        if (expressionDef instanceof ExpressionDef.GetStaticField getStaticField) {
            generatorAdapter.getStatic(
                TypeUtils.getType(getStaticField.classDef()),
                getStaticField.name(),
                TypeUtils.getType(getStaticField.type())
            );
            return;
        }
        if (expressionDef instanceof ExpressionDef.PutStaticField putStaticField) {
            pushExpression(generatorAdapter, objectDef, methodDef, putStaticField.expression(), putStaticField.type());
            generatorAdapter.putStatic(
                TypeUtils.getType(putStaticField.classDef()),
                putStaticField.name(),
                TypeUtils.getType(putStaticField.type())
            );
            return;
        }
        if (expressionDef instanceof ExpressionDef.CallInstanceMethod2 invokeMethod) {
            TypeDef instanceType = getInstanceType(objectDef, invokeMethod.instance().type());
            pushExpression(generatorAdapter, objectDef, methodDef, invokeMethod.instance(), instanceType);
            for (ExpressionDef parameter : invokeMethod.parameters()) {
                pushExpression(generatorAdapter, objectDef, methodDef, parameter, parameter.type());
            }
            generatorAdapter.invokeVirtual(TypeUtils.getType(instanceType), Method.getMethod(invokeMethod.method()));
            return;
        }
        if (expressionDef instanceof ExpressionDef.CallInstanceMethod callInstanceMethod) {
            ExpressionDef instance = callInstanceMethod.instance();
            TypeDef instanceType = getInstanceType(objectDef, instance.type());
            pushExpression(generatorAdapter, objectDef, methodDef, instance, instanceType);
            for (ExpressionDef parameter : callInstanceMethod.parameters()) {
                pushExpression(generatorAdapter, objectDef, methodDef, parameter, parameter.type());
            }
            if (instance instanceof VariableDef.Super) {
                generatorAdapter.invokeConstructor(
                    TypeUtils.getType(instanceType),
                    new Method(callInstanceMethod.name(), getMethodDescriptor2(callInstanceMethod.returningType(), callInstanceMethod.parameters()))
                );
            } else {
                generatorAdapter.invokeVirtual(
                    TypeUtils.getType(instanceType),
                    new Method(callInstanceMethod.name(), getMethodDescriptor2(callInstanceMethod.returningType(), callInstanceMethod.parameters()))
                );
            }
            return;
        }
        if (expressionDef instanceof ExpressionDef.NewInstance newInstance) {
            Type type = TypeUtils.getType(newInstance.type());
            generatorAdapter.newInstance(type);
            generatorAdapter.dup();
            for (ExpressionDef expression : newInstance.values()) {
                pushExpression(generatorAdapter, objectDef, methodDef, expression, expression.type());
            }
            generatorAdapter.invokeConstructor(
                type,
                new Method("<init>", TypeUtils.getConstructorDescriptor(newInstance.values().stream().map(ExpressionDef::type).toList()))
            );
            return;
        }
//        if (expressionDef instanceof TypeDef.Primitive.PrimitiveInstance primitiveInstance) {
//            return renderExpression(objectDef, methodDef, primitiveInstance.value());
//        }
        if (expressionDef instanceof ExpressionDef.NewArrayOfSize newArray) {
            generatorAdapter.push(newArray.size());
            generatorAdapter.newArray(TypeUtils.getType(newArray.type().componentType()));
            return;
        }
        if (expressionDef instanceof ExpressionDef.NewArrayInitialized newArray) {
            List<ExpressionDef> expressions = newArray.expressions();
            generatorAdapter.push(expressions.size());
            TypeDef componentType = newArray.type().componentType();
            Type type = TypeUtils.getType(componentType);
            generatorAdapter.newArray(type);

            if (!expressions.isEmpty()) {
                int index = 0;
                for (ExpressionDef expression : expressions) {
                    generatorAdapter.dup();
                    generatorAdapter.push(index++);
                    pushExpression(generatorAdapter, objectDef, methodDef, expression, componentType);
                    generatorAdapter.arrayStore(type);
                }
            }
            return;
        }
//        if (expressionDef instanceof ExpressionDef.Convert convertExpressionDef) {
//            return renderExpression(objectDef, methodDef, convertExpressionDef.expressionDef());
//        }
        if (expressionDef instanceof ExpressionDef.Cast castExpressionDef) {
            ExpressionDef exp = castExpressionDef.expressionDef();
            pushExpression(generatorAdapter, objectDef, methodDef, exp, exp.type());
            generatorAdapter.checkCast(TypeUtils.getType(castExpressionDef.type()));
            return;
        }
        if (expressionDef instanceof ExpressionDef.Constant constant) {
            pushConstant(generatorAdapter, constant, expectedType);
            return;
        }
        if (expressionDef instanceof ExpressionDef.CallStaticMethod staticMethod) {
            for (ExpressionDef parameter : staticMethod.parameters()) {
                pushExpression(generatorAdapter, objectDef, methodDef, parameter, parameter.type());
            }
            generatorAdapter.invokeStatic(
                TypeUtils.getType(staticMethod.classDef()),
                new Method(staticMethod.name(), getMethodDescriptor2(staticMethod.returningType(), staticMethod.parameters()))
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
//                return renderExpression(objectDef, methodDef, getPropertyValue.instance().invoke(methodElement));
//            }
//            if (memberElement instanceof FieldElement fieldElement) {
//                // TODO: support field
//            }
//            throw new IllegalStateException("Unrecognized property read element: " + propertyElement);
//        }
//        if (expressionDef instanceof ExpressionDef.Condition condition) {
//            renderExpression(generatorAdapter, objectDef, methodDef, condition.left());
//            renderExpression(generatorAdapter, objectDef, methodDef, condition.right());
//            return;
//        }
//        if (expressionDef instanceof ExpressionDef.And andExpressionDef) {
//            return CodeBlock.concat(
//                renderCondition(objectDef, methodDef, andExpressionDef.left()),
//                CodeBlock.of(" && "),
//                renderCondition(objectDef, methodDef, andExpressionDef.right())
//            );
//        }
//        if (expressionDef instanceof ExpressionDef.Or orExpressionDef) {
//            return CodeBlock.concat(
//                renderCondition(objectDef, methodDef, orExpressionDef.left()),
//                CodeBlock.of(" || "),
//                renderCondition(objectDef, methodDef, orExpressionDef.right())
//            );
//        }
        if (expressionDef instanceof ExpressionDef.IfElse conditionIfElse) {
            Label elseLabel = new Label();
            renderConditionalExpression(generatorAdapter, objectDef, methodDef, conditionIfElse.condition(), elseLabel);
            Label end = new Label();
            pushExpression(generatorAdapter, objectDef, methodDef, conditionIfElse.expression(), conditionIfElse.type());
            generatorAdapter.goTo(end);
            generatorAdapter.visitLabel(elseLabel);
            pushExpression(generatorAdapter, objectDef, methodDef, conditionIfElse.elseExpression(), conditionIfElse.type());
            generatorAdapter.visitLabel(end);
            return;
        }
        if (expressionDef instanceof ExpressionDef.Switch aSwitch) {
            ExpressionDef expression = aSwitch.expression();
            pushSwitchExpression(generatorAdapter, objectDef, methodDef, expression);
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
                    pushExpression(generatorAdapter, objectDef, methodDef, exp, aSwitch.type());
                    generatorAdapter.goTo(end);
                }

                @Override
                public void generateDefault() {
                    pushExpression(generatorAdapter, objectDef, methodDef, finalDefaultCase, aSwitch.type());
                }
            });
            return;
        }
        if (expressionDef instanceof ExpressionDef.SwitchYieldCase switchYieldCase) {
            pushStatement(generatorAdapter, objectDef, methodDef, switchYieldCase.statement());
            return;
        }
        if (expressionDef instanceof VariableDef variableDef) {
            pushVariable(generatorAdapter, objectDef, methodDef, variableDef);
            return;
        }
//        if (expressionDef instanceof ExpressionDef.InvokeGetClassMethod invokeGetClassMethod) {
//            return renderExpression(objectDef, methodDef, invokeGetClassMethod.instance().invoke("getClass", TypeDef.of(Class.class)));
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
//            return renderExpression(objectDef, methodDef, instance.isNull().asConditionIfElse(
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
//                .add(renderExpression(objectDef, methodDef, equalsReferentially.instance()))
//                .add(" == ")
//                .add(renderExpression(objectDef, methodDef, equalsReferentially.other()))
//                .build();
//        }
        throw new IllegalStateException("Unrecognized expression: " + expressionDef);
    }

    private TypeDef getInstanceType(ObjectDef objectDef, TypeDef instanceType) {
        if (instanceType == TypeDef.THIS) {
            return objectDef.asTypeDef();
        }
        if (instanceType == TypeDef.SUPER) {
            if (objectDef instanceof ClassDef classDef) {
                if (classDef.getSuperclass() == null) {
                    throw new IllegalStateException("Super class is missing for class def: " + classDef);
                }
                return classDef.getSuperclass();
            }
            if (objectDef instanceof EnumDef) {
                return ClassTypeDef.of(Enum.class);
            }
            if (objectDef instanceof InterfaceDef interfaceDef) {
                throw new IllegalStateException("Super class is not supported for interface def: " + interfaceDef);
            }
        }
        return instanceType;
    }

    private void pushSwitchExpression(GeneratorAdapter generatorAdapter, ObjectDef objectDef, MethodDef methodDef, ExpressionDef expression) {
        pushExpression(generatorAdapter, objectDef, methodDef, expression, expression.type());
        if (expression.type() instanceof ClassTypeDef classTypeDef && classTypeDef.getName().equals(String.class.getName())) {
            generatorAdapter.invokeVirtual(
                Type.getType(String.class),
                Method.getMethod(ReflectionUtils.getRequiredMethod(String.class, "hashCode"))
            );
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
                                             ObjectDef objectDef,
                                             MethodDef methodDef,
                                             ExpressionDef expressionDef,
                                             Label elseLabel) {
        if (expressionDef instanceof ExpressionDef.Condition condition) {
            if (condition.operator().trim().equals("==")) {
                generatorAdapter.ifCmp(TypeUtils.getType(condition.type()), GeneratorAdapter.EQ, elseLabel);
            } else if (condition.operator().trim().equals("!=")) {
                generatorAdapter.ifCmp(TypeUtils.getType(condition.type()), GeneratorAdapter.NE, elseLabel);
            } else {
                throw new IllegalStateException("Unrecognized condition operator: " + condition.operator());
            }
            return;
        }
        if (expressionDef instanceof ExpressionDef.IsNull isNull) {
            pushExpression(generatorAdapter, objectDef, methodDef, isNull.expression(), TypeDef.Primitive.BOOLEAN);
            generatorAdapter.ifNonNull(elseLabel);
            return;
        }
        if (expressionDef instanceof ExpressionDef.IsNotNull isNotNull) {
            pushExpression(generatorAdapter, objectDef, methodDef, isNotNull.expression(), TypeDef.Primitive.BOOLEAN);
            generatorAdapter.ifNull(elseLabel);
            return;
        }
        if (expressionDef instanceof ExpressionDef.Constant constant) {
            // TODO: allow only boolean
            pushConstant(generatorAdapter, constant, TypeDef.Primitive.BOOLEAN);
            generatorAdapter.push(true);
            generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, elseLabel);
            return;
        }
        throw new IllegalStateException("Unrecognized conditional expression: " + expressionDef);
    }

    private void pushVariable(GeneratorAdapter generatorAdapter, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, VariableDef variableDef) {
//        if (variableDef instanceof VariableDef.Local localVariableDef) {
//            return CodeBlock.of(localVariableDef.name());
//        }
        if (variableDef instanceof VariableDef.MethodParameter parameterVariableDef) {
            if (methodDef == null) {
                throw new IllegalStateException("Accessing method parameters is not available");
            }
            ParameterDef parameterDef = methodDef.getParameters().stream().filter(p -> p.getName().equals(parameterVariableDef.name())).findFirst().orElseThrow();
            int parameterIndex = methodDef.getParameters().indexOf(parameterDef);
            generatorAdapter.loadArg(parameterIndex);
            return;
        }
//        if (variableDef instanceof VariableDef.StaticField staticField) {
//            return CodeBlock.of("$T.$L", asType(staticField.ownerType(), objectDef), staticField.name());
//        }
//        if (variableDef instanceof VariableDef.Field field) {
//            if (objectDef == null) {
//                throw new IllegalStateException("Accessing 'this' is not available");
//            }
//            if (objectDef instanceof ClassDef classDef) {
//                if (!classDef.hasField(field.name())) {
//                    throw new IllegalStateException("Field " + field.name() + " is not available in [" + classDef + "]:" + classDef.getFields());
//                }
//            } else {
//                throw new IllegalStateException("Field access no supported on the object definition: " + objectDef);
//            }
//            return CodeBlock.of(renderExpression(objectDef, methodDef, field.instance()) + "." + field.name());
//        }
        if (variableDef instanceof VariableDef.This) {
            if (objectDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            generatorAdapter.loadThis();
            return;
        }
        if (variableDef instanceof VariableDef.Super) {
            if (objectDef == null) {
                throw new IllegalStateException("Accessing 'super' is not available");
            }
            generatorAdapter.loadThis();
            return;
        }
        throw new IllegalStateException("Unrecognized variable: " + variableDef);
    }

    private void pushConstant(GeneratorAdapter generatorAdapter,
                              ExpressionDef.Constant constant,
                              TypeDef expectedType) {
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
            if (!expectedType.isPrimitive()) {
                generatorAdapter.box(TypeUtils.getType(primitive));
            }
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
            System.out.println("XXX " + aClass);
            System.out.println("XXX2 " + Type.getObjectType(aClass.getName().replace('.', '/')));
            generatorAdapter.push(Type.getObjectType(aClass.getName().replace('.', '/')));
            return;
        }
        if (value instanceof Integer integer) {
            generatorAdapter.push(integer);
            if (!expectedType.isPrimitive()) {
                generatorAdapter.box(Type.getType(int.class));
            }
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param returnType    The return type
     * @param argumentTypes The argument types
     * @return The method descriptor
     */
    private static String getMethodDescriptor(TypeDef returnType, Collection<ParameterDef> argumentTypes) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');

        for (ParameterDef argumentType : argumentTypes) {
            builder.append(TypeUtils.getType(argumentType.getType()));
        }

        builder.append(')');

        builder.append(TypeUtils.getType(Objects.requireNonNullElse(returnType, TypeDef.VOID)));
        return builder.toString();
    }

    /**
     * @param returnType    The return type
     * @param argumentTypes The argument types
     * @return The method descriptor
     */
    private static String getMethodDescriptor2(TypeDef returnType, Collection<ExpressionDef> argumentTypes) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');

        for (ExpressionDef argumentType : argumentTypes) {
            builder.append(TypeUtils.getType(argumentType.type()));
        }

        builder.append(')');

        builder.append(TypeUtils.getType(Objects.requireNonNullElse(returnType, TypeDef.VOID)));
        return builder.toString();
    }

}
