package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MethodInvokerTest {

    MyRepository repository = new MyRepository() {
        @Override
        public String interfaceMethod(String string, Integer integer, int i) {
            return string + (integer + i);
        }

        @Override
        public double interfaceMethodReturnsDouble() {
            return 1;
        }

        @Override
        public long interfaceMethodReturnsLong() {
            return 2;
        }

        @Override
        public int interfaceMethodReturnsInt() {
            return 3;
        }
    };

    MyRepository repositoryImplementedDefault = new MyRepository() {
        @Override
        public String interfaceMethod(String string, Integer integer, int i) {
            return string + (integer + i);
        }

        @Override
        public String defaultMethod(String string, Integer integer, int i) {
            return "X" + MyRepository.super.defaultMethod(string, integer, i);
        }

        @Override
        public double interfaceMethodReturnsDouble() {
            return 1;
        }

        @Override
        public long interfaceMethodReturnsLong() {
            return 2;
        }

        @Override
        public int interfaceMethodReturnsInt() {
            return 3;
        }
    };

    @Test
    public void testDefaultMethod() {
        assertEquals(
            "DEFAULT102",
            MethodInvoker.invokeDefaultMethod(repository, "DEFAULT", 100, 2)
        );
    }

    @Test
    public void testDefaultMethod2() {
        assertEquals(
            "XDEFAULT102",
            MethodInvoker.invokeDefaultMethod(repositoryImplementedDefault, "DEFAULT", 100, 2)
        );
    }

    @Test
    public void testInterfaceMethod() {
        assertEquals(
            "IFC102",
            MethodInvoker.invokeInterfaceMethod(repository, "IFC", 100, 2)
        );
    }

    @Test
    public void testStaticMethod() {
        assertEquals(
            "STT102",
            MethodInvoker.invokeStaticMethod("STT", 100, 2)
        );
    }

    @Test
    public void testInterfaceSuperMethod() {
        assertEquals(
            "ABCSTT102",
            new MethodRepositoryInvoker() {

                @Override
                public String interfaceMethod(String string, Integer integer, int i) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public double interfaceMethodReturnsDouble() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public long interfaceMethodReturnsLong() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int interfaceMethodReturnsInt() {
                    throw new UnsupportedOperationException();
                }
            }.defaultMethod("STT", 100, 2)
        );
    }

    @Test
    public void testDefaultMethodIgnoreResult() {
        assertEquals(
            "Ignored",
            MethodInvoker.invokeDefaultMethodIgnoreResult(repository, "DEFAULT", 100, 2)
        );
    }

    @Test
    public void testDefaultMethod2IgnoreResult() {
        assertEquals(
            "Ignored",
            MethodInvoker.invokeDefaultMethodIgnoreResult(repositoryImplementedDefault, "DEFAULT", 100, 2)
        );
    }

    @Test
    public void testInterfaceMethodIgnoreResult() {
        assertEquals(
            "Ignored",
            MethodInvoker.invokeInterfaceMethodIgnoreResult(repository, "IFC", 100, 2)
        );
    }

    @Test
    public void testStaticMethodIgnoreResult() {
        assertEquals(
            "Ignored",
            MethodInvoker.invokeStaticMethodIgnoreResult("STT", 100, 2)
        );
    }

    @Test
    public void testInvokeTryFinallyMethod() {
        AtomicInteger lock = new AtomicInteger();
        assertEquals(
            0,
            lock.get()
        );
        MethodInvoker.invokeTryFinally(lock);
        assertEquals(
            1,
            lock.get()
        );
    }

    @Test
    public void testInvokeTryFinallyLockMethod() {
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        assertEquals(
            123,
            MethodInvoker.invokeTryFinallyReadLock(lock)
        );
        assertEquals(
            123,
            MethodInvoker.invokeTryFinallyWriteLock(lock)
        );
        assertEquals(
            123,
            MethodInvoker.invokeTryFinallyReadLock(lock)
        );
        assertEquals(
            123,
            MethodInvoker.invokeTryFinallyWriteLock(lock)
        );
    }

    @Test
    public void testSwapper1() {
        Swapper swapper = new Swapper();
        assertNull(swapper.getTarget());
        Object o1 = new Object();
        swapper.swap(o1);
        assertEquals(
            o1,
            swapper.getTarget()
        );
        Object o2 = new Object();
        swapper.swap(o2);
        assertEquals(
            o2,
            swapper.getTarget()
        );
    }

    @Test
    public void testSwapper2() {
        Swapper swapper = new Swapper();
        assertNull(swapper.getTarget());
        AtomicInteger counter = new AtomicInteger();
        Object o1 = new Object();
        swapper.swap2(o1, counter);
        assertEquals(
            o1,
            swapper.getTarget()
        );
        assertEquals(
            0,
            counter.get()
        );
        Object o2 = new Object();
        swapper.swap2(o2, counter);
        assertEquals(
            o2,
            swapper.getTarget()
        );
        assertEquals(
            0,
            counter.get()
        );
    }

    @Test
    public void testSwapper3() {
        Swapper swapper = new Swapper();
        assertNull(swapper.getTarget());
        AtomicInteger counter = new AtomicInteger();
        try {
            swapper.swap3(new Object(), counter);
            Assertions.fail();
        } catch (IllegalStateException e) {
            // Ignore
        }
        assertEquals(
            0,
            counter.get()
        );
        try {
            swapper.swap3(new Object(), counter);
            Assertions.fail();
        } catch (IllegalStateException e) {
            // Ignore
        }
        assertEquals(
            0,
            counter.get()
        );
    }

    @Test
    public void testSwapper4() {
        Swapper swapper = new Swapper();
        AtomicInteger counter = new AtomicInteger();
        Object result = swapper.swap4(new Object(), counter);
        assertEquals(
            0,
            counter.get()
        );
        assertEquals(
            "Bam",
            result
        );
    }

    @Test
    public void testSwapper5() {
        Swapper swapper = new Swapper();
        assertNull(swapper.getTarget());
        Object o1 = new Object();
        swapper.swap5(o1);
        assertEquals(
            o1,
            swapper.getTarget()
        );
        Object o2 = new Object();
        swapper.swap5(o2);
        assertEquals(
            o2,
            swapper.getTarget()
        );
    }

    @Test
    public void testSwapper6() {
        Swapper swapper = new Swapper();
        try {
            swapper.swap6(new Object());
            Assertions.fail();
        } catch (IllegalStateException e) {
            // Ignore
        }
        try {
            swapper.swap6(new Object());
        } catch (IllegalStateException e) {
            // Ignore
        }
    }

}
