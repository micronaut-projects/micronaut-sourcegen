/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InnerTypesTest {
    @Test
    public void enumTest() {
        assertEquals(3, MyEnumWithInnerTypes.values().length);
        assertEquals("A", MyEnumWithInnerTypes.A.myName());
        assertEquals("B", MyEnumWithInnerTypes.B.myName());
        assertEquals("C", MyEnumWithInnerTypes.C.myName());

        innerEnumTest(
            MyEnumWithInnerTypes.InnerEnum.values().length,
            MyEnumWithInnerTypes.InnerEnum.SINGLE.myName(),
            MyEnumWithInnerTypes.InnerEnum.MARRIED.myName());

        innerInterfaceTest();

        MyEnumWithInnerTypes.InnerRecord innerRecord = MyEnumWithInnerTypesInnerRecordBuilder.builder().id(3).build();
        assertEquals(3, innerRecord.id());

        MyEnumWithInnerTypes.InnerClass innerClass = new MyEnumWithInnerTypes.InnerClass("name");
        assertEquals("name", innerClass.getName());
    }

    @Test
    public void recordTest() {
        RecordWithInnerTypes myRecord = new RecordWithInnerTypes(0, "name");
        assertEquals(0, myRecord.id());
        assertEquals("name", myRecord.name());

        innerEnumTest(
            RecordWithInnerTypes.InnerEnum.values().length,
            RecordWithInnerTypes.InnerEnum.SINGLE.myName(),
            RecordWithInnerTypes.InnerEnum.MARRIED.myName());

        innerInterfaceTest();

        RecordWithInnerTypes.InnerRecord innerRecord = RecordWithInnerTypesInnerRecordBuilder.builder().id(3).build();
        assertEquals(3, innerRecord.id());

        RecordWithInnerTypes.InnerClass innerClass = new RecordWithInnerTypes.InnerClass("name");
        assertEquals("name", innerClass.getName());
    }

    @Test
    public void classTest() {
        ClassWithInnerTypes myClass = new ClassWithInnerTypes();
        myClass.setId(0);
        myClass.setName("name");
        assertEquals(0, myClass.getId());
        assertEquals("name", myClass.getName());

        innerEnumTest(
            ClassWithInnerTypes.InnerEnum.values().length,
            ClassWithInnerTypes.InnerEnum.SINGLE.myName(),
            ClassWithInnerTypes.InnerEnum.MARRIED.myName());

        innerInterfaceTest();

        ClassWithInnerTypes.InnerRecord innerRecord = ClassWithInnerTypesInnerRecordBuilder.builder().id(3).build();
        assertEquals(3, innerRecord.id());

        ClassWithInnerTypes.InnerClass innerClass = new ClassWithInnerTypes.InnerClass("name");
        assertEquals("name", innerClass.getName());
    }

    @Test
    public void InterfaceTest() {
        assertEquals(true, InterfaceWithInnerTypes.hello());

        innerEnumTest(
            InterfaceWithInnerTypes.InnerEnum.values().length,
            InterfaceWithInnerTypes.InnerEnum.SINGLE.myName(),
            InterfaceWithInnerTypes.InnerEnum.MARRIED.myName());

        innerInterfaceTest();

        InterfaceWithInnerTypes.InnerRecord innerRecord = InterfaceWithInnerTypesInnerRecordBuilder.builder().id(3).build();
        assertEquals(3, innerRecord.id());

        InterfaceWithInnerTypes.InnerClass innerClass = new InterfaceWithInnerTypes.InnerClass("name");
        assertEquals("name", innerClass.getName());
    }

    private void innerEnumTest(int values, String single, String married) {
        assertEquals(2, values);
        assertEquals("SINGLE", single);
        assertEquals("MARRIED", married);
    }

    private void innerInterfaceTest() {
        MyInstance myInstance = new MyInstance();
        Assertions.assertEquals(123L, myInstance.findLong());
        myInstance.saveString("abc");
        Assertions.assertEquals("abc", myInstance.myString);
    }

    static class MyInstance implements MyEnumWithInnerTypes.InnerInterface,
        RecordWithInnerTypes.InnerInterface, ClassWithInnerTypes.InnerInterface,
        InterfaceWithInnerTypes.InnerInterface
    {

        String myString;

        @Override
        public Long findLong() {
            return 123L;
        }

        @Override
        public void saveString(String myString) {
            this.myString = myString;
        }
    }
}
