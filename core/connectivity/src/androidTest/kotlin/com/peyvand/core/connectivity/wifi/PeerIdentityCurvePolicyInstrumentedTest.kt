package com.peyvand.core.connectivity.wifi

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.EllipticCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeerIdentityCurvePolicyInstrumentedTest {
    @Test
    fun acceptsTheExactSecp256r1Parameters() {
        assertTrue(PeerIdentityCurvePolicy.isP256(p256Parameters()))
    }

    @Test
    fun rejectsAnotherCurveWithTheSameFieldAndOrderBitLengths() {
        val valid = p256Parameters()
        val alteredCurve = EllipticCurve(
            valid.curve.field,
            valid.curve.a,
            valid.curve.b.add(BigInteger.ONE),
            valid.curve.seed,
        )
        val sameBitLengthButDifferentCurve = ECParameterSpec(
            alteredCurve,
            valid.generator,
            valid.order,
            valid.cofactor,
        )

        assertEquals(256, sameBitLengthButDifferentCurve.curve.field.fieldSize)
        assertEquals(256, sameBitLengthButDifferentCurve.order.bitLength())
        assertFalse(PeerIdentityCurvePolicy.isP256(sameBitLengthButDifferentCurve))
    }

    private fun p256Parameters(): ECParameterSpec {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return (generator.generateKeyPair().public as ECPublicKey).params
    }
}
