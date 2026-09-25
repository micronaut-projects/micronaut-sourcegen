package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that each resolution of a call keeps the wildcards it captures to itself, so that one started within another
 * neither shares nor disturbs them.
 */
class ResolutionContextTest {

    @Test
    void twoCapturesOfOneResolutionAreDistinct() {
        ResolutionContext context = new ResolutionContext();

        TypeDef.TypeVariable first = context.capture(TypeDef.wildcard());
        TypeDef.TypeVariable second = context.capture(TypeDef.wildcard());

        assertNotEquals(first.name(), second.name());
        assertTrue(ResolutionContext.isCapture(first));
        assertFalse(ResolutionContext.isCapture(TypeDef.variable("T")));
    }

    @Test
    void aNestedResolutionKeepsItsCapturesApart() {
        ResolutionContext outer = new ResolutionContext();
        TypeDef.TypeVariable outerCapture = outer.capture(TypeDef.wildcardSupertypeOf(TypeDef.of(Integer.class)));

        ResolutionContext inner = new ResolutionContext();
        TypeDef.TypeVariable innerCapture = inner.capture(TypeDef.wildcard());

        // Numbered apart, the inner one does not see the lower bound of the outer one's `? super Integer`
        assertEquals(outerCapture.name(), innerCapture.name());
        assertNull(inner.lowerBound(innerCapture));
        assertEquals(TypeDef.of(Integer.class), outer.lowerBound(outerCapture));
    }

    @Test
    void aCaptureIsBoundedByTheWildcardAndTheDeclaredBound() {
        ResolutionContext context = new ResolutionContext();

        TypeDef.TypeVariable capture = context.capture(TypeDef.wildcard(), List.of(TypeDef.of(Number.class)));

        assertEquals(List.of(TypeDef.of(Number.class)), capture.bounds());
    }

    @Test
    void aCallResolvedAgainResolvesAlike() {
        // A capture of the receiver's `? super Integer` takes an Integer, whatever resolution ran before
        ClassTypeDef receiver = TypeDef.parameterized(ClassTypeDef.of(java.util.function.Consumer.class),
            TypeDef.wildcardSupertypeOf(TypeDef.of(Integer.class)));
        List<ExpressionDef> values = List.of(ExpressionDef.constant(1));

        OverloadResolution.Resolution first = OverloadResolution.resolve(List.of(receiver), "accept", TypeDef.VOID, values, null, null);
        OverloadResolution.Resolution second = OverloadResolution.resolve(List.of(receiver), "accept", TypeDef.VOID, values, null, null);

        assertEquals(OverloadResolution.Outcome.RESOLVED, first.outcome());
        assertEquals(first, second);
    }
}
