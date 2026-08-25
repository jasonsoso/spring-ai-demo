package com.jason.demo.demo2.framework.jackson;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 读写：Long/long/BigInteger → 字符串；BigDecimal → 去尾零纯文本；
 * LocalDate → yyyy-MM-dd；LocalDateTime/Instant → yyyy-MM-dd HH:mm:ss（Asia/Shanghai）。
 */
@Configuration
public class JacksonJsonCustomizer {

    public static final String DATE_PATTERN = "yyyy-MM-dd";
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    @Bean
    public JsonMapperBuilderCustomizer longAndDateTimeJsonCustomizer() {
        return builder -> builder.addModule(buildModule());
    }

    static SimpleModule buildModule() {
        SimpleModule module = new SimpleModule("demo2-long-datetime");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        module.addSerializer(BigInteger.class, ToStringSerializer.instance);
        module.addSerializer(BigDecimal.class, new BigDecimalPlainStringSerializer());
        module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER));
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        module.addSerializer(Instant.class, new InstantAsShanghaiStringSerializer());
        module.addDeserializer(Long.class, new LongFromStringDeserializer());
        module.addDeserializer(Long.TYPE, new LongFromStringDeserializer());
        module.addDeserializer(BigInteger.class, new BigIntegerFromStringDeserializer());
        module.addDeserializer(BigDecimal.class, new BigDecimalFromStringDeserializer());
        module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
        module.addDeserializer(Instant.class, new InstantFromShanghaiStringDeserializer());
        return module;
    }

    /** BigDecimal → JSON 字符串，去尾零且避免科学计数法。 */
    static final class BigDecimalPlainStringSerializer extends StdSerializer<BigDecimal> {

        BigDecimalPlainStringSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.stripTrailingZeros().toPlainString());
        }
    }

    static final class InstantAsShanghaiStringSerializer extends StdSerializer<Instant> {

        InstantAsShanghaiStringSerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(DATE_TIME_FORMATTER.format(value.atZone(ZONE)));
        }
    }

    static final class LongFromStringDeserializer extends StdDeserializer<Long> {

        LongFromStringDeserializer() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext ctxt) throws JacksonException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_NUMBER_INT) {
                return parser.getLongValue();
            }
            String text = parser.getString();
            return text == null || text.isBlank() ? null : Long.valueOf(text);
        }
    }

    static final class BigIntegerFromStringDeserializer extends StdDeserializer<BigInteger> {

        BigIntegerFromStringDeserializer() {
            super(BigInteger.class);
        }

        @Override
        public BigInteger deserialize(JsonParser parser, DeserializationContext ctxt) throws JacksonException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_NUMBER_INT) {
                return BigInteger.valueOf(parser.getLongValue());
            }
            String text = parser.getString();
            return text == null || text.isBlank() ? null : new BigInteger(text);
        }
    }

    static final class BigDecimalFromStringDeserializer extends StdDeserializer<BigDecimal> {

        BigDecimalFromStringDeserializer() {
            super(BigDecimal.class);
        }

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext ctxt) throws JacksonException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_NUMBER_FLOAT || token == JsonToken.VALUE_NUMBER_INT) {
                return parser.getDecimalValue();
            }
            String text = parser.getString();
            return text == null || text.isBlank() ? null : new BigDecimal(text);
        }
    }

    static final class InstantFromShanghaiStringDeserializer extends StdDeserializer<Instant> {

        InstantFromShanghaiStringDeserializer() {
            super(Instant.class);
        }

        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext ctxt) throws JacksonException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            String text = parser.getString();
            if (text == null || text.isBlank()) {
                return null;
            }
            return LocalDateTime.parse(text, DATE_TIME_FORMATTER).atZone(ZONE).toInstant();
        }
    }
}
