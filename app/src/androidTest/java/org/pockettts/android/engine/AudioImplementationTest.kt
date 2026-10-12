package org.pockettts.android.engine

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Disposable QA files only; restores manifests and deletes only this test's voices. */
class AudioImplementationTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture()=File(app.cacheDir,"implementation-${System.nanoTime()}.wav").also { file ->
        PcmWav.Writer(file).use { it.write(FloatArray(96000) { sin(it*.2).toFloat()*.1f }) }
    }

    @Test fun atomicRecoveryMissingPartialInvalidAndSerializedReads() {
        val pack=ModelPackRepository.find(app,BundledPocketTts.ID)!!
        val manifest=pack.manifestFile; val original=manifest.readBytes(); val backup=File(manifest.path+".bak")
        assertFalse(backup.exists())
        try {
            for (partial in listOf(false,true)) {
                backup.writeBytes(original)
                if(partial) manifest.writeText("{partial") else assertTrue(manifest.delete())
                assertNotNull(ModelPackRepository.find(app,pack.id))
                assertArrayEquals(original,manifest.readBytes()); assertFalse(backup.exists())
            }
            for (invalid in listOf("{invalid", "{\"format\":1,\"id\":\"${pack.id}\",\"voices\":[]}")) {
                backup.writeText(invalid)
                assertNotNull(ModelPackRepository.find(app,pack.id)); assertTrue(backup.exists())
                val sample=fixture()
                try { assertTrue(runCatching { ModelPackRepository.importRecording(app,pack.id,"Invalid backup QA",sample) }.isFailure) }
                finally { sample.delete() }
                assertArrayEquals(original,manifest.readBytes()); backup.delete()
            }
            val entered=CountDownLatch(1); val done=CountDownLatch(1)
            synchronized(ManifestStorage.lock) {
                Thread { entered.countDown(); ManifestStorage.read(manifest); done.countDown() }.start()
                assertTrue(entered.await(2,TimeUnit.SECONDS)); assertFalse(done.await(100,TimeUnit.MILLISECONDS))
            }
            assertTrue(done.await(2,TimeUnit.SECONDS))
        } finally { manifest.writeBytes(original); backup.delete() }
    }

    @Test fun float32And64HeadroomStereoCancellationAndInvalidSamples() {
        val source=File(app.cacheDir,"headroom-${System.nanoTime()}.wav")
        val output=File(app.cacheDir,"headroom-output-${System.nanoTime()}.wav")
        fun write(bits:Int, channels:Int, value:Double, opposite:Boolean=false) {
            val frames=72000; val bytes=frames*channels*bits/8
            val b=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36+bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            b.putShort(3).putShort(channels.toShort()).putInt(24000).putInt(24000*channels*bits/8).putShort((channels*bits/8).toShort()).putShort(bits.toShort())
            b.put("data".toByteArray()).putInt(bytes)
            repeat(frames) { frame -> repeat(channels) { ch ->
                val v=value*(if(frame%2==0) 1 else -1)*(if(opposite && ch==1) -1 else 1)
                if(bits==32) b.putFloat(v.toFloat()) else b.putDouble(v)
            } }
            source.writeBytes(b.array())
        }
        try {
            for(bits in listOf(32,64)) {
                write(bits,1,1.2); WavSamples.trim(source,output,0,3000,.5f)
                assertEquals(.6f,DeepFilterNet3Denoiser.readRecording(output).maxOrNull()!!,.001f)
                write(bits,2,1.2,true); WavSamples.trim(source,output,0,3000,.5f)
                assertEquals(0f,DeepFilterNet3Denoiser.readRecording(output).maxOf { abs(it) },.00001f)
                for(invalid in listOf(Double.NaN,Double.POSITIVE_INFINITY,1e20)) {
                    write(bits,1,invalid); assertTrue(runCatching { WavSamples.trim(source,output,0,3000,.5f) }.isFailure)
                }
            }
        } finally { source.delete(); output.delete() }
    }

    @Test fun cancellingQueuedNewSaveCannotCommitOrLeaveOrphans() {
        val pack=ModelPackRepository.find(app,BundledPocketTts.ID)!!
        val source=fixture(); val cancelled=AtomicBoolean(false); val started=CountDownLatch(1)
        var result:Result<ModelPack>?=null
        val before=pack.manifestFile.readBytes(); val files=pack.voicesDir.listFiles()!!.map { it.name }.toSet()
        val thread=Thread {
            started.countDown()
            result=runCatching { ModelPackRepository.importRecording(app,pack.id,"Cancel queued ${System.nanoTime()}",source,original=source,cancelled=cancelled::get) }
        }
        try {
            synchronized(PocketEngine) {
                thread.start(); assertTrue(started.await(2,TimeUnit.SECONDS))
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
                while(thread.state!=Thread.State.BLOCKED && System.nanoTime()<deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED,thread.state); cancelled.set(true)
            }
            thread.join(5000); assertFalse(thread.isAlive); assertTrue(result!!.isFailure)
            assertArrayEquals(before,pack.manifestFile.readBytes())
            assertEquals(files,pack.voicesDir.listFiles()!!.map { it.name }.toSet())
            println("REL-002 queued save cancelled after engine lock wait; no commit/orphans")
        } finally { cancelled.set(true); thread.join(5000); source.delete() }
    }

    @Test fun cancellationDuringCopiesAndBeforeCommitRollsBackOnlyThisOperation() {
        val pack=ModelPackRepository.find(app,BundledPocketTts.ID)!!
        val source=fixture(); val manifest=pack.manifestFile.readBytes()
        val existing=pack.voicesDir.walkTopDown().map { it.relativeTo(pack.voicesDir).path }.toSet()
        try {
            for(stopAt in listOf(1,8,32,52)) {
                var calls=0
                assertTrue(runCatching { ModelPackRepository.importRecording(app,pack.id,"Cancel copy $stopAt",source,original=source,
                    cancelled={ ++calls>=stopAt }) }.isFailure)
                assertArrayEquals(manifest,pack.manifestFile.readBytes())
                assertEquals(existing,pack.voicesDir.walkTopDown().map { it.relativeTo(pack.voicesDir).path }.toSet())
            }
            val cancelled=AtomicBoolean(false)
            val committed=ModelPackRepository.importRecording(app,pack.id,"Committed ${System.nanoTime()}",source,original=source,cancelled=cancelled::get).voices.last()
            cancelled.set(true) // A later cancellation must not undo an already committed save.
            try { assertNotNull(ModelPackRepository.find(app,pack.id)!!.voices.firstOrNull { it.id==committed.id }) }
            finally { ModelPackRepository.deleteVoice(app,pack.id,committed.id) }
        } finally { source.delete() }
    }

    @Test fun referenceRecipeBackupAndReaderGainAreIndependent() {
        val source=fixture(); val amplified=File(app.cacheDir,"gain-${System.nanoTime()}.wav")
        val pack=ModelPackRepository.find(app,BundledPocketTts.ID)!!
        var id:String?=null
        try {
            WavSamples.trim(source,amplified,0,4000,2f)
            val recipe=VoiceEdit(0,4000,PcmGain.db(2f),PcmGain.db(2f),false)
            val voice=ModelPackRepository.importRecording(app,pack.id,"Recipe ${System.nanoTime()}",amplified,original=source,trimmed=amplified,edit=recipe).voices.last()
            id=voice.id; val key=ModelPackRepository.voiceName(pack,voice)
            ReaderGain.set(app,key,4f)
            val reopened=ModelPackRepository.find(app,pack.id)!!.voices.first { it.id==voice.id }
            assertEquals(recipe,reopened.edit); assertArrayEquals(source.readBytes(),ModelPackRepository.editingSource(pack,reopened).readBytes())
            val restored=ModelPackRepository.renameVoice(app,pack.id,voice.id,"Renamed ${System.nanoTime()}").voices.first { it.id==voice.id }
            assertEquals(4f,ReaderGain.get(app,ModelPackRepository.voiceName(pack,restored)),0f)
            assertEquals(0f,ReaderGain.get(app,"${pack.id}::alba"),0f)
            val bytes=java.io.ByteArrayOutputStream(); VoiceBackup.export(app,bytes)
            val originalIds=ModelPackRepository.find(app,pack.id)!!.voices.map { it.id }.toSet()
            VoiceBackup.import(app,java.io.ByteArrayInputStream(bytes.toByteArray()))
            val copies=ModelPackRepository.find(app,pack.id)!!.voices.filter { it.id !in originalIds }
            try {
                val copy=copies.first { it.edit==recipe }
                assertArrayEquals(amplified.readBytes(),File(pack.voicesDir,copy.fileName).readBytes())
                assertArrayEquals(source.readBytes(),ModelPackRepository.editingSource(pack,copy).readBytes())
                assertEquals(0f,ReaderGain.get(app,ModelPackRepository.voiceName(pack,copy)),0f)
            } finally { copies.forEach { ModelPackRepository.deleteVoice(app,pack.id,it.id) } }
            val reader=File(app.cacheDir,"reader-gain-${System.nanoTime()}.wav")
            try {
                ReaderGain.render(source,reader,PcmGain.MAX_DB) { false }
                assertEquals(.2f,DeepFilterNet3Denoiser.readRecording(reader).maxOrNull()!!,.001f)
                assertEquals(.1f,DeepFilterNet3Denoiser.readRecording(source).maxOrNull()!!,.001f)
            } finally { reader.delete() }
        } finally { id?.let { ModelPackRepository.deleteVoice(app,pack.id,it) }; source.delete(); amplified.delete() }
    }
}
