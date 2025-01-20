package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.EnumConstantElement;
import io.micronaut.inject.ast.EnumElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.visitors.PluginUtils.ParameterConfig;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.EnumDef.EnumDefBuilder;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * A utility class for working with complex types, like enums and POJOs.
 */
@Internal
public class ModelUtils {

    /**
     * Copy an existing enum to the plugin generated sources.
     *
     * @param context The visitor context
     * @param packageName The package name to copy to
     * @param element The
     * @return The copied enum
     */
    public static EnumDef copyEnum(VisitorContext context, String packageName, ClassElement element) {
        EnumDefBuilder enumDefBuilder = EnumDef.builder(packageName + "."
                + element.getSimpleName().replaceAll(".+\\$", ""))
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc(JavadocUtils.getTaskJavadoc(context, element).javadoc().orElse(element.getName() + " enum."));
        if (element instanceof EnumElement enumElement) {
            for (EnumConstantElement constant: enumElement.elements()) {
                enumDefBuilder.addEnumConstant(constant.getName());
            }
        }
        return enumDefBuilder.build();
    }

    /**
     * Converts a parameter value if required.
     * Conversion is required if the value is a model, so a new type was generated for it
     * instead of the original one.
     *
     * @param parameter The parameter config
     * @param statements The modifiable statements to which a local variable may be added if needed
     * @param paramExpression The current expression for param
     * @return The new expression for param
     */
    public static ExpressionDef convertParameterIfRequired(
            ParameterConfig parameter, List<StatementDef> statements, ExpressionDef paramExpression
    ) {
        if (parameter.source().getType().isEnum()) {
            ClassTypeDef requiredType = ClassTypeDef.of(parameter.source().getType());
            VariableDef.Local param = new VariableDef.Local(parameter.source().getName() + "Param", requiredType);
            statements.add(param.defineAndAssign(paramExpression.ifNull(ExpressionDef.constant(null), requiredType
                .invokeStatic("valueOf", requiredType, paramExpression.invoke("name", TypeDef.STRING)))));
            return param;
        }
        return paramExpression;
    }

}
