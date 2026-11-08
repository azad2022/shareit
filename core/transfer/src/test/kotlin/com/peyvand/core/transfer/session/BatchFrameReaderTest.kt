package com.peyvand.core.transfer.session

import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchFrameReaderTest {
    @Test
    fun closeClosesBlockingInputAndReleasesReaderJob() = runBlocking {
        val input = BlockingInputStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reader = BatchFrameReader(input, scope, UUID.randomUUID())

        try {
            assertTrue("Reader did not enter the blocking read", input.readStarted.await(5, TimeUnit.SECONDS))

            reader.close()

            assertTrue("Reader close did not close the owning input", input.wasClosed.get())
            assertTrue(
                "Closing the input did not release the blocking read",
                input.readFinished.await(5, TimeUnit.SECONDS),
            )
        } finally {
            reader.close()
            scope.cancel()
        }
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val wasClosed = AtomicBoolean(false)
        private val unblockRead = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            try {
                unblockRead.await()
                return -1
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Blocking read was interrupted", interrupted)
            } finally {
                readFinished.countDown()
            }
        }

        override fun close() {
            wasClosed.set(true)
            unblockRead.countDown()
        }
    }
}
