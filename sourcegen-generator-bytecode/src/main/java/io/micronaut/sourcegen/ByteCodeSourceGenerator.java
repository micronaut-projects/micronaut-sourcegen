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
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
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

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
        Type enumType = TypeUtils.getType(enumDef.asTypeDef());
        classWriter.visit(V17, ACC_ENUM | getModifiersFlag(enumDef.getModifiers()), enumType.getInternalName(), null, null, null);
        for (String enumConstant : enumDef.getEnumConstants()) {
            FieldVisitor fieldVisitor = classWriter.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC + ACC_ENUM, enumConstant, enumType.getDescriptor(), null, null);
            fieldVisitor.visitEnd();
        }
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
        String methodDescriptor = getMethodDescriptor(methodDef.getReturnType(), methodDef.getParameters());

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
                renderStatementCodeBlock(generatorAdapter, objectDef, methodDef, statement);
            }
            methodVisitor.visitMaxs(5, 5);
        }
        methodVisitor.visitEnd();
    }

    private void renderStatementCodeBlock(GeneratorAdapter generatorAdapter,
                                          @Nullable ObjectDef objectDef,
                                          MethodDef methodDef,
                                          StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Multi statements) {
            for (StatementDef statement : statements.statements()) {
                renderStatementCodeBlock(generatorAdapter, objectDef, methodDef, statement);
            }
            return;
        }
        if (statementDef instanceof StatementDef.If ifStatement) {
            Label elseLabel = new Label();
            renderConditionalExpression(generatorAdapter, objectDef, methodDef, ifStatement.condition(), elseLabel);
            renderStatementCodeBlock(generatorAdapter, objectDef, methodDef, ifStatement.statement());
            generatorAdapter.visitLabel(elseLabel);
            return;
        }
        if (statementDef instanceof StatementDef.IfElse ifStatement) {
            Label elseLabel = new Label();
            renderConditionalExpression(generatorAdapter, objectDef, methodDef, ifStatement.condition(), elseLabel);
            Label end = new Label();
            renderStatementCodeBlock(generatorAdapter, objectDef, methodDef, ifStatement.statement());
            generatorAdapter.visitLabel(end);
            generatorAdapter.visitLabel(elseLabel);
            renderStatementCodeBlock(generatorAdapter, objectDef, methodDef, ifStatement.elseStatement());
            return;
        }
        if (statementDef instanceof StatementDef.Switch aSwitch) {
//            CodeBlock.Builder builder = CodeBlock.builder();
//            builder.add("switch (");
//            builder.add(renderExpression(objectDef, methodDef, aSwitch.expression()));
//            builder.add(") {\n");
//            builder.indent();
//            for (Map.Entry<ExpressionDef.Constant, StatementDef> e : aSwitch.cases().entrySet()) {
//                if (e.getKey() == null || e.getKey().value() == null) {
//                    builder.add("default");
//                } else {
//                    builder.add("case ");
//                    builder.add(renderConstantExpression(e.getKey()));
//                }
//                builder.add(": {\n");
//                builder.indent();
//                StatementDef statement = e.getValue();
//                builder.add(renderStatementCodeBlock(objectDef, methodDef, statement));
//                builder.unindent();
//                builder.add("}\n");
//            }
//            builder.unindent();
//            builder.add("}\n");
//            return builder.build();
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
            pushExpression(generatorAdapter, objectDef, methodDef, aReturn.expression());
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
//        if (statementDef instanceof ExpressionDef expressionDef) {
//            return renderExpression(objectDef, methodDef, expressionDef);
//        }
        throw new IllegalStateException("Unrecognized statement: " + statementDef);
    }

    private void pushExpression(GeneratorAdapter generatorAdapter,
                                @Nullable ObjectDef objectDef,
                                MethodDef methodDef,
                                ExpressionDef expressionDef) {
//        if (expressionDef instanceof ExpressionDef.NewInstance newInstance) {
//            return CodeBlock.concat(
//                CodeBlock.of("new $L(", asType(newInstance.type(), objectDef)),
//                newInstance.values()
//                    .stream()
//                    .map(exp -> renderExpression(objectDef, methodDef, exp))
//                    .collect(CodeBlock.joining(", ")),
//                CodeBlock.of(")")
//            );
//        }
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
            Type type = TypeUtils.getType(newArray.type().componentType());
            generatorAdapter.newArray(type);

            int index = 0;
            if (!expressions.isEmpty()) {
                for (ExpressionDef expression : expressions) {
                    generatorAdapter.dup();
                    generatorAdapter.push(index);
                    pushExpression(generatorAdapter, objectDef, methodDef, expression);
                    generatorAdapter.arrayStore(type);
                    index++;
                }
            }
            return;
        }
//        if (expressionDef instanceof ExpressionDef.Convert convertExpressionDef) {
//            return renderExpression(objectDef, methodDef, convertExpressionDef.expressionDef());
//        }
//        if (expressionDef instanceof ExpressionDef.Cast castExpressionDef) {
//            if (castExpressionDef.expressionDef() instanceof VariableDef variableDef) {
//                return CodeBlock.concat(
//                    CodeBlock.of("($T) ", asType(castExpressionDef.type(), objectDef)),
//                    renderExpression(objectDef, methodDef, variableDef)
//                );
//            }
//            return CodeBlock.concat(
//                CodeBlock.of("($T) (", asType(castExpressionDef.type(), objectDef)),
//                renderExpression(objectDef, methodDef, castExpressionDef.expressionDef()),
//                CodeBlock.of(")")
//            );
//        }
        if (expressionDef instanceof ExpressionDef.Constant constant) {
            renderConstantExpression(generatorAdapter, constant);
            return;
        }
