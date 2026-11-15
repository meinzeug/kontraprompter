package de.kontrakurs.kontraprompter.speech

import de.kontrakurs.kontraprompter.video.PcmTee

/** Standalone test program: stdin is synthetic PCM; never reads a microphone. */
object OfflinePcmProbe {
    @JvmStatic fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "pcm" -> {
                val tee = PcmTee()
                val subscription = tee.subscribe(2)
                var offset = 0L
                try {
                    while (true) {
                        val block = System.`in`.readNBytes(2048)
                        if (block.isEmpty()) break
                        check(block.size % 2 == 0)
                        tee.publish("00000000-0000-4000-8000-000000000001", tee.generation, offset, 1, 48_000, block, block.size)
                        val received = checkNotNull(subscription.poll())
                        check(received.firstSample == offset && received.sampleRate == 48_000)
                        val copy = ByteArray(received.samples.remaining())
                        received.samples.get(copy)
                        System.out.write(copy)
                        offset += block.size / 2
                    }
                    check(subscription.dropped == 0L)
                    System.out.flush()
                    System.err.println("synthetic_pcm_samples=$offset dropped=${subscription.dropped}")
                } finally { subscription.close(); tee.close() }
            }
            "match" -> {
                require(args.size == 2)
                val cursor = SpokenScriptCursor(spokenWords("Hallo zusammen heute sprechen wir über Medien und Videos"))
                val epoch = cursor.begin(0)
                val position = cursor.accept(epoch, 0, args[1], true)
                println("matched_word_position=${position ?: -1}")
            }
            else -> error("Expected pcm or match")
        }
    }
}
