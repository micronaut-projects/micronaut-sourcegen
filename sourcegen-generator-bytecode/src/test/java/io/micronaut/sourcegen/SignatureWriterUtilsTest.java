package io.micronaut.sourcegen;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class SignatureWriterUtilsTest {

    TypeDef.TypeVariable entityType = new TypeDef.TypeVariable(
        "E",
        List.of()
    );
    TypeDef.TypeVariable idType = new TypeDef.TypeVariable(
        "ID",
        List.of()
    );

    MethodDef findById = MethodDef.builder("findById")
        .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
        .addParameter("id", idType)
        .returns(new ClassTypeDef.Parameterized(
            ClassTypeDef.of(Optional.class),
            List.of(entityType)
        ))
        .build();

    MethodDef findAll = MethodDef.builder("findAll")
        .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
        .returns(new ClassTypeDef.Parameterized(
            ClassTypeDef.of(List.class),
            List.of(entityType)
        ))
        .build();

    MethodDef save = MethodDef.builder("save")
        .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
        .addParameter("entity", entityType)
        .returns(TypeDef.VOID)
        .build();

    InterfaceDef crudRepositoryDef = InterfaceDef.builder("example.CrudRepository1")
        .addModifiers(Modifier.PUBLIC)
        .addTypeVariable(
            entityType
        )
        .addTypeVariable(
            idType
        )
        .addMethod(findById)
        .addMethod(findAll)
        .addMethod(save)
        .build();


    ClassDef myEntityDef = ClassDef.builder( "example.MyEntity1")
        .addProperty(
            PropertyDef.builder("id").ofType(TypeDef.of(Long.class).makeNullable()).build()
        )
        .addProperty(
            PropertyDef.builder("firstName").ofType(TypeDef.of(String.class).makeNullable()).build()
        )
        .addProperty(
            PropertyDef.builder("age").ofType(TypeDef.of(Integer.class).makeNullable()).build()
        )
        .build();

    InterfaceDef myRepositoryRef = InterfaceDef.builder( "example.MyRepository1")
        .addModifiers(Modifier.PUBLIC)

        .addSuperinterface(new ClassTypeDef.Parameterized(
            crudRepositoryDef.asTypeDef(),
            List.of(myEntityDef.asTypeDef(), TypeDef.of(Long.class))
        ))

        .build();

    ClassDef ifPredicateDef = ClassDef.builder("example.IfPredicate")
        .addSuperinterface(TypeDef.parameterized(Predicate.class, Object.class))
        .build();

    @Test
    public void interfaceSignature() {
        Assertions.assertEquals(
            "<E:Ljava/lang/Object;ID:Ljava/lang/Object;>Ljava/lang/Object;",
            SignatureWriterUtils.getInterfaceSignature(crudRepositoryDef)
        );

        Assertions.assertEquals(
            "Ljava/lang/Object;Lexample/CrudRepository1<Lexample/MyEntity1;Ljava/lang/Long;>;",
            SignatureWriterUtils.getInterfaceSignature(myRepositoryRef)
        );
    }

    @Test
    public void interfaceMethodSignature() {
        Assertions.assertEquals(
            "(TID;)Ljava/util/Optional<TE;>;",
            SignatureWriterUtils.getMethodSignature(null, findById)
        );

        Assertions.assertEquals(
            "()Ljava/util/List<TE;>;",
            SignatureWriterUtils.getMethodSignature(null, findAll)
        );

        Assertions.assertEquals(
            "(TE;)V",
            SignatureWriterUtils.getMethodSignature(null, save)
        );
    }

    @Test
    public void methodSignature() {
//        Assertions.assertEquals(
//            "()V",
//            SignatureWriterUtils.getMethodSignature(
//                MethodDef.builder("<init>")
//                    .addParameter(ParameterDef.of("value", TypeDef.STRING))
//                    .returns(TypeDef.VOID)
//                    .build()
//            )
//        );

        ClassTypeDef myBeanType = ClassTypeDef.of("example.MyBean");

        String builderSimpleName = myBeanType.getSimpleName() + "Builder";
        String builderClassName = myBeanType.getPackageName() + "." + builderSimpleName;
        ClassTypeDef builderType = ClassTypeDef.of(builderClassName);

        MethodDef build = MethodDef.builder("with")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .addParameter("consumer", TypeDef.parameterized(ClassTypeDef.of(Consumer.class), builderType))
            .returns(myBeanType)
            .build();

        Assertions.assertEquals(
            "(Ljava/util/function/Consumer<Lexample/MyBeanBuilder;>;)Lexample/MyBean;",
            SignatureWriterUtils.getMethodSignature(
                null,
                build
            )
        );
    }

    @Test
    public void classSignature() {
        Assertions.assertEquals(
            "Ljava/lang/Object;Ljava/util/function/Predicate<Ljava/lang/Object;>;",
            SignatureWriterUtils.getClassSignature(ifPredicateDef)
        );
    }

    @Test
    public void classSignature2() {
        ClassTypeDef myBean = ClassTypeDef.of("example.MyBean");

        ClassTypeDef abstractBuilderType = ClassTypeDef.of(myBean.getPackageName() + "." + "Abstract" + myBean.getSimpleName() + "SuperBuilder");

        TypeDef.TypeVariable selfType = new TypeDef.TypeVariable("B");
        TypeDef.TypeVariable producingType = new TypeDef.TypeVariable("C");
        ClassDef abstractBuilder = ClassDef.builder(abstractBuilderType.getName())
            .addTypeVariable(new TypeDef.TypeVariable("C", List.of(myBean)))
            .addTypeVariable(new TypeDef.TypeVariable("B",
                    List.of(
                        TypeDef.parameterized(
                            abstractBuilderType,
                            producingType,
                            selfType
                        )
                    )
                )
            )
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .build();

        Assertions.assertEquals(
            "<C:Lexample/MyBean;B:Lexample/AbstractMyBeanSuperBuilder<TC;TB;>;>Ljava/lang/Object;",
            SignatureWriterUtils.getClassSignature(abstractBuilder)
        );
    }

    @Test
    public void basicInterfaceSignature() {
        InterfaceDef interfaceDef = InterfaceDef.builder("example.MyInterface1")
            .addModifiers(Modifier.PUBLIC)
            .build();
        Assertions.assertNull(SignatureWriterUtils.getInterfaceSignature(interfaceDef));
    }

    @Test
    public void arrayMethodSignature() {
        ClassDef arrayClassDef1 = ClassDef.builder( "example.Array1")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("test").addParameter("param", TypeDef.STRING)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING.array(1))
                .build())
            .build();
        Assertions.assertNull(SignatureWriterUtils.getMethodSignature(null, arrayClassDef1.getMethods().get(0)));
    }

}