//        if (expressionDef instanceof ExpressionDef.CallInstanceMethod callInstanceMethod) {
//            return CodeBlock.concat(
//                CodeBlock.of(renderExpression(objectDef, methodDef, callInstanceMethod.instance())
//                    + "." + callInstanceMethod.name()
//                    + "("),
//                callInstanceMethod.parameters()
//                    .stream()
//                    .map(exp -> renderExpression(objectDef, methodDef, exp))
//                    .collect(CodeBlock.joining(", ")),
//                CodeBlock.of(")")
//            );
//        }
//        if (expressionDef instanceof ExpressionDef.CallStaticMethod staticMethod) {
//            return CodeBlock.concat(
//                CodeBlock.of("$T." + staticMethod.name() + "(", asType(staticMethod.classDef(), objectDef)),
//                staticMethod.parameters()
//                    .stream()
//                    .map(exp -> renderExpression(objectDef, methodDef, exp))
//                    .collect(CodeBlock.joining(", ")),
//                CodeBlock.of(")")
//            );
//        }
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
            pushExpression(generatorAdapter, objectDef, methodDef, conditionIfElse.expression());
            generatorAdapter.goTo(end);
            generatorAdapter.visitLabel(elseLabel);
            pushExpression(generatorAdapter, objectDef, methodDef, conditionIfElse.elseExpression());
            generatorAdapter.visitLabel(end);
            return;
        }
//        if (expressionDef instanceof ExpressionDef.Switch aSwitch) {
//            CodeBlock.Builder builder = CodeBlock.builder();
//            builder.add("switch (");
//            builder.add(renderExpression(objectDef, methodDef, aSwitch.expression()));
//            builder.add(") {\n");
//            builder.indent();
//            for (Map.Entry<ExpressionDef.Constant, ExpressionDef> e : aSwitch.cases().entrySet()) {
//                if (e.getKey() == null || e.getKey().value() == null) {
//                    builder.add("default");
//                } else {
//                    builder.add("case ");
//                    builder.add(renderConstantExpression(e.getKey()));
//                }
//                builder.add(" -> ");
//                ExpressionDef value = e.getValue();
//                builder.add(renderExpression(objectDef, methodDef, value));
//                if (value instanceof ExpressionDef.SwitchYieldCase) {
//                    builder.add("\n");
//                } else {
//                    builder.add(";\n");
//                }
//            }
//            builder.unindent();
//            builder.add("}");
//            return builder.build();
//        }
//        if (expressionDef instanceof ExpressionDef.SwitchYieldCase switchYieldCase) {
//            CodeBlock.Builder builder = CodeBlock.builder();
//            builder.add("{\n");
//            builder.indent();
//            StatementDef statement = switchYieldCase.statement();
//            List<StatementDef> flatten = statement.flatten();
//            if (flatten.isEmpty()) {
//                throw new IllegalStateException("SwitchYieldCase did not return any statements");
//            }
//            StatementDef last = flatten.get(flatten.size() - 1);
//            List<StatementDef> rest = flatten.subList(0, flatten.size() - 1);
//            for (StatementDef statementDef : rest) {
//                builder.add(renderStatementCodeBlock(objectDef, methodDef, statementDef));
//            }
//            renderYield(builder, methodDef, last, objectDef);
//            builder.unindent();
//            builder.add("}");
//            String str = builder.build().toString();
//            // Render the body to prevent nested statements
//            return CodeBlock.of(str);
//        }
        if (expressionDef instanceof VariableDef variableDef) {
            renderVariable(generatorAdapter, objectDef, methodDef, variableDef);
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
            pushExpression(generatorAdapter, objectDef, methodDef, isNull.expression());
            generatorAdapter.ifNonNull(elseLabel);
            return;
        }
        if (expressionDef instanceof ExpressionDef.IsNotNull isNotNull) {
            pushExpression(generatorAdapter, objectDef, methodDef, isNotNull.expression());
            generatorAdapter.ifNull(elseLabel);
            return;
        }
        if (expressionDef instanceof ExpressionDef.Constant constant) {
            // TODO: allow only boolean
            renderConstantExpression(generatorAdapter, constant);
            generatorAdapter.push(true);
            generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, elseLabel);
            return;
        }
        throw new IllegalStateException("Unrecognized conditional expression: " + expressionDef);
    }

    private void renderVariable(GeneratorAdapter generatorAdapter, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, VariableDef variableDef) {
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
//        if (variableDef instanceof VariableDef.This) {
//            if (objectDef == null) {
//                throw new IllegalStateException("Accessing 'this' is not available");
//            }
//            return CodeBlock.of("this");
//        }
        throw new IllegalStateException("Unrecognized variable: " + variableDef);
    }

    private void renderConstantExpression(GeneratorAdapter generatorAdapter, ExpressionDef.Constant constant) {
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
            return;
        }
        if (type instanceof ClassTypeDef classTypeDef) {
            if (classTypeDef.getName().equals(String.class.getName())) {
                generatorAdapter.push((String) value);
                return;
            }
        }
        if (type instanceof ClassTypeDef.JavaClass javaClass) {
            Class<?> clazz = javaClass.type();
            generatorAdapter.push(Type.getObjectType(clazz.getName().replace('.', '/')));
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

        builder.append(TypeUtils.getType(returnType));
        return builder.toString();
    }

}
