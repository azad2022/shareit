package com.peyvand.core.connectivity.wifi

import java.math.BigInteger
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint

/**
 * Exact secp256r1 parameter check for long-lived PEYVAND signing identities.
 *
 * Field/order bit lengths alone are not curve identity: other 256-bit curves exist.
 */
internal object PeerIdentityCurvePolicy {
    private val fieldPrime = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
        16,
    )
    private val curveA = fieldPrime.subtract(BigInteger.valueOf(3L))
    private val curveB = BigInteger(
        "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
        16,
    )
    private val generator = ECPoint(
        BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
        BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16),
    )
    private val subgroupOrder = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        16,
    )

    fun isP256(parameters: ECParameterSpec): Boolean {
        val field = parameters.curve.field as? ECFieldFp ?: return false
        return field.p == fieldPrime &&
            parameters.curve.a == curveA &&
            parameters.curve.b == curveB &&
            parameters.generator == generator &&
            parameters.order == subgroupOrder &&
            parameters.cofactor == 1
    }
}
