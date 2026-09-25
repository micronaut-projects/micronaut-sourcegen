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
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import javax.lang.model.element.Modifier;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Maps Java model modifiers to JVMS access flags without depending on a bytecode library.
 *
 * <p>The integer values are the class-file values defined by the JVMS and are understood by both
 * the ASM and JDK ClassFile backends.</p>
 *
 * @since 2.2
 */
@Internal
public final class ModifierUtils {

    public static final int ACC_PUBLIC = 0x0001;
    public static final int ACC_PRIVATE = 0x0002;
    public static final int ACC_PROTECTED = 0x0004;
    public static final int ACC_STATIC = 0x0008;
    public static final int ACC_FINAL = 0x0010;
    public static final int ACC_SUPER = 0x0020;
    public static final int ACC_SYNCHRONIZED = 0x0020;
    public static final int ACC_VOLATILE = 0x0040;
    public static final int ACC_BRIDGE = 0x0040;
    public static final int ACC_TRANSIENT = 0x0080;
    public static final int ACC_VARARGS = 0x0080;
    public static final int ACC_NATIVE = 0x0100;
    public static final int ACC_INTERFACE = 0x0200;
    public static final int ACC_ABSTRACT = 0x0400;
    public static final int ACC_STRICT = 0x0800;
    public static final int ACC_SYNTHETIC = 0x1000;
    public static final int ACC_ANNOTATION = 0x2000;
    public static final int ACC_ENUM = 0x4000;

    private ModifierUtils() {
    }

    /**
     * Maps member modifiers.
     *
     * @param modifiers Java modifiers
     * @return JVMS flags
     */
    public static int memberFlags(Set<Modifier> modifiers) {
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
        if (modifiers.contains(Modifier.SYNCHRONIZED)) {
            access |= ACC_SYNCHRONIZED;
        }
        if (modifiers.contains(Modifier.NATIVE)) {
            access |= ACC_NATIVE;
        }
        if (modifiers.contains(Modifier.STRICTFP)) {
            access |= ACC_STRICT;
        }
        if (modifiers.contains(Modifier.TRANSIENT)) {
            access |= ACC_TRANSIENT;
        }
        if (modifiers.contains(Modifier.VOLATILE)) {
            access |= ACC_VOLATILE;
        }
        return access;
    }

    /**
     * Maps an object definition to its class-file flags.
     *
     * @param objectDef The definition
     * @return JVMS flags
     */
    public static int objectFlags(ObjectDef objectDef) {
        if (objectDef instanceof EnumDef enumDef) {
            return ACC_ENUM | memberFlags(enumDef.getModifiers()) | ACC_FINAL;
        }
        if (objectDef instanceof InterfaceDef interfaceDef) {
            return ACC_INTERFACE | ACC_ABSTRACT | memberFlags(interfaceDef.getModifiers());
        }
        if (objectDef instanceof RecordDef recordDef) {
            // The JVMS has no record access flag; the Record attribute marks a record class.
            return ACC_FINAL | memberFlags(recordDef.getModifiers());
        }
        if (objectDef instanceof AnnotationObjectDef annotationObjectDef) {
            return ACC_ANNOTATION | ACC_INTERFACE | ACC_ABSTRACT | memberFlags(annotationObjectDef.getModifiers());
        }
        return memberFlags(objectDef.getModifiers());
    }

