package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class TestRecordGenerator implements TypeElementVisitor<TestAnn, Object> {
    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        List<MethodElement> methods = element.getEnclosedElements(ElementQuery.ALL_METHODS
            .onlyInstance()
            .filter(m -> m.getParameters().length == 0 && !m.getReturnType().isVoid()));

        String className = element.getName() + "Record";
        RecordDef.RecordDefBuilder builder = RecordDef.builder(className)
            .addAnnotation(Introspected.class);
        for (MethodElement method : methods) {
            builder.addProperty(PropertyDef.builder(method.getName()).ofType(TypeDef.of(method.getGenericReturnType())).build());
        }

        ClassTypeDef fieldType = TypeDef.parameterized(Set.class, String.class);
        builder.addProperty(PropertyDef.builder("explicitlySet").ofType(fieldType).build());

        Optional<SourceGenerator> byLanguage = SourceGenerators.findByLanguage(context.getLanguage());
        RecordDef recordDef = builder.build();
        List<PropertyElement> beanProperties = recordDef.getBeanProperties(context);
        List<ParameterElement> constructorParameters = recordDef.getConstructorParameters(context);
        ClassDef.ClassDefBuilder builderDef = BuilderAnnotationVisitor.createBuilder(
            element.getPackageName(),
            recordDef.asTypeDef(),
            null,
            beanProperties,
            constructorParameters,
            (buildContext) -> {
                if (buildContext.field().getName().equals("explicitlySet")) {
                    return buildContext.aThis().returning();
                } else {
                    VariableDef.Field explicitlySet = buildContext.aThis().field("explicitlySet", fieldType);
                    ExpressionDef.InvokeStaticMethod newSet = ClassTypeDef.of(CollectionUtils.class)
                        .invokeStatic("newHashSet", fieldType, ExpressionDef.constant(beanProperties.size()));
                    return StatementDef.multi(
                        explicitlySet.isNull().ifTrue(explicitlySet.assign(newSet)),
                        explicitlySet.invoke("add", TypeDef.Primitive.BOOLEAN, ExpressionDef.constant(buildContext.field().getName())),
                        buildContext.aThis().returning()
                    );
                }
            }
        );

        WitherGenerator.weaveWithMethods(
            recordDef.asTypeDef(),
            builder, beanProperties,
            constructorParameters,
            true
        );

        ClassDef builderType = builderDef.build();
        ClassTypeDef builderTypeDef = builderType.asTypeDef();

        builder.addMethod(MethodDef.builder("builder").returns(
            builderTypeDef
        ).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .build((aThis, parameters) -> builderTypeDef.invokeStatic("builder", builderTypeDef).returning()));

        byLanguage.ifPresent(generator -> {
            generator.write(builderType, context, element);
            generator.write(builder.build(), context, element);
        });
    }
}
