package com.example.lip_database

import java.security.MessageDigest
import java.util.UUID

object ItemIdGenerator {
    fun create(name: String, storage: String, smNumber: String, weightKg: Double): String {
        val source = "$name|$storage|$smNumber|$weightKg|${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "QR-${digest.take(16).uppercase()}"
    }
}