    /**
     * Maps the class-file flags of a class being emitted. Visibility and static are represented by
     * the enclosing {@code InnerClasses} entry instead of the class-file access field.
     *
     * @param modifiers Declared modifiers
     * @param outerType Enclosing type, or {@code null}
     * @return JVMS class flags
     */
    public static int classFlags(Set<Modifier> modifiers, @Nullable ClassTypeDef outerType) {
        int access = memberFlags(modifiers) & ~(ACC_PRIVATE | ACC_PROTECTED | ACC_STATIC);
        boolean implicitlyPublic = outerType != null
            && outerType.isInterface()
            && (access & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE)) == 0;
        if (modifiers.contains(Modifier.PROTECTED) || implicitlyPublic) {
            access |= ACC_PUBLIC;
        }
        return access;
    }

    /**
     * Maps the flags carried by an {@code InnerClasses} entry.
     *
     * @param objectDef The nested definition
     * @param declaredInterface Whether the enclosing type is an interface
     * @return JVMS flags
     */
    public static int innerClassFlags(ObjectDef objectDef, boolean declaredInterface) {
        int access = objectFlags(objectDef) | ACC_STATIC;
        if (declaredInterface && (access & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE)) == 0) {
            access |= ACC_PUBLIC;
        }
        return access;
    }

    /**
     * Maps the access modifiers alone - {@code public}, {@code protected} and {@code private} - as a member that
     * takes the access of its definition, the implicit canonical constructor of a record, is written.
     *
     * @param modifiers Java modifiers
     * @return JVMS access flags
     * @since 2.3
     */
    public static int accessFlags(Set<Modifier> modifiers) {
        return memberFlags(modifiers) & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE);
    }

    /**
     * Maps the flags of a method: its modifiers, and {@code ACC_SYNTHETIC} for a synthetic one.
     *
     * @param method The method
     * @return JVMS flags
     * @since 2.3
     */
    public static int methodFlags(MethodDef method) {
        int flags = memberFlags(method.getModifiers());
        if (method.isSynthetic()) {
            flags |= ACC_SYNTHETIC;
        }
        return flags;
    }

    /**
     * Maps the flags of a field: its modifiers, {@code ACC_SYNTHETIC} for a synthetic one and {@code ACC_ENUM} for
     * the constant of an enum.
     *
     * @param objectDef The definition declaring the field, in its lowered class form for an enum
     * @param field     The field
     * @return JVMS flags
     * @since 2.3
     */
    public static int fieldFlags(ObjectDef objectDef, FieldDef field) {
        int flags = memberFlags(field.getModifiers());
        if (field.isSynthetic()) {
            flags |= ACC_SYNTHETIC;
        }
        if (EnumGenUtils.isEnumField(objectDef, field)) {
            flags |= ACC_ENUM;
        }
        return flags;
    }

    /**
     * Maps the flags of a method parameter, as the {@code MethodParameters} attribute carries them.
     *
     * @param parameter The parameter
     * @return JVMS flags
     * @since 2.3
     */
    public static int parameterFlags(ParameterDef parameter) {
        int flags = parameter.getModifiers().contains(Modifier.FINAL) ? ACC_FINAL : 0;
        if (parameter.isSynthetic()) {
            flags |= ACC_SYNTHETIC;
        }
        return flags;
    }

    /**
     * Maps the flags of the class file of a definition: the kind of type it is, its {@link #classFlags class flags},
     * {@code ACC_SYNTHETIC} for a synthetic one and {@code ACC_ENUM} for the lowered class form of an enum. A record
     * is final; the {@code Record} attribute, not a flag, marks it as a record.
     *
     * @param objectDef The definition, in its lowered class form for an enum
     * @param outerType The enclosing type, or {@code null}
     * @return JVMS class flags
     * @since 2.3
     */
    public static int classFileFlags(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        if (objectDef instanceof EnumDef enumDef) {
            return classFileFlags(EnumGenUtils.toClassDef(enumDef), outerType);
        }
        int flags = classFlags(objectDef.getModifiers(), outerType);
        if (objectDef instanceof InterfaceDef) {
            flags |= ACC_INTERFACE | ACC_ABSTRACT;
        } else if (objectDef instanceof RecordDef) {
            flags |= ACC_FINAL;
        } else if (objectDef instanceof AnnotationObjectDef) {
            flags |= ACC_ANNOTATION | ACC_INTERFACE | ACC_ABSTRACT;
        } else if (objectDef instanceof ClassDef classDef && EnumGenUtils.isEnum(classDef)) {
            flags |= ACC_ENUM;
        }
        if (objectDef.isSynthetic()) {
            flags |= ACC_SYNTHETIC;
        }
        return flags;
    }
}
