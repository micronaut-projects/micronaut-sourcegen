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

import io.micronaut.core.annotation.Experimental;
import org.jspecify.annotations.Nullable;
import io.micronaut.sourcegen.model.ClassTypeDef.ClassName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * The definition for an annotation class.
 *
 * @author Andriy Dmyturk
 * @since 1.7
 */
@Experimental
public final class AnnotationObjectDef extends ObjectDef {

    private final List<AnnotationMemberDef> members;
    private final List<FieldDef> fields;

    private AnnotationObjectDef(
        List<AnnotationMemberDef> members,
        List<FieldDef> fields,
        ClassName className,
        EnumSet<Modifier> modifiers,
        List<AnnotationDef> annotations,
        List<String> javadoc,
        List<ObjectDef> innerTypes
    ) {
        super(className, modifiers, annotations, javadoc, Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), innerTypes, false);
        this.fields = fields;
        this.members = members;
    }

    @Override
    public ObjectDef withClassName(ClassName className) {
        return new AnnotationObjectDef(members, fields, className, modifiers, annotations, javadoc, innerTypes);
    }

    /**
     * Get fields.
     * @return The fields
     */
    public List<FieldDef> getFields() {
        return fields;
    }

    /**
     * Get the members of this annotation definition.
     * @return The members
     */
    public List<AnnotationMemberDef> getMembers() {
        return members;
    }

    /**
     * Create the builder.
     * @param name The annotation classname
     * @return The builder
     */
    public static AnnotationObjectDefBuilder builder(String name) {
        return new AnnotationObjectDefBuilder(name);
    }

    /**
     * The builder for annotation object.
     */
    @Experimental
    public static final class AnnotationObjectDefBuilder extends AbstractElementBuilder<AnnotationObjectDefBuilder> {

        private final List<AnnotationMemberDef> members = new ArrayList<>();
        private final List<FieldDef> fields = new ArrayList<>();
        private final List<ObjectDef> innerTypes = new ArrayList<>();

        private AnnotationObjectDefBuilder(String className) {
            super(className, AnnotationObjectDefBuilder.class);
        }

        /**
         * Add an inner type.
         *
         * @param innerDef The inner definition.
         * @return The builder
         */
        public AnnotationObjectDefBuilder addInnerType(ObjectDef innerDef) {
            ClassTypeDef innerType = innerDef.asTypeDef();
            String newName = ClassTypeDef.of(name).getCanonicalName() + "$" + innerType.getSimpleName();
            innerTypes.add(innerDef.withClassName(new ClassTypeDef.ClassName(newName, true)));
            return this;
        }

        /**
         * Add a member to this annotation definition.
         * @param member The member
         * @return The builder
         */
        public AnnotationObjectDefBuilder addMember(AnnotationMemberDef member) {
            members.add(member);
            return this;
        }

        /**
         * Add a field to this annotation definition. The field must be static and final.
         * @param field The field.
         * @return The builder
         */
        public AnnotationObjectDefBuilder addField(FieldDef field) {
            fields.add(field);
            return this;
        }

        /**
         * Build the {@link AnnotationObjectDef} instance.
         * @return The instance
         */
        public AnnotationObjectDef build() {
            return new AnnotationObjectDef(members, fields, new ClassTypeDef.ClassName(name),
                modifiers, annotations, javadoc, innerTypes
            );
        }

    }

    /**
     * A definition for annotation member (i.e. property).
     *
     * @author Andriy Dmytruk
     * @since 1.7
     */
    public static final class AnnotationMemberDef extends AbstractElement {

        private final TypeDef type;
        private final @Nullable ExpressionDef defaultValue;
        private final @Nullable AnnotationDef defaultAnnotationValue;

        private AnnotationMemberDef(TypeDef type, @Nullable ExpressionDef defaultValue, @Nullable AnnotationDef defaultAnnotationValue,
            String name, EnumSet<Modifier> modifiers, List<AnnotationDef> annotations, List<String> javadoc, boolean synthetic
        ) {
            super(name, modifiers, annotations, javadoc, synthetic);
            this.type = type;
            this.defaultAnnotationValue = defaultAnnotationValue;
            this.defaultValue = defaultValue;
        }

        /**
         * Create a builder.
         * @param memberName The name of the member
         * @param type The member type
         * @return The builder
         */
        public static AnnotationMemberDefBuilder builder(String memberName, TypeDef type) {
            return new AnnotationMemberDefBuilder(memberName, type);
        }

        /**
         * Get the type of the member.
         * @return the type
         */
        public TypeDef getType() {
            return type;
        }

        /**
         * Get the default value of the member.
         * @return the default value
         */
        public ExpressionDef getDefaultValue() {
            return defaultValue;
        }

        /**
         * Get the default value of the member when .
         * @return the default value
         */
        public AnnotationDef getAnnotationDefaultValue() {
            return defaultAnnotationValue;
        }
    }

    /**
     * Builder for {@link AnnotationMemberDef}.
     */
    public static final class AnnotationMemberDefBuilder extends AbstractElementBuilder<AnnotationMemberDefBuilder> {

        private final TypeDef type;
        @Nullable
        private ExpressionDef defaultValue;
        @Nullable
        private AnnotationDef defaultAnnotationValue;

        private AnnotationMemberDefBuilder(String name, TypeDef type) {
            super(name, AnnotationMemberDefBuilder.class);
            addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
            this.type = type;
        }

        /**
         * Set the default value for this annotation member.
         * @param defaultValue The default value
         * @return This builder
         */
        public AnnotationMemberDefBuilder withDefault(ExpressionDef defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        /**
         * Set the default value for a nested annotation.
         * The default is an annotation definition.
         * @param defaultValue The default annotation definition
         * @return This builder
         */
        public AnnotationMemberDefBuilder withDefault(AnnotationDef defaultValue) {
            this.defaultAnnotationValue = defaultValue;
            return this;
        }

        /**
         * Create the annotation member definition instance.
         * @return The instance
         */
        public AnnotationMemberDef build() {
            return new AnnotationMemberDef(type, defaultValue, defaultAnnotationValue, name, modifiers, annotations, javadoc, synthetic);
        }

    }

}
