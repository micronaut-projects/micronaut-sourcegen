package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
        Assertions.assertEquals(
            "DEFAULT102",
            MethodInvoker.invokeDefaultMethod(repository, "DEFAULT", 100, 2)
        );
    }

    @Test
    public void testDefaultMethod2() {
        Assertions.assertEquals(
            "XDEFAULT102",
            MethodInvoker.invokeDefaultMethod(repositoryImplementedDefault, "DEFAULT", 100, 2)
        );
    }

    @Test
    public void testInterfaceMethod() {
        Assertions.assertEquals(
            "IFC102",
            MethodInvoker.invokeInterfaceMethod(repository, "IFC", 100, 2)
        );
    }

    @Test
    public void testStaticMethod() {
        Assertions.assertEquals(
            "STT102",
            MethodInvoker.invokeStaticMethod("STT", 100, 2)
        );
    }

    @Test
    public void testInterfaceSuperMethod() {
        Assertions.assertEquals(
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
        Assertions.assertEquals(
            "Ignored",
            MethodInvoker.invokeDefaultMethodIgnoreResult(repository, "DEFAULT", 100, 2)
        );
    }

    @Test
    public void testDefaultMethod2IgnoreResult() {
        Assertions.assertEquals(
            "Ignored",
            MethodInvoker.invokeDefaultMethodIgnoreResult(repositoryImplementedDefault, "DEFAULT", 100, 2)
        );
    }

    @Test
    public void testInterfaceMethodIgnoreResult() {
        Assertions.assertEquals(
            "Ignored",
            MethodInvoker.invokeInterfaceMethodIgnoreResult(repository, "IFC", 100, 2)
        );
    }

    @Test
    public void testStaticMethodIgnoreResult() {
        Assertions.assertEquals(
            "Ignored",
            MethodInvoker.invokeStaticMethodIgnoreResult("STT", 100, 2)
        );
    }

    @Test
    public void testInvokeTryFinallyMethod() {
        AtomicInteger lock = new AtomicInteger();
        Assertions.assertEquals(
            0,
            lock.get()
        );
        MethodInvoker.invokeTryFinally(lock);
        Assertions.assertEquals(
            1,
            lock.get()
        );
    }

    @Test
    public void testInvokeTryFinallyLockMethod() {
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        Assertions.assertEquals(
            123,
            MethodInvoker.invokeTryFinallyReadLock(lock)
        );
        Assertions.assertEquals(
            123,
            MethodInvoker.invokeTryFinallyWriteLock(lock)
        );
        Assertions.assertEquals(
            123,
            MethodInvoker.invokeTryFinallyReadLock(lock)
        );
        Assertions.assertEquals(
            123,
            MethodInvoker.invokeTryFinallyWriteLock(lock)
        );
    }

    @Test
    public void testSwapper1() {
        Swapper swapper = new Swapper();
        Assertions.assertEquals(
            null,
            swapper.getTarget()
        );
        Object o1 = new Object();
        swapper.swap(o1);
        Assertions.assertEquals(
            o1,
            swapper.getTarget()
        );
        Object o2 = new Object();
        swapper.swap(o2);
        Assertions.assertEquals(
            o2,
            swapper.getTarget()
        );
    }

    @Test
    public void testSwapper2() {
        Swapper swapper = new Swapper();
        Assertions.assertEquals(
            null,
            swapper.getTarget()
        );
        AtomicInteger counter = new AtomicInteger();
        Object o1 = new Object();
        swapper.swap2(o1, counter);
        Assertions.assertEquals(
            o1,
            swapper.getTarget()
        );
        Assertions.assertEquals(
            0,
            counter.get()
        );
        Object o2 = new Object();
        swapper.swap2(o2, counter);
        Assertions.assertEquals(
            o2,
            swapper.getTarget()
        );
        Assertions.assertEquals(
            0,
            counter.get()
        );
    }

    @Test
    public void testSwapper3() {
        Swapper swapper = new Swapper();
        Assertions.assertEquals(
            null,
            swapper.getTarget()
        );
        AtomicInteger counter = new AtomicInteger();
        try {
            swapper.swap3(new Object(), counter);
            Assertions.fail();
        } catch (IllegalStateException e) {
            // Ignore
        }
        Assertions.assertEquals(
            0,
            counter.get()
        );
        try {
            swapper.swap3(new Object(), counter);
            Assertions.fail();
        } catch (IllegalStateException e) {
            // Ignore
        }
        Assertions.assertEquals(
            0,
            counter.get()
        );
    }

    @Test
    public void testSwapper4() {
        Swapper swapper = new Swapper();
        AtomicInteger counter = new AtomicInteger();
        Object result = swapper.swap4(new Object(), counter);
        Assertions.assertEquals(
            0,
            counter.get()
        );
        Assertions.assertEquals(
            "Bam",
            result
        );
    }

    @Test
    public void testSwapper5() {
        Swapper swapper = new Swapper();
        Assertions.assertEquals(
            null,
            swapper.getTarget()
        );
        Object o1 = new Object();
        swapper.swap5(o1);
        Assertions.assertEquals(
            o1,
            swapper.getTarget()
        );
        Object o2 = new Object();
        swapper.swap5(o2);
        Assertions.assertEquals(
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
