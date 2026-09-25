/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.langchain4j.ingest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.apache.camel.BindToRegistry;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.KeyValueIdempotentRepository;
import org.apache.camel.test.junit6.CamelTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LangChain4jIngestAudioTest extends CamelTestSupport {

    @BindToRegistry("store")
    private final InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

    @BindToRegistry("model")
    private final DeterministicAudioEmbeddingModel model = new DeterministicAudioEmbeddingModel(16);

    /** Claims taken on the register, so a test can assert a rejected delivery never took one. */
    private final AtomicInteger claims = new AtomicInteger();

    @BindToRegistry("register")
    private final IdempotentRepository register = new KeyValueIdempotentRepository() {
        @Override
        public boolean add(String key) {
            claims.incrementAndGet();
            return super.add(key);
        }
    };

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:audio")
                        .to("langchain4j-ingest:music?modality=audio&documentIdHeader=CamelFileName");
                // parameters after the semicolon are dropped before the type reaches the model
                from("direct:typed")
                        .to("langchain4j-ingest:typed?modality=audio&documentIdHeader=CamelFileName"
                            + "&contentType=audio/x-custom;rate=16000");
                // the batch size does not apply to audio, so even an invalid value must not fail the start
                from("direct:batch0")
                        .to("langchain4j-ingest:batch0?modality=audio&documentIdHeader=CamelFileName"
                            + "&embeddingBatchSize=0");
                from("direct:capped")
                        .to("langchain4j-ingest:capped?modality=audio&documentIdHeader=CamelFileName"
                            + "&maxDocumentSize=100");
                from("direct:min")
                        .to("langchain4j-ingest:min?modality=audio&documentIdHeader=CamelFileName"
                            + "&minDocumentSize=1000000");
                from("direct:dedup")
                        .to("langchain4j-ingest:dedup?modality=audio&documentIdHeader=CamelFileName"
                            + "&idempotentRepository=#bean:register");
            }
        };
    }

    @Test
    void ingestsOneVectorPerDocumentWithIdentityMetadata() throws Exception {
        byte[] wav = wav(200);

        IngestResult result = template.requestBodyAndHeader("direct:audio", wav,
                Exchange.FILE_NAME, "song-1.wav", IngestResult.class);

        assertThat(result.outcome()).isEqualTo(IngestResult.Outcome.INGESTED);
        assertThat(result.pipeline()).isEqualTo("music");
        assertThat(result.documentId()).isEqualTo("song-1.wav");
        assertThat(result.segmentsWritten()).isEqualTo(1);
        assertThat(model.mimeTypes()).containsExactly("audio/wav");

        List<EmbeddingMatch<TextSegment>> matches = search(wav);
        assertThat(matches).hasSize(1);
        EmbeddingMatch<TextSegment> match = matches.get(0);
        // the same clip embeds to the same vector: a query by it is an exact hit
        assertThat(match.score()).isGreaterThan(0.99);
        // the placeholder segment carries the id as text and the same identity stamps as text segments
        assertThat(match.embedded().text()).isEqualTo("song-1.wav");
        assertThat(match.embedded().metadata().getString(LangChain4jIngest.METADATA_PIPELINE)).isEqualTo("music");
        assertThat(match.embedded().metadata().getString(LangChain4jIngest.METADATA_DOCUMENT_ID))
                .isEqualTo("song-1.wav");
    }

    @Test
    void emptyBodyAnswersEmpty() {
        IngestResult result = template.requestBodyAndHeader("direct:audio", new byte[0],
                Exchange.FILE_NAME, "silence.wav", IngestResult.class);

        assertThat(result.outcome()).isEqualTo(IngestResult.Outcome.EMPTY);
        assertThat(result.segmentsWritten()).isZero();
    }

    @Test
    void tooSmallAudioIsFiltered() throws Exception {
        IngestResult result = template.requestBodyAndHeader("direct:min", wav(50),
                Exchange.FILE_NAME, "tiny.wav", IngestResult.class);

        assertThat(result.outcome()).isEqualTo(IngestResult.Outcome.FILTERED);
    }

    @Test
    void oversizedAudioFailsAndTheCapCountsBytes() throws Exception {
        assertThatThrownBy(() -> template.requestBodyAndHeader("direct:capped", wav(200),
                Exchange.FILE_NAME, "big.wav"))
                .isInstanceOf(CamelExecutionException.class)
                .hasStackTraceContaining("exceeds maxDocumentSize")
                .hasStackTraceContaining("bytes");
    }

    @Test
    void mimeTypeComesFromTheExtensionOrTheOption() throws Exception {
        byte[] clip = wav(50);

        template.requestBodyAndHeader("direct:audio", clip, Exchange.FILE_NAME, "clip.mp3", IngestResult.class);
        template.requestBodyAndHeader("direct:typed", clip, Exchange.FILE_NAME, "clip.wav", IngestResult.class);

        assertThat(model.mimeTypes()).containsExactly("audio/mpeg", "audio/x-custom");

        assertThatThrownBy(() -> template.requestBodyAndHeader("direct:audio", clip,
                Exchange.FILE_NAME, "clip.bin"))
                .isInstanceOf(CamelExecutionException.class)
                .hasStackTraceContaining("contentType");
    }

    @Test
    void duplicateIdIsSkippedWithARepository() throws Exception {
        byte[] wav = wav(50);

        IngestResult first = template.requestBodyAndHeader("direct:dedup", wav,
                Exchange.FILE_NAME, "song-2.wav", IngestResult.class);
        IngestResult second = template.requestBodyAndHeader("direct:dedup", wav,
                Exchange.FILE_NAME, "song-2.wav", IngestResult.class);

        assertThat(first.outcome()).isEqualTo(IngestResult.Outcome.INGESTED);
        assertThat(second.outcome()).isEqualTo(IngestResult.Outcome.SKIPPED);
        assertThat(search(wav)).hasSize(1);
    }

    @Test
    void textOnlyModelFailsTheStart() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.getRegistry().bind("store", new InMemoryEmbeddingStore<TextSegment>());
            context.getRegistry().bind("model", new DeterministicEmbeddingModel(16));
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:in").to("langchain4j-ingest:pipe?modality=audio");
                }
            });

            assertThatThrownBy(context::start)
                    .hasStackTraceContaining("does not support audio")
                    .hasStackTraceContaining("supportedContentTypes");
        }
    }

    @Test
    void unknownTypeFailsBeforeTheClaimAndTheBodyRead() {
        int claimsBefore = claims.get();
        // reading this body fails loudly, so the type must have been rejected before any read
        InputStream unreadable = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("body must not be read");
            }
        };

        Throwable thrown = catchThrowable(() -> template.requestBodyAndHeader("direct:dedup", unreadable,
                Exchange.FILE_NAME, "clip.bin"));

        assertThat(thrown).isInstanceOf(CamelExecutionException.class)
                .hasStackTraceContaining("cannot tell the audio type");
        StringWriter trace = new StringWriter();
        thrown.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).as("the body must not have been read").doesNotContain("body must not be read");
        assertThat(claims.get()).as("no claim for a document whose type cannot be told").isEqualTo(claimsBefore);
    }

    @Test
    void batchSizeIsNotValidatedInAudioMode() throws Exception {
        IngestResult result = template.requestBodyAndHeader("direct:batch0", wav(50),
                Exchange.FILE_NAME, "song-3.wav", IngestResult.class);

        assertThat(result.outcome()).isEqualTo(IngestResult.Outcome.INGESTED);
    }

    @Test
    void modelWithoutContentTypesFailsTheStart() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.getRegistry().bind("store", new InMemoryEmbeddingStore<TextSegment>());
            context.getRegistry().bind("model", new DeterministicEmbeddingModel(16) {
                @Override
                public Set<ContentType> supportedContentTypes() {
                    return null;
                }
            });
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:in").to("langchain4j-ingest:pipe?modality=audio");
                }
            });

            assertThatThrownBy(context::start)
                    .hasStackTraceContaining("does not support audio")
                    .hasStackTraceContaining("are null");
        }
    }

    @Test
    void textCannotBeIngestedThroughAnAudioService() {
        IngestService audioService = new IngestService("pipe", store, model, 0, 0);

        assertThatThrownBy(() -> audioService.ingest("doc", "some text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured for audio");
    }

    private List<EmbeddingMatch<TextSegment>> search(byte[] audio) {
        return store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(model.embeddingOf(audio))
                .maxResults(10)
                .build()).matches();
    }

    /** A mono 16 kHz PCM WAV of the given length holding a 440 Hz tone: a real file, no binary fixture. */
    static byte[] wav(int millis) throws IOException {
        AudioFormat format = new AudioFormat(16_000f, 16, 1, true, false);
        int frames = (int) (format.getSampleRate() * millis / 1000);
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / format.getSampleRate()) * Short.MAX_VALUE / 4);
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, frames),
                AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }
}
