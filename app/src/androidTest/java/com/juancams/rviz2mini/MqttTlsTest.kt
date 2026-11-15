// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.plot.JsonStream
import com.juancams.rviz2mini.plot.MqttJsonStream
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * MQTT with TLS (`mqtts://`) against the TLS test broker of tools/stream_test_server.py
 * (`--tls`, port 8884) and its self-signed certificate for the PC's IP, which the test accepts as CA:
 * `-e pc true -e pcIp IP -e caPem <certificate in base64>`. And another broker (`--tls-only`, port
 * 8885) with a certificate from the same CA but for another IP: with it the connection must fail.
 */
@RunWith(AndroidJUnit4::class)
class MqttTlsTest {

    @After
    fun tearDown() { MqttJsonStream.socketFactory = null }

    @Test
    fun mqttsWithItsCertificateAndHostCheck() {
        val args = InstrumentationRegistry.getArguments()
        val pem = args.getString("caPem")
        assumeTrue(args.getString("pc") == "true" && pem != null)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(android.util.Base64.decode(pem, android.util.Base64.DEFAULT).inputStream())
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", cert) }
        MqttJsonStream.socketFactory = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers, null)
        }.socketFactory

        val got = CopyOnWriteArrayList<Map<String, Any?>>()
        val s = JsonStream.create("mqtts://vizmini:secret@${TestArgs.pcIp}:8884/robot/#") { got.add(it) }!!
        s.start()
        try {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (got.size < 5 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            Log.i("MqttTls", "recibidos ${got.size}: ${got.firstOrNull()} error=${s.error}")
            assertTrue("no llega nada por mqtts: ${s.error}", got.size >= 5)
            assertTrue(got.first().containsKey("speed"))
            assertNull(s.error)
        } finally {
            s.close()
        }
        // The 8885 broker has a trusted certificate but for another IP: it is not accepted.
        val bad = JsonStream.create("mqtts://${TestArgs.pcIp}:8885/robot/#") {}!!
        bad.start()
        try {
            val until = SystemClock.uptimeMillis() + 10_000
            while (bad.error == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            Log.i("MqttTls", "otro nombre: ${bad.error}")
            // Certificate failure (the name does not match), not a connection failure.
            val e = bad.error.orEmpty()
            assertTrue("acepta un certificado de otro host: $e", Regex("subjectAltName|hostname|verif|certificate", RegexOption.IGNORE_CASE).containsMatchIn(e))
        } finally {
            bad.close()
        }
    }
}
