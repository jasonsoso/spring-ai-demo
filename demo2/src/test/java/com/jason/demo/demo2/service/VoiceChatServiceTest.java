package com.jason.demo.demo2.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceChatServiceTest {

    @Test
    void sentenceBuffer_drainsOnPunctuation() {
        var buffer = new VoiceChatService.SentenceBuffer(40);
        buffer.append("你好，世界。");
        assertThat(buffer.drainReadySentences()).containsExactly("你好，世界。");
        assertThat(buffer.flush()).isEmpty();
    }

    @Test
    void sentenceBuffer_drainsWhenExceedingMaxChars() {
        var buffer = new VoiceChatService.SentenceBuffer(10);
        buffer.append("这是一段没有标点的较长文本内容");
        assertThat(buffer.drainReadySentences()).hasSize(1);
        assertThat(buffer.drainReadySentences()).isEmpty();
    }

    @Test
    void sentenceBuffer_flushReturnsRemaining() {
        var buffer = new VoiceChatService.SentenceBuffer(40);
        buffer.append("剩余");
        assertThat(buffer.flush()).isEqualTo("剩余");
        assertThat(buffer.flush()).isEmpty();
    }
}
