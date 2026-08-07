package com.jason.demo.demo2.framework.jackson;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 写出：Long/long/BigInteger → 字符串；LocalDateTime/Instant → yyyy-MM-dd HH:mm:ss（Asia/Shanghai）。
 */
@Configuration
public class JacksonJsonCustomizer {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
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
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        module.addSerializer(Instant.class, new InstantAsShanghaiStringSerializer());
        return module;
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
}
