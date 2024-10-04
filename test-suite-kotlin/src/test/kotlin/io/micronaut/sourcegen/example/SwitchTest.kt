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
package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

internal class SwitchTest {
    @Test
    fun switch1() {
        val switch1: Switch1 = Switch1()
        assertEquals(1, switch1.test("abc"))
        assertEquals(2, switch1.test("xyz"))
        assertEquals(3, switch1.test("ZZZZ"))
    }

    @Test
    fun switch2() {
        val switch2: Switch2 = Switch2()
        assertEquals(TimeUnit.SECONDS, switch2.test("abc"))
        assertEquals(TimeUnit.MINUTES, switch2.test("xyz"))
        assertEquals(TimeUnit.HOURS, switch2.test("ZZZZ"))
    }

    @Test
    fun switch3() {
        val switch3: Switch3 = Switch3()
        assertEquals(TimeUnit.MICROSECONDS, switch3.test("abc", 123))
        assertEquals(TimeUnit.MILLISECONDS, switch3.test("abc", 1))
        assertEquals(TimeUnit.NANOSECONDS, switch3.test("abc", 2))
        assertEquals(TimeUnit.MINUTES, switch3.test("xyz", 111))
        assertEquals(TimeUnit.HOURS, switch3.test("ZZZZ", 111))
    }


    @Test
    fun switch4() {
        val switch4: Switch4 = Switch4()
        assertEquals(1, switch4.test("abc"))
        assertEquals(2, switch4.test("xyz"))
        assertEquals(3, switch4.test("ZZZZ"))
    }

    @Test
    fun switch5() {
        val switch5: Switch5 = Switch5()
        assertEquals(TimeUnit.SECONDS, switch5.test("abc"))
        assertEquals(TimeUnit.MINUTES, switch5.test("xyz"))
        assertEquals(TimeUnit.HOURS, switch5.test("ZZZZ"))
    }

    @Test
    fun switch6() {
        val switch6: Switch6 = Switch6()
        assertEquals(TimeUnit.MICROSECONDS, switch6.test("abc", 123))
        assertEquals(TimeUnit.MILLISECONDS, switch6.test("abc", 1))
        assertEquals(TimeUnit.NANOSECONDS, switch6.test("abc", 2))
        assertEquals(TimeUnit.MINUTES, switch6.test("xyz", 111))
        assertEquals(TimeUnit.HOURS, switch6.test("ZZZZ", 111))
    }

    @Test
    fun switch7() {
        val switch7: Switch7 = Switch7()
        assertEquals(TimeUnit.MICROSECONDS, switch7.test("abc", 123))
        assertEquals(TimeUnit.MILLISECONDS, switch7.test("abc", 1))
        assertEquals(TimeUnit.NANOSECONDS, switch7.test("abc", 2))
        assertEquals(TimeUnit.MINUTES, switch7.test("xyz", 111))
        assertEquals(TimeUnit.HOURS, switch7.test("ZZZZ", 111))
    }

    @Test
    fun switch8() {
        val switch8: Switch8 = Switch8()
        assertEquals(TimeUnit.SECONDS, switch8.test("abc"))
        assertEquals(TimeUnit.MINUTES, switch8.test("xyz"))
        assertEquals(TimeUnit.HOURS, switch8.test("ZZZZ"))
    }
}
