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
import io.micronaut.sourcegen.model.AnnotationObjectDef.AnnotationMemberDefBuilder;
import io.micronaut.sourcegen.model.AnnotationObjectDef.AnnotationObjectDefBuilder;
import io.micronaut.sourcegen.model.FieldDef.FieldDefBuilder;
import io.micronaut.sourcegen.model.MethodDef.MethodDefBuilder;
import io.micronaut.sourcegen.model.ParameterDef.ParameterDefBuilder;
import io.micronaut.sourcegen.model.PropertyDef.PropertyDefBuilder;

import javax.lang.model.element.Modifier;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * The abstract element builder.
 *
 * @param <ThisType> The type of this builder
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed class AbstractElementBuilder<ThisType> permits AnnotationMemberDefBuilder, AnnotationObjectDefBuilder, FieldDefBuilder, MethodDefBuilder, ObjectDefBuilder, ParameterDefBuilder, PropertyDefBuilder {

    protected String name;
    protected final EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
    protected final List<AnnotationDef> annotations = new ArrayList<>();
    protected final List<String> javadoc = new ArrayList<>();
    protected final ThisType thisInstance;
    protected boolean synthetic;

    protected AbstractElementBuilder(String name) {
        this.name = name;
        this.thisInstance = (ThisType) this;
    }

    /**
     * Modifies the name.
     *
     * @param name The name
     * @return The builder
     * @since 2.0
     */
    public final ThisType name(String name) {
        this.name = name;
        return thisInstance;
    }

    /**
     * Marks the element as synthetic.
     *
     * @return The builder
     */
    public final ThisType synthetic() {
        synthetic = true;
        return thisInstance;
    }

    /**
     * Marks the element as synthetic.
     *
     * @param synthetic Is synthetic
     * @return The builder
     */
    public final ThisType synthetic(boolean synthetic) {
        this.synthetic = synthetic;
        return thisInstance;
    }

    public final ThisType addModifiers(Collection<Modifier> modifiers) {
        this.modifiers.addAll(modifiers);
        return thisInstance;
    }

    public final ThisType addModifiers(Modifier... modifiers) {
        Collections.addAll(this.modifiers, modifiers);
        return thisInstance;
    }

    /**
     * Overrides the modifiers.
     *
     * @param modifiers The modifier
     * @return this type
     */
    public final ThisType overrideModifiers(Modifier... modifiers) {
        this.modifiers.clear();
        Collections.addAll(this.modifiers, modifiers);
        return thisInstance;
    }

    public final ThisType addAnnotation(String annotationName) {
        return addAnnotation(ClassTypeDef.of(annotationName));
    }

    public final ThisType addAnnotation(Class<? extends Annotation> annotationType) {
        return addAnnotation(ClassTypeDef.of(annotationType));
    }

    public final ThisType addAnnotation(ClassTypeDef typeDef) {
        return addAnnotation(AnnotationDef.builder(typeDef).build());
    }

    public final ThisType addAnnotation(AnnotationDef annotationDef) {
        annotations.add(annotationDef);
        return thisInstance;
    }

    public final ThisType addAnnotations(AnnotationDef... annotationDefs) {
        return addAnnotations(Arrays.asList(annotationDefs));
    }

    public final ThisType addAnnotations(List<AnnotationDef> annotationDefs) {
        annotationDefs.forEach(this::addAnnotation);
        return thisInstance;
    }

    public final ThisType addJavadoc(String doc) {
        javadoc.add(doc);
        return thisInstance;
    }

    /**
     * Adds javadoc.
     * @param doc The javadoc
     * @return The builder
     */
    public final ThisType addJavadoc(List<String> doc) {
        javadoc.addAll(doc);
        return thisInstance;
    }

}
